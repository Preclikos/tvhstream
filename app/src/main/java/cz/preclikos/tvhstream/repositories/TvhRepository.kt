package cz.preclikos.tvhstream.repositories

import cz.preclikos.tvhstream.htsp.ChannelUi
import cz.preclikos.tvhstream.htsp.EpgEventEntry
import cz.preclikos.tvhstream.htsp.HtspEvent
import cz.preclikos.tvhstream.htsp.HtspMessage
import cz.preclikos.tvhstream.htsp.HtspService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
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

class TvhRepository(
    private val htsp: HtspService,
    private val ioDispatcher: CoroutineDispatcher
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    // ===== Status =====
    private val _status = MutableStateFlow("Disconnected")
    val status: StateFlow<String> = _status
    fun setStatus(text: String) { _status.value = text }

    // ===== Channels =====
    private data class ChannelEntry(
        val id: Int,
        val name: String,
        val number: Int?
    )

    private val channelMap = linkedMapOf<Int, ChannelEntry>()
    private val _channelsUi = MutableStateFlow<List<ChannelUi>>(emptyList())
    val channelsUi: StateFlow<List<ChannelUi>> = _channelsUi

    // Barrier: repo-level jistota, že kanály jsou opravdu zpracované
    private var channelsReadyDef = CompletableDeferred<Unit>()

    // ===== EPG =====
    private val epgByChannel = mutableMapOf<Int, MutableStateFlow<List<EpgEventEntry>>>()
    fun epgForChannel(channelId: Int): StateFlow<List<EpgEventEntry>> =
        epgByChannel.getOrPut(channelId) { MutableStateFlow(emptyList()) }

    private enum class EpgSnapshotState { NOT_LOADED, LOADED }
    private val epgSnapshotState = mutableMapOf<Int, EpgSnapshotState>()
    private val epgSnapshotInFlight = mutableSetOf<Int>()

    // Jeden mutex na všechny “mutable state” operace (channels + epg ingest + snapshot tracking)
    private val stateMutex = Mutex()

    private var epgBackfillJob: Job? = null

    @Volatile private var started = false

    fun startIfNeeded() {
        if (started) return
        started = true

        scope.launch {
            htsp.events.collect { e ->
                when (e) {
                    is HtspEvent.ServerMessage -> handleServerMessage(e.msg)
                    is HtspEvent.ConnectionError -> {
                        stopEpgSnapshotWorker()
                        setStatus("Error: ${e.error.message ?: e.error}")
                    }
                }
            }
        }
    }

    /**
     * Zavolej před každým novým connectem / reconnectem.
     * Vyčistí stav a resetne barrier.
     */
    suspend fun onNewConnectionStarting() {
        stateMutex.withLock {
            channelMap.clear()
            _channelsUi.value = emptyList()

            // EPG nechávám čistě podle tvé preference:
            // - pokud chceš při reconnectu EPG zahodit => clear i epgByChannel + snapshotState
            epgByChannel.clear()
            epgSnapshotState.clear()
            epgSnapshotInFlight.clear()

            channelsReadyDef = CompletableDeferred()
        }
    }

    suspend fun awaitChannelsReady(timeoutMs: Long = 30_000) {
        withTimeout(timeoutMs) { channelsReadyDef.await() }
    }

    // ===== EPG Snapshot worker (1× per channel) =====

    fun startEpgSnapshotWorker(
        batchSize: Int = 5,
        intervalMs: Long = 2_000,
        windowPastSec: Long = 2 * 3600,
        windowFutureSec: Long = 12 * 3600
    ) {
        if (epgBackfillJob?.isActive == true) return

        epgBackfillJob = scope.launch {
            while (isActive) {
                val targets = stateMutex.withLock { pickChannelsNeedingSnapshotLocked(batchSize) }
                if (targets.isEmpty()) {
                    delay(5_000)
                    continue
                }

                val nowSec = System.currentTimeMillis() / 1000L
                val fromSec = nowSec - windowPastSec
                val toSec = nowSec + windowFutureSec

                var ok = 0
                for (chId in targets) {
                    if (!isActive) break
                    if (fetchEpgSnapshotOnce(chId, fromSec, toSec)) ok++
                    delay(150) // jemné “neDDOS”
                }

                setStatus("EPG snapshot: $ok/${targets.size}")
                delay(intervalMs)
            }
        }
    }

    fun stopEpgSnapshotWorker() {
        epgBackfillJob?.cancel()
        epgBackfillJob = null
    }

    private suspend fun fetchEpgSnapshotOnce(
        channelId: Int,
        fromSec: Long,
        toSec: Long
    ): Boolean {
        // atomicky rozhodni: smí se snapshot udělat?
        stateMutex.withLock {
            val st = epgSnapshotState[channelId] ?: EpgSnapshotState.NOT_LOADED
            if (st == EpgSnapshotState.LOADED) return false
            if (!epgSnapshotInFlight.add(channelId)) return false
        }

        try {
            val reply = runCatching {
                htsp.request(
                    method = "getEvents",
                    fields = mapOf(
                        "channelId" to channelId,
                        "start" to fromSec,
                        "stop" to toSec
                    ),
                    timeoutMs = 20_000
                )
            }.getOrNull() ?: return false

            if (reply.fields.containsKey("error")) return false

            // ingest + označení LOADED musí být pod zámkem
            stateMutex.withLock {
                ingestGetEventsReplyLocked(reply)
                epgSnapshotState[channelId] = EpgSnapshotState.LOADED
            }
            return true
        } finally {
            stateMutex.withLock { epgSnapshotInFlight.remove(channelId) }
        }
    }

    private fun pickChannelsNeedingSnapshotLocked(limit: Int): List<Int> {
        if (channelMap.isEmpty()) return emptyList()

        val sortedIds = channelMap.values
            .sortedWith(
                compareBy<ChannelEntry>(
                    { it.number == null },
                    { it.number ?: Int.MAX_VALUE },
                    { it.name.lowercase() },
                    { it.id }
                )
            )
            .map { it.id }

        val out = ArrayList<Int>(limit)
        for (id in sortedIds) {
            val st = epgSnapshotState[id] ?: EpgSnapshotState.NOT_LOADED
            if (st == EpgSnapshotState.NOT_LOADED && !epgSnapshotInFlight.contains(id)) {
                out.add(id)
                if (out.size == limit) break
            }
        }
        return out
    }

    // ===== Server messages =====

    private suspend fun handleServerMessage(msg: HtspMessage) {
        when (msg.method) {
            // Channels
            "channelAdd", "channelUpdate" -> stateMutex.withLock { handleChannelLocked(msg) }
            "channelDelete" -> stateMutex.withLock { handleChannelDeleteLocked(msg) }

            // Barrier marker (po enableAsyncMetadata)
            "initialSyncCompleted" -> {
                stateMutex.withLock {
                    publishChannelsLocked()
                    if (!channelsReadyDef.isCompleted) channelsReadyDef.complete(Unit)
                }
            }

            // EPG live stream
            "eventAdd", "eventUpdate" -> stateMutex.withLock { handleEventUpsertLocked(msg) }
            "eventDelete" -> stateMutex.withLock { handleEventDeleteLocked(msg) }
        }
    }

    // ===== Channels handling =====

    private fun handleChannelLocked(msg: HtspMessage) {
        when (msg.method) {
            "channelAdd" -> {
                val id = msg.int("channelId") ?: return
                val name = msg.str("channelName") ?: return
                val number =
                    msg.int("channelNumber")
                        ?: msg.int("number")
                        ?: msg.int("lcn")
                        ?: msg.int("channelNum")
                        ?: msg.int("channelno")

                channelMap[id] = ChannelEntry(id, name, number)

                // snapshot pro nový kanál ještě nebyl
                if (epgSnapshotState[id] == null) epgSnapshotState[id] = EpgSnapshotState.NOT_LOADED

                publishChannelsLocked()
            }

            "channelUpdate" -> {
                val id = msg.int("channelId") ?: return
                val existing = channelMap[id] ?: return

                val newName = msg.str("channelName") ?: existing.name
                val newNumber =
                    msg.int("channelNumber")
                        ?: msg.int("number")
                        ?: msg.int("lcn")
                        ?: msg.int("channelNum")
                        ?: msg.int("channelno")
                        ?: existing.number

                channelMap[id] = existing.copy(name = newName, number = newNumber)
                publishChannelsLocked()
            }
        }
    }

    private fun handleChannelDeleteLocked(msg: HtspMessage) {
        val id = msg.int("channelId") ?: return

        channelMap.remove(id)
        epgByChannel.remove(id)

        // dovol znovu snapshot, pokud se kanál objeví znovu
        epgSnapshotState.remove(id)
        epgSnapshotInFlight.remove(id)

        publishChannelsLocked()
    }

    private fun publishChannelsLocked() {
        val sorted = channelMap.values
            .sortedWith(
                compareBy<ChannelEntry>(
                    { it.number == null },
                    { it.number ?: Int.MAX_VALUE },
                    { it.name.lowercase() },
                    { it.id }
                )
            )
            .map { ChannelUi(it.id, formatName(it)) }

        _channelsUi.value = sorted
        setStatus("Channels: ${sorted.size}")
    }

    private fun formatName(c: ChannelEntry): String =
        if (c.number != null) "${c.number}  ${c.name}" else c.name

    // ===== EPG live handling =====

    fun nowEvent(channelId: Int, nowSec: Long): EpgEventEntry? {
        val list = epgByChannel[channelId]?.value ?: return null
        return list.firstOrNull { it.start <= nowSec && nowSec < it.stop }
            ?: list.minByOrNull { kotlin.math.abs(it.start - nowSec) }
    }

    private fun handleEventUpsertLocked(msg: HtspMessage) {
        val eventId = msg.int("eventId") ?: msg.int("id") ?: return
        val channelId = msg.int("channelId") ?: msg.int("channel") ?: return

        val title = msg.str("title") ?: msg.str("eventTitle") ?: msg.str("name") ?: "—"
        val summary = msg.str("summary") ?: msg.str("description")

        val start = msg.long("start") ?: msg.long("startTime") ?: return
        val stop = msg.long("stop") ?: msg.long("stopTime") ?: return

        val flow = epgByChannel.getOrPut(channelId) { MutableStateFlow(emptyList()) }
        flow.value = upsertAndTrim(
            list = flow.value,
            item = EpgEventEntry(eventId, channelId, start, stop, title, summary),
            nowSec = System.currentTimeMillis() / 1000L,
            keepPastSec = 2 * 3600,
            keepFutureSec = 12 * 3600
        )
    }

    private fun handleEventDeleteLocked(msg: HtspMessage) {
        val eventId = msg.int("eventId") ?: msg.int("id") ?: return
        val channelId = msg.int("channelId") ?: msg.int("channel") ?: return

        val flow = epgByChannel[channelId] ?: return
        flow.value = flow.value.filterNot { it.eventId == eventId }
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

        val replaced = buildList {
            var found = false
            for (e in list) {
                if (e.eventId == item.eventId) {
                    add(item); found = true
                } else add(e)
            }
            if (!found) add(item)
        }

        return replaced
            .asSequence()
            .filter { it.stop >= from && it.start <= to }
            .sortedBy { it.start }
            .toList()
    }

    // ===== EPG snapshot ingest (reply) =====

    fun ingestGetEventsReply(reply: HtspMessage) {
        // pokud to někdo volá zvenku, zajisti zámek
        scope.launch {
            stateMutex.withLock { ingestGetEventsReplyLocked(reply) }
        }
    }

    private fun ingestGetEventsReplyLocked(reply: HtspMessage) {
        val raw = reply.fields["events"]
            ?: reply.fields["epg"]
            ?: reply.fields["entries"]
            ?: return

        @Suppress("UNCHECKED_CAST")
        val list = raw as? List<Map<String, Any?>> ?: return

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
                nowSec = System.currentTimeMillis() / 1000L,
                keepPastSec = 2 * 3600,
                keepFutureSec = 12 * 3600
            )
        }
    }
}
