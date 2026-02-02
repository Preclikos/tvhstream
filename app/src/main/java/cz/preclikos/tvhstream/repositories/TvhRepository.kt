package cz.preclikos.tvhstream.repositories

import cz.preclikos.tvhstream.htsp.ChannelUi
import cz.preclikos.tvhstream.htsp.EpgEventEntry
import cz.preclikos.tvhstream.htsp.HtspEvent
import cz.preclikos.tvhstream.htsp.HtspMessage
import cz.preclikos.tvhstream.htsp.HtspService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

class TvhRepository(
    private val htsp: HtspService,
    private val ioDispatcher: CoroutineDispatcher
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private var epgBackfillJob: Job? = null
    private val ingestMutex = Mutex()

    // ===== Status =====
    private val _status = MutableStateFlow("Disconnected")
    val status: StateFlow<String> = _status

    fun setStatus(text: String) {
        _status.value = text
    }

    // ===== Channels =====
    private data class ChannelEntry(
        val id: Int,
        val name: String,
        val number: Int?
    )

    private val channelMap = linkedMapOf<Int, ChannelEntry>() // key = channelId
    private val _channelsUi = MutableStateFlow<List<ChannelUi>>(emptyList())
    val channelsUi: StateFlow<List<ChannelUi>> = _channelsUi

    // ===== EPG =====
    private val epgByChannel = mutableMapOf<Int, MutableStateFlow<List<EpgEventEntry>>>()

    fun epgForChannel(channelId: Int): StateFlow<List<EpgEventEntry>> =
        epgByChannel.getOrPut(channelId) { MutableStateFlow(emptyList()) }

    @Volatile
    private var started = false

    fun startIfNeeded() {
        if (started) return
        started = true

        scope.launch {
            htsp.events.collect { e ->
                when (e) {
                    is HtspEvent.ServerMessage -> handleServerMessage(e.msg)
                    is HtspEvent.ConnectionError -> {
                        stopEpgBackfillWorker()
                        setStatus("Error: ${e.error.message ?: e.error}")
                    }
                }
            }
        }
    }

    /**
     * IMPORTANT:
     * getChannels reply jde přes seq -> pending, takže se typicky NEDOSTANE do events.
     * Tohle je explicitní ingest odpovědi.
     */
    fun ingestGetChannelsReply(reply: HtspMessage) {
        val raw = reply.fields["channels"] ?: run {
            setStatus("getChannels: no 'channels' field (keys=${reply.fields.keys})")
            return
        }

        @Suppress("UNCHECKED_CAST")
        val list = raw as? List<Map<String, Any?>> ?: run {
            setStatus("getChannels: 'channels' has unexpected type: ${raw::class.java}")
            return
        }

        for (ch in list) {
            val id = (ch["channelId"] as? Number)?.toInt() ?: continue
            val name = ch["channelName"] as? String ?: continue

            val number =
                (ch["channelNumber"] as? Number)?.toInt()
                    ?: (ch["number"] as? Number)?.toInt()
                    ?: (ch["lcn"] as? Number)?.toInt()
                    ?: (ch["channelNum"] as? Number)?.toInt()
                    ?: (ch["channelno"] as? Number)?.toInt()

            channelMap[id] = ChannelEntry(id = id, name = name, number = number)
        }

        publishChannels()
    }

    private fun pickChannelsMissingEpg(limit: Int): List<Int> {
        // vezmeme kanály v pořadí podle UI (číslo->jméno), a vybereme ty, co mají empty list
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
            val flow = epgByChannel[id]
            val empty = flow == null || flow.value.isEmpty()
            if (empty) {
                out.add(id)
                if (out.size == limit) break
            }
        }
        return out
    }

    private suspend fun fetchEpgForChannelSafe(
        channelId: Int,
        fromSec: Long,
        toSec: Long
    ): Boolean {
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

        ingestMutex.withLock {
            ingestGetEventsReply(reply)
        }
        return true
    }

    fun startEpgBackfillWorker(
        batchSize: Int = 5,
        intervalMs: Long = 2_000,
        windowPastSec: Long = 2 * 3600,
        windowFutureSec: Long = 12 * 3600
    ) {
        // už běží? nech to být
        if (epgBackfillJob?.isActive == true) return

        epgBackfillJob = scope.launch {
            while (isActive) {
                // nemá smysl, dokud nemáme kanály
                if (channelMap.isEmpty()) {
                    delay(intervalMs)
                    continue
                }

                val nowSec = System.currentTimeMillis() / 1000L
                val fromSec = nowSec - windowPastSec
                val toSec = nowSec + windowFutureSec

                val targets = pickChannelsMissingEpg(batchSize)
                if (targets.isEmpty()) {
                    // všechno má něco → zpomal
                    delay(5_000)
                    continue
                }

                var ok = 0
                for (chId in targets) {
                    if (fetchEpgForChannelSafe(chId, fromSec, toSec)) ok++
                    // malá pauza, ať neDDOSuješ TVH
                    delay(150)
                }

                setStatus("EPG backfill: filled $ok/${targets.size}")
                delay(intervalMs)
            }
        }
    }

    fun stopEpgBackfillWorker() {
        epgBackfillJob?.cancel()
        epgBackfillJob = null
    }

    private fun handleServerMessage(msg: HtspMessage) {
        when (msg.method) {
            "channelAdd", "channelUpdate" -> handleChannel(msg)

            // EPG live stream (názvy se můžou lišit dle TVH)
            "eventAdd", "eventUpdate" -> handleEventUpsert(msg)
            "eventDelete" -> handleEventDelete(msg)
        }
    }

    suspend fun requestEpgForAllChannels(
        nowSec: Long = System.currentTimeMillis() / 1000L,
        fromSec: Long = nowSec - 2 * 3600,
        toSec: Long = nowSec + 12 * 3600,
        parallelism: Int = 6,
        timeoutMs: Long = 20_000
    ) = coroutineScope {
        val ids = channelMap.keys.toList()
        if (ids.isEmpty()) {
            setStatus("EPG: no channels")
            return@coroutineScope
        }

        val sem = Semaphore(parallelism)
        val ingestMutex = Mutex()
        val done = AtomicInteger(0)
        val ok = AtomicInteger(0)

        ids.map { chId ->
            async {
                sem.withPermit {
                    val reply = runCatching {
                        htsp.request(
                            method = "getEvents",
                            fields = mapOf(
                                "channelId" to chId,
                                "start" to fromSec,
                                "stop" to toSec
                            ),
                            timeoutMs = timeoutMs
                        )
                    }.getOrNull()

                    if (reply != null && !reply.fields.containsKey("error")) {
                        // ingest musí být sériový (jinak race)
                        ingestMutex.withLock {
                            ingestGetEventsReply(reply)
                        }
                        ok.incrementAndGet()
                    }

                    val d = done.incrementAndGet()
                    if (d % 10 == 0 || d == ids.size) {
                        setStatus("EPG: $d/${ids.size} channels (ok=${ok.get()})")
                    }
                }
            }
        }.awaitAll()

        setStatus("EPG loaded: ok=${ok.get()}/${ids.size}")
    }

    suspend fun requestInitialEpgWindow(
        nowSec: Long = System.currentTimeMillis() / 1000L,
        fromSec: Long = nowSec - 2 * 3600,
        toSec: Long = nowSec + 12 * 3600,
        channelLimit: Int = 50
    ) {
        val channelIds = channelMap.values
            .sortedWith(compareBy<ChannelEntry>(
                { it.number == null },
                { it.number ?: Int.MAX_VALUE },
                { it.name.lowercase() },
                { it.id }
            ))
            .take(channelLimit)
            .map { it.id }

        if (channelIds.isEmpty()) {
            setStatus("EPG: no channels yet")
            return
        }

        var ok = 0
        for (chId in channelIds) {
            val reply = runCatching {
                htsp.request(
                    method = "getEvents",
                    fields = mapOf(
                        "channelId" to chId,
                        "start" to fromSec,
                        "stop" to toSec
                    )
                )
            }.getOrElse { t ->
                // nepadat kvůli jednomu kanálu
                setStatus("EPG failed for $chId: ${t.message ?: t}")
                continue
            }

            ingestGetEventsReply(reply)
            ok++
        }

        setStatus("EPG loaded for channels: $ok/${channelIds.size}")
    }

    // ===== Channels handling (async) =====

    private fun handleChannel(msg: HtspMessage) {
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

                channelMap[id] = ChannelEntry(id = id, name = name, number = number)
                publishChannels()
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
                publishChannels()
            }
        }
    }

    private fun publishChannels() {
        val sorted = channelMap.values
            .sortedWith(
                compareBy<ChannelEntry>(
                    { it.number == null },                 // channels WITH number first
                    { it.number ?: Int.MAX_VALUE },        // then by channel number
                    { it.name.lowercase() },               // then by name
                    { it.id }                              // fallback
                )
            )
            .map { ChannelUi(it.id, formatName(it)) }

        _channelsUi.value = sorted
        setStatus("Channels: ${sorted.size}")
    }

    private fun formatName(c: ChannelEntry): String {
        return if (c.number != null) "${c.number}  ${c.name}" else c.name
    }

    // ===== EPG handling (async) =====

    fun nowEvent(channelId: Int, nowSec: Long): EpgEventEntry? {
        val list = epgByChannel[channelId]?.value ?: return null
        return list.firstOrNull { it.start <= nowSec && nowSec < it.stop }
            ?: list.minByOrNull { kotlin.math.abs(it.start - nowSec) } // fallback
    }

    private fun handleEventUpsert(msg: HtspMessage) {
        val eventId = msg.int("eventId") ?: msg.int("id") ?: return
        val channelId = msg.int("channelId") ?: msg.int("channel") ?: return

        val title = msg.str("title") ?: msg.str("eventTitle") ?: msg.str("name") ?: "—"
        val summary = msg.str("summary") ?: msg.str("description")

        val start = msg.long("start") ?: msg.long("startTime") ?: return
        val stop = msg.long("stop") ?: msg.long("stopTime") ?: return

        val flow = epgByChannel.getOrPut(channelId) { MutableStateFlow(emptyList()) }

        val updated = upsertAndTrim(
            list = flow.value,
            item = EpgEventEntry(eventId, channelId, start, stop, title, summary),
            nowSec = System.currentTimeMillis() / 1000L,
            keepPastSec = 2 * 3600,
            keepFutureSec = 12 * 3600
        )

        flow.value = updated
    }

    private fun handleEventDelete(msg: HtspMessage) {
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

    suspend fun loadEpgForChannel(
        channelId: Int,
        nowSec: Long = System.currentTimeMillis() / 1000L,
        fromSec: Long = nowSec - 2 * 3600,
        toSec: Long = nowSec + 12 * 3600
    ) {
        val fieldsWithChannel = mapOf(
            "channelId" to channelId,
            "start" to fromSec,
            "stop" to toSec
        )
        val fieldsNoChannel = mapOf(
            "start" to fromSec,
            "stop" to toSec
        )

        val reply = runCatching { htsp.request("getEvents", fieldsWithChannel) }
            .recoverCatching { htsp.request("getEpg", fieldsWithChannel) }
            .recoverCatching { htsp.request("getEvents", fieldsNoChannel) }
            .recoverCatching { htsp.request("getEpg", fieldsNoChannel) }
            .getOrElse { t ->
                setStatus("EPG load failed: ${t.message ?: t}")
                return
            }

        ingestGetEventsReply(reply)
    }

    fun ingestGetEventsReply(reply: HtspMessage) {
        val raw = reply.fields["events"]
            ?: reply.fields["epg"]
            ?: reply.fields["entries"]
            ?: run {
                setStatus("EPG reply: no events field (keys=${reply.fields.keys})")
                return
            }

        @Suppress("UNCHECKED_CAST")
        val list = raw as? List<Map<String, Any?>> ?: run {
            setStatus("EPG reply: unexpected type ${raw::class.java}")
            return
        }

        var count = 0
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
            count++
        }

        setStatus("EPG loaded: $count")
    }
}
