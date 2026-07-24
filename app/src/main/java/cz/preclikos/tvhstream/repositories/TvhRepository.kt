package cz.preclikos.tvhstream.repositories

import cz.preclikos.tvhstream.R
import cz.preclikos.tvhstream.core.coverageForEvents
import cz.preclikos.tvhstream.htsp.ChannelUi
import cz.preclikos.tvhstream.htsp.EpgEventEntry
import cz.preclikos.tvhstream.htsp.HtspEvent
import cz.preclikos.tvhstream.htsp.HtspMessage
import cz.preclikos.tvhstream.htsp.HtspService
import cz.preclikos.tvhstream.services.StatusService
import cz.preclikos.tvhstream.services.StatusSlot
import cz.preclikos.tvhstream.services.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class EpgCoverage(
    var coveredFrom: Long = Long.MAX_VALUE,  // earliest event.start we have
    var coveredTo: Long = 0L,                // latest event.stop we have
    var lastRefreshSec: Long = 0L            // last time we asked server for this channel
)

class TvhRepository(
    private val htsp: HtspService,
    ioDispatcher: CoroutineDispatcher,
    private val statusService: StatusService
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    // ---------------------------
    // Tunables (your requested behavior)
    // ---------------------------

    /**
     * Fast start: fill a small window quickly so UI is usable right away.
     */
    private val warmupFutureSec = 4 * 3600L

    @Volatile
    private var warmupCompleted = false

    /**
     * After warmup, keep at least this much future ahead (top-up when below).
     */
    private val steadyMinFutureSec = 20 * 3600L

    /**
     * Try to keep up to this much future (cap) so cache doesn't balloon.
     */
    private val steadyMaxFutureSec = 24 * 3600L

    /**
     * Each top-up extends horizon by this much (per request).
     * (Keep it moderate so you don't DDOS tvheadend.)
     */
    private val topUpChunkSec = 4 * 3600L

    /**
     * How much past we retain in cache (for "now/prev" and seeking).
     */
    private val keepPastSec = 6 * 3600L

    /**
     * How much future we retain in cache (should be >= steadyMaxFutureSec).
     */
    private val keepFutureSec = steadyMaxFutureSec

    /**
     * Overlap when extending horizon to avoid boundary gaps.
     */
    private val horizonOverlapSec = 10 * 60L

    /**
     * Don't refresh same channel too often (prevents spinning).
     */
    private val perChannelCooldownSec = 10 * 60L

    // Worker pacing
    private val requestDelayMs = 250L
    private val idleDelayMs = 3_000L

    // ---------------------------
    // Status helpers
    // ---------------------------

    private var lastStatusMs = 0L
    private fun setStatusThrottled(
        text: UiText,
        slot: StatusSlot = StatusSlot.EPG,
        minIntervalMs: Long = 700L
    ) {
        val now = System.currentTimeMillis()
        if (now - lastStatusMs >= minIntervalMs) {
            lastStatusMs = now
            statusService.set(slot, text)
        }
    }

    private fun setStatus(text: UiText, slot: StatusSlot = StatusSlot.SYNC) {
        statusService.set(slot, text)
    }

    // ---------------------------
    // Channels
    // ---------------------------

    private val channelStore = ChannelSnapshotStore()
    private val _channelsUi = MutableStateFlow<List<ChannelUi>>(emptyList())
    val channelsUi: StateFlow<List<ChannelUi>> = _channelsUi

    private var channelsReadyDef = CompletableDeferred<Unit>()

    // ---------------------------
    // EPG store
    // ---------------------------

    private val epgByChannel = mutableMapOf<Int, MutableStateFlow<List<EpgEventEntry>>>()
    fun epgForChannel(channelId: Int): StateFlow<List<EpgEventEntry>> =
        epgByChannel.getOrPut(channelId) { MutableStateFlow(emptyList()) }

    /**
     * Coverage is what replaces "NOT_LOADED/LOADED".
     * We use it to maintain a sliding horizon.
     */
    private val epgCoverage = mutableMapOf<Int, EpgCoverage>()

    /**
     * Prevent parallel duplicate requests per channel.
     */
    private val epgInFlight = mutableSetOf<Int>()

    private val stateMutex = Mutex()

    // Worker
    private var epgWorkerJob: Job? = null

    // Lifecycle
    @Volatile
    private var started = false

    fun startIfNeeded() {
        if (started) return
        started = true

        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            htsp.controlEvents.collect { e ->
                when (e) {
                    is HtspEvent.ServerMessage -> handleServerMessage(e.msg)
                    else -> {}
                }
            }
        }
    }

    suspend fun onDisconnected() {
        stopEpgWorker()
        onNewConnectionStarting()
    }

    suspend fun onNewConnectionStarting() {
        stateMutex.withLock {
            channelStore.reset()
            _channelsUi.value = emptyList()

            epgByChannel.clear()
            epgCoverage.clear()
            epgInFlight.clear()

            // Force a fresh warmup for the new connection; coverage was just cleared.
            warmupCompleted = false

            channelsReadyDef = CompletableDeferred()
        }
    }

    suspend fun awaitChannelsReady(timeoutMs: Long = 30_000) {
        withTimeout(timeoutMs) { channelsReadyDef.await() }
    }

    // ---------------------------
    // EPG Worker (warmup -> steady sliding horizon)
    // ---------------------------

    /**
     * Starts an EPG worker that:
     *  - warmup: fill ~4h future quickly for all channels
     *  - steady: keep future horizon between 20–24h by periodic top-ups
     *
     * You can call this after initialSyncCompleted (or whenever channels are ready).
     */
    fun startEpgWorker(
        batchSize: Int = 6,
        intervalMs: Long = 1_500L
    ) {
        if (epgWorkerJob?.isActive == true) return

        epgWorkerJob = scope.launch {
            setStatus(UiText.Res(R.string.status_epg_loading), StatusSlot.EPG)

            while (isActive) {
              try {
                val nowSec = nowSec()

                val targets = stateMutex.withLock {
                    pickChannelsNeedingTopUpLocked(
                        nowSec = nowSec,
                        limit = batchSize
                    )
                }

                if (targets.isEmpty()) {
                    val (warmDone, total) = stateMutex.withLock { warmupProgressLocked(nowSec) }
                    if (total > 0 && warmDone >= total) {
                        warmupCompleted = true
                        setStatusThrottled(
                            UiText.Res(R.string.status_epg_steady_warmup, listOf(warmDone, total)),
                            StatusSlot.EPG,
                            minIntervalMs = 2_000
                        )
                    } else if (total > 0) {
                        setStatusThrottled(
                            UiText.Res(R.string.status_epg_warmup, listOf(warmDone, total)),
                            StatusSlot.EPG,
                            minIntervalMs = 1_000
                        )
                    }
                    delay(idleDelayMs)
                    continue
                }

                var ok = 0
                for (chId in targets) {
                    if (!isActive) break
                    val did = fetchEpgTopUpOnce(channelId = chId, nowSec = nowSec)
                    if (did) ok++
                    delay(requestDelayMs)
                }

                val (warmDone, total) = stateMutex.withLock { warmupProgressLocked(nowSec) }
                setStatusThrottled(
                    UiText.Res(
                        R.string.status_epg_warmup_batch,
                        listOf(warmDone, total, ok, targets.size)
                    ),
                    StatusSlot.EPG,
                    minIntervalMs = 1_000
                )

                delay(intervalMs)
              } catch (ce: CancellationException) {
                  throw ce
              } catch (t: Throwable) {
                  // A single failed iteration (transient request/parse error) must never
                  // kill the worker, otherwise EPG would silently stop refreshing.
                  Timber.w(t, "EPG worker iteration failed; backing off")
                  delay(idleDelayMs)
              }
            }
        }
    }

    fun stopEpgWorker() {
        epgWorkerJob?.cancel()
        epgWorkerJob = null
    }

    /**
     * Decides how far we should fetch for a channel right now.
     * Uses a sliding horizon:
     *  - if channel isn't warmed up, aim for now+warmupFuture
     *  - else keep horizon >= now+steadyMinFuture, extending in chunks up to steadyMaxFuture
     */
    private suspend fun fetchEpgTopUpOnce(channelId: Int, nowSec: Long): Boolean {
        // reserve channel
        stateMutex.withLock {
            if (!epgInFlight.add(channelId)) return false
            epgCoverage.getOrPut(channelId) { EpgCoverage() }
        }

        try {
            val desiredMaxTo = nowSec + steadyMaxFutureSec
            val desiredWarmTo = nowSec + warmupFutureSec
            val desiredMinTo = nowSec + steadyMinFutureSec

            val targetTo: Long = stateMutex.withLock {
                val cov = epgCoverage[channelId] ?: EpgCoverage()

                // Cooldown check (avoid hammering same channel)
                if (nowSec - cov.lastRefreshSec < perChannelCooldownSec) return false

                val currentTo = cov.coveredTo

                // Warmup phase for this channel?
                if (currentTo < desiredWarmTo) {
                    min(desiredWarmTo, desiredMaxTo)
                } else {
                    // Steady phase: if below min future, extend by chunk, capped to max
                    if (currentTo < desiredMinTo) {
                        min(currentTo + topUpChunkSec, desiredMaxTo)
                    } else {
                        // already good
                        return false
                    }
                }
            }

            // If we're here, we want to fetch up to targetTo.
            // Use epgMaxTime (HTSP v6+). It’s a Unix timestamp (seconds).
            val reply = runCatching {
                htsp.request(
                    method = "getEvents",
                    fields = mapOf(
                        "channelId" to channelId,
                        "epgMaxTime" to (targetTo),
                        // optional helpers if your server/client supports them:
                        // "numFollowing" to 200
                    ),
                    timeoutMs = 20_000
                )
            }.getOrNull() ?: return false

            if (reply.fields.containsKey("error")) return false

            stateMutex.withLock {
                // ingest + update coverage from reply
                val ingest = ingestGetEventsReplyLocked(reply, nowSec = nowSec)
                // mark refresh time even if ingest returned 0 (so we don't spin)
                epgCoverage.getOrPut(channelId) { EpgCoverage() }.lastRefreshSec = nowSec

                // Aggressive trimming pass (keeps cache in bounds)
                trimAllEpgLocked(nowSec)
                return ingest.totalEvents > 0 || true
            }
        } finally {
            stateMutex.withLock { epgInFlight.remove(channelId) }
        }
    }

    /**
     * Pick channels that either:
     *  - are not warmed up (coveredTo < now+warmupFuture)
     *  - or need steady top-up (coveredTo < now+steadyMinFuture)
     *
     * Prioritize the ones with the smallest coveredTo first.
     */
    private fun pickChannelsNeedingTopUpLocked(nowSec: Long, limit: Int): List<Int> {
        if (channelStore.isEmpty()) return emptyList()

        val wantWarmTo = nowSec + warmupFutureSec
        val wantMinTo = nowSec + steadyMinFutureSec

        // Order channels by number/name, but select targets by "how far behind horizon they are"
        val sortedIds = channelStore.snapshot()
            .map { it.id }

        val candidates = sortedIds.asSequence()
            .filter { id ->
                if (epgInFlight.contains(id)) return@filter false
                val cov = epgCoverage.getOrPut(id) { EpgCoverage() }
                // Needs warmup or steady top-up
                (cov.coveredTo < wantWarmTo) || (cov.coveredTo < wantMinTo)
            }
            .sortedBy { id -> epgCoverage[id]?.coveredTo ?: 0L }
            .take(limit)
            .toList()

        return candidates
    }

    private fun warmupProgressLocked(nowSec: Long): Pair<Int, Int> {
        val total = channelStore.size
        if (total == 0) return 0 to 0
        val wantWarmTo = nowSec + warmupFutureSec
        val done = channelStore.ids().count { id ->
            val cov = epgCoverage[id]
            cov != null && cov.coveredTo >= wantWarmTo
        }
        return done to total
    }

    // ---------------------------
    // Server message handling
    // ---------------------------

    private suspend fun handleServerMessage(msg: HtspMessage) {
        when (msg.method) {
            "channelAdd", "channelUpdate" -> stateMutex.withLock { handleChannelLocked(msg) }
            "channelDelete" -> stateMutex.withLock { handleChannelDeleteLocked(msg) }

            "initialSyncCompleted" -> {
                val count = stateMutex.withLock {
                    publishChannelsLocked(channelStore.completeInitialSync())
                    if (!channelsReadyDef.isCompleted) channelsReadyDef.complete(Unit)
                    channelStore.size
                }
                setStatus(
                    UiText.Res(R.string.status_channels_ready, listOf(count)),
                    StatusSlot.SYNC
                )
            }

            // Async EPG updates (only when server data changes)
            "eventAdd", "eventUpdate" -> stateMutex.withLock { handleEventUpsertLocked(msg) }
            "eventDelete" -> stateMutex.withLock { handleEventDeleteLocked(msg) }
        }
    }

    private fun handleChannelLocked(msg: HtspMessage) {
        val id = msg.int("channelId") ?: return
        val existing = channelStore[id]
        val isNew = existing == null

        val name = msg.str("channelName") ?: existing?.name ?: return
        val number = msg.int("channelNumber")
            ?: msg.int("number")
            ?: msg.int("lcn")
            ?: msg.int("channelNum")
            ?: msg.int("channelno")
            ?: existing?.number
        val icon = msg.str("channelIcon") ?: existing?.icon

        val snapshot = channelStore.upsert(ChannelMetadata(id, name, number, icon))
        epgCoverage.getOrPut(id) { EpgCoverage() }

        snapshot?.let(::publishChannelsLocked)
        setStatusThrottled(
            UiText.Res(R.string.status_syncing_channels, listOf(channelStore.size)),
            StatusSlot.SYNC,
            minIntervalMs = 700
        )

        if (isNew && warmupCompleted) {
            scope.launch { fetchEpgTopUpOnce(channelId = id, nowSec = nowSec()) }
        }
    }

    private fun handleChannelDeleteLocked(msg: HtspMessage) {
        val id = msg.int("channelId") ?: return

        val snapshot = channelStore.delete(id)
        epgByChannel.remove(id)
        epgCoverage.remove(id)
        epgInFlight.remove(id)

        snapshot?.let(::publishChannelsLocked)
    }

    private fun publishChannelsLocked(channels: List<ChannelMetadata>) {
        _channelsUi.value = channels.map { ChannelUi(it.id, formatName(it), it.icon) }
    }

    private fun formatName(c: ChannelMetadata): String =
        if (c.number != null) "${c.number}  ${c.name}" else c.name

    // ---------------------------
    // Query helpers
    // ---------------------------

    fun nowEvent(channelId: Int, nowSec: Long): EpgEventEntry? {
        val list = epgByChannel[channelId]?.value ?: return null
        return list.firstOrNull { it.start <= nowSec && nowSec < it.stop }
            ?: list.minByOrNull { abs(it.start - nowSec) }
    }

    fun nextEvent(channelId: Int, nowSec: Long): EpgEventEntry? {
        val list = epgByChannel[channelId]?.value ?: return null
        return list.firstOrNull { it.start > nowSec }
    }

    // ---------------------------
    // Async event handling (updates coverage too)
    // ---------------------------

    private fun handleEventUpsertLocked(msg: HtspMessage) {
        val eventId = msg.int("eventId") ?: msg.int("id") ?: return
        val channelId = msg.int("channelId") ?: msg.int("channel") ?: return

        val title = msg.str("title") ?: msg.str("eventTitle") ?: msg.str("name") ?: "—"
        val summary = msg.str("summary") ?: msg.str("description")

        val start = msg.long("start") ?: msg.long("startTime") ?: return
        val stop = msg.long("stop") ?: msg.long("stopTime") ?: return

        val nowSec = nowSec()
        val flow = epgByChannel.getOrPut(channelId) { MutableStateFlow(emptyList()) }
        flow.value = upsertAndTrim(
            list = flow.value,
            item = EpgEventEntry(eventId, channelId, start, stop, title, summary),
            nowSec = nowSec,
            keepPastSec = keepPastSec,
            keepFutureSec = keepFutureSec
        )

        // Update coverage
        val cov = epgCoverage.getOrPut(channelId) { EpgCoverage() }
        cov.coveredFrom = min(cov.coveredFrom, start)
        cov.coveredTo = max(cov.coveredTo, stop)
    }

    private fun handleEventDeleteLocked(msg: HtspMessage) {
        val eventId = msg.int("eventId") ?: msg.int("id") ?: return
        val channelId = msg.int("channelId") ?: msg.int("channel") ?: return

        val flow = epgByChannel[channelId] ?: return
        flow.value = flow.value.filterNot { it.eventId == eventId }
        // Coverage not strictly recomputed here (not worth it). Worker will top-up if needed.
    }

    // ---------------------------
    // Ingest getEvents reply + update coverage
    // ---------------------------

    private data class IngestResult(
        val totalEvents: Int,
        val perChannelMinStart: MutableMap<Int, Long>,
        val perChannelMaxStop: MutableMap<Int, Long>
    )

    private fun ingestGetEventsReplyLocked(reply: HtspMessage, nowSec: Long): IngestResult {
        val raw = reply.fields["events"]
            ?: reply.fields["epg"]
            ?: reply.fields["entries"]
            ?: return IngestResult(0, mutableMapOf(), mutableMapOf())

        @Suppress("UNCHECKED_CAST")
        val list = raw as? List<Map<String, Any?>> ?: return IngestResult(
            0,
            mutableMapOf(),
            mutableMapOf()
        )

        val minStart = mutableMapOf<Int, Long>()
        val maxStop = mutableMapOf<Int, Long>()
        var total = 0

        for (ev in list) {
            val eventId = (ev["eventId"] as? Number)?.toInt()
                ?: (ev["id"] as? Number)?.toInt()
                ?: continue

            val channelId = (ev["channelId"] as? Number)?.toInt()
                ?: (ev["channel"] as? Number)?.toInt()
                ?: continue

            val title = (ev["title"] as? String)
                ?: (ev["eventTitle"] as? String)
                ?: (ev["name"] as? String)
                ?: "—"

            val summary = (ev["summary"] as? String) ?: (ev["description"] as? String)

            val start = (ev["start"] as? Number)?.toLong()
                ?: (ev["startTime"] as? Number)?.toLong()
                ?: continue

            val stop = (ev["stop"] as? Number)?.toLong()
                ?: (ev["stopTime"] as? Number)?.toLong()
                ?: continue

            val flow = epgByChannel.getOrPut(channelId) { MutableStateFlow(emptyList()) }
            flow.value = upsertAndTrim(
                list = flow.value,
                item = EpgEventEntry(eventId, channelId, start, stop, title, summary),
                nowSec = nowSec,
                keepPastSec = keepPastSec,
                keepFutureSec = keepFutureSec
            )

            minStart[channelId] = min(minStart[channelId] ?: Long.MAX_VALUE, start)
            maxStop[channelId] = max(maxStop[channelId] ?: 0L, stop)
            total++
        }

        // Apply coverage updates
        for ((ch, mn) in minStart) {
            val cov = epgCoverage.getOrPut(ch) { EpgCoverage() }
            cov.coveredFrom = min(cov.coveredFrom, mn)
        }
        for ((ch, mx) in maxStop) {
            val cov = epgCoverage.getOrPut(ch) { EpgCoverage() }
            cov.coveredTo = max(cov.coveredTo, mx)
        }

        return IngestResult(total, minStart, maxStop)
    }

    // ---------------------------
    // Trimming / maintenance
    // ---------------------------

    private fun trimAllEpgLocked(nowSec: Long) {
        // Trim each channel's list to keepPast/keepFuture bounds
        for ((chId, flow) in epgByChannel) {
            flow.value = flow.value
                .asSequence()
                .filter { e ->
                    e.stop >= (nowSec - keepPastSec) && e.start <= (nowSec + keepFutureSec)
                }
                .sortedBy { it.start }
                .toList()
        }

        // Re-derive coverage from what we actually still hold. Coverage must track
        // the retained events authoritatively (NOT a monotonic max): otherwise after
        // a long uptime the horizon can stay "high" while the cache has already been
        // trimmed empty, so the worker believes it is up to date and stops topping up
        // -> "No EPG" everywhere until reconnect. Resetting coverage for an emptied
        // channel makes the worker re-fetch it on the next tick (self-healing).
        for ((chId, flow) in epgByChannel) {
            val cov = epgCoverage.getOrPut(chId) { EpgCoverage() }
            val derived = coverageForEvents(flow.value)
            cov.coveredFrom = derived.from
            cov.coveredTo = derived.to
        }
    }

    private fun upsertAndTrim(
        list: List<EpgEventEntry>,
        item: EpgEventEntry,
        nowSec: Long,
        keepPastSec: Long,
        keepFutureSec: Long
    ): List<EpgEventEntry> {
        val from = nowSec - keepPastSec
        val to = nowSec + keepFutureSec

        val replaced = buildList(list.size + 1) {
            var found = false
            for (e in list) {
                if (e.eventId == item.eventId) {
                    add(item)
                    found = true
                } else {
                    add(e)
                }
            }
            if (!found) add(item)
        }

        return replaced
            .asSequence()
            .filter { it.stop >= from && it.start <= to }
            .sortedBy { it.start }
            .toList()
    }

    // ---------------------------
    // Utils
    // ---------------------------

    private fun nowSec(): Long = System.currentTimeMillis() / 1000L
}
