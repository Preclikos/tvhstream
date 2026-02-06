package cz.preclikos.tvhstream.player.htsp

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import cz.preclikos.tvhstream.htsp.HtspEvent
import cz.preclikos.tvhstream.htsp.HtspMessage
import cz.preclikos.tvhstream.htsp.HtspService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.min

@OptIn(UnstableApi::class)
class HtspSubscriptionDataSource private constructor(
    private val context: Context,
    private val htspConnection: HtspService,
    private val streamProfile: String?
) : DataSource, Closeable, HtspDataSourceInterface {

    private var dataSpec: DataSpec? = null
    private val dataSourceNumber: Int
    private val subscriptionId: Int

    private var timeshiftPeriod = 0
    private var subscriptionStarted = false
    private var isSubscribed = false

    private val jobScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var eventJob: Job? = null


    // ---------- High-performance buffering ----------
    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val notFull = lock.newCondition()

    /**
     * Ring buffer: zero compact/flip.
     */
    private val ring = RingBuffer(BUFFER_SIZE)

    class Factory internal constructor(
        private val context: Context,
        private val htspConnection: HtspService,
        private val streamProfile: String?
    ) : DataSource.Factory {
        private var dataSource: HtspSubscriptionDataSource? = null

        override fun createDataSource(): DataSource {
            Timber.d("Created new data source from factory")
            dataSource = HtspSubscriptionDataSource(context, htspConnection, streamProfile)
            return dataSource!!
        }

        val currentDataSource: HtspDataSourceInterface?
            get() = dataSource

        fun releaseCurrentDataSource() {
            Timber.d("Releasing data source")
            dataSource?.release()
        }
    }

    init {
        Timber.d("Initializing subscription data source")
        dataSourceNumber = dataSourceCount.incrementAndGet()
        subscriptionId = subscriptionCount.incrementAndGet()

        Timber.d("New subscription data source instantiated (%d)", dataSourceNumber)

        // Push HEADER once into the stream.
        lock.lock()
        try {
            ring.write(HtspFramedCodec.HEADER, 0, HtspFramedCodec.HEADER.size) { needed ->
                // There should always be space at init; if not, clear.
                Timber.e("Ring buffer unexpectedly full at init; clearing (%d)", dataSourceNumber)
                ring.clear()
                needed <= ring.free()
            }
            notEmpty.signalAll()
        } finally {
            lock.unlock()
        }
    }

    @Throws(Throwable::class)
    protected fun finalize() {
        Timber.d("Finalizing subscription data source")
        release()
    }

    override fun addTransferListener(transferListener: TransferListener) {
        // no-op
    }

    override fun open(dataSpec: DataSpec): Long {
        startPumpIfNeeded()
        Timber.d("Opening subscription data source (%d)", dataSourceNumber)
        this.dataSpec = dataSpec

        if (!isSubscribed) {
            val path = dataSpec.uri.path
            Timber.d("We are not yet subscribed to path %s", path)

            if (!path.isNullOrBlank() && path.length > 1) {
                val channelId = path.substring(1).toInt()

                Timber.d(
                    "Sending subscription start (subscriptionId=%s channelId=%s)",
                    subscriptionId,
                    channelId
                )

                runBlocking {
                    val response = htspConnection.request(
                        "subscribe",
                        mapOf(
                            "subscriptionId" to subscriptionId,
                            "channelId" to channelId,
                            "timeshiftPeriod" to timeshiftPeriod,
                            // "profile" to streamProfile  // pokud HTSP server podporuje
                        )
                    )

                    val availableTimeshiftPeriod = response.int("timeshiftPeriod")
                    if (availableTimeshiftPeriod != null) {
                        Timber.d(
                            "Available timeshift period in seconds: %s",
                            availableTimeshiftPeriod
                        )
                    }
                }

                isSubscribed = true
            }
        }

        val seekPosition = this.dataSpec!!.position
        if (seekPosition > 0 && timeshiftPeriod > 0) {
            Timber.d(
                "Sending subscription skip (subscriptionId=%s time=%d)",
                subscriptionId,
                seekPosition
            )

            runBlocking {
                htspConnection.request(
                    "subscriptionSkip",
                    mapOf(
                        "subscriptionId" to subscriptionId,
                        "time" to seekPosition,
                        "absolute" to 1
                    )
                )
            }

            // Clear buffered stream data on seek.
            lock.lock()
            try {
                ring.clear()
                // keep header so consumer stays sane:
                ring.write(HtspFramedCodec.HEADER, 0, HtspFramedCodec.HEADER.size) { true }
                notEmpty.signalAll()
                notFull.signalAll()
            } finally {
                lock.unlock()
            }
        }

        subscriptionStarted = true
        return C.LENGTH_UNSET.toLong()
    }

    private fun startPumpIfNeeded() {
        if (eventJob != null) return

        eventJob = jobScope.launch {
            launch {
                htspConnection.controlEvents.collect { ev ->
                    val msg = (ev as? HtspEvent.ServerMessage)?.msg ?: return@collect
                    val msgSubId = msg.int("subscriptionId")
                    if (msgSubId != null && msgSubId != subscriptionId) return@collect

                    when (msg.method) {
                        "subscriptionStart" -> {
                            subscriptionStarted = true
                            writeFramedMessage(msg)
                        }

                        "subscriptionStop" -> {
                            subscriptionStarted = false
                            lock.lock()
                            try {
                                notEmpty.signalAll()
                                notFull.signalAll()
                            } finally {
                                lock.unlock()
                            }
                        }
                    }
                }
            }

            launch {
                htspConnection.muxEvents.collect { msg ->
                    val msgSubId = msg.int("subscriptionId")
                    if (msgSubId != null && msgSubId != subscriptionId) return@collect
                    writeFramedMessage(msg)
                }
            }
        }
    }

    override fun getUri(): Uri? = dataSpec?.uri

    /**
     * DataSource.read() – blokuje bez polling/sleep.
     */
    override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
        if (readLength == 0) return 0

        lock.lock()
        try {
            while (subscriptionStarted && ring.size() == 0) {
                try {
                    notEmpty.await()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    Timber.w("Interrupted while waiting for data (%d)", dataSourceNumber)
                    return 0
                }
            }

            if (!subscriptionStarted && ring.size() == 0) {
                Timber.d("End of input buffer (%d)", dataSourceNumber)
                return C.RESULT_END_OF_INPUT
            }

            val toRead = min(readLength, ring.size())
            val actuallyRead = ring.read(buffer, offset, toRead)
            if (actuallyRead > 0) {
                notFull.signalAll()
            }
            return actuallyRead
        } finally {
            lock.unlock()
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun getResponseHeaders(): Map<String, List<String>> {
        return mutableMapOf<String?, MutableList<String?>?>() as Map<String, List<String>>
    }

    override fun close() {
        Timber.d("Closing subscription data source (%d)", dataSourceNumber)
        subscriptionStarted = false
        eventJob?.cancel()
        eventJob = null

        lock.lock()
        try {
            notEmpty.signalAll()
            notFull.signalAll()
        } finally {
            lock.unlock()
        }
    }

    private fun release() {
        Timber.d("Releasing subscription data source (%d)", dataSourceNumber)
        subscriptionStarted = false

        eventJob?.cancel()
        eventJob = null

        lock.lock()
        try {
            notEmpty.signalAll()
            notFull.signalAll()
        } finally {
            lock.unlock()
        }

        if (isSubscribed) {
            runBlocking {
                try {
                    htspConnection.request(
                        "unsubscribe",
                        mapOf("subscriptionId" to subscriptionId)
                    )
                } catch (t: Throwable) {
                    Timber.w(t, "unsubscribe failed (%d)", dataSourceNumber)
                }
            }
        }

        isSubscribed = false
    }

    override fun pause() {
        Timber.d("Pausing subscription data source (%d)", dataSourceNumber)
        runBlocking {
            htspConnection.request(
                "subscriptionSpeed",
                mapOf(
                    "subscriptionId" to subscriptionId,
                    "speed" to 0
                )
            )
        }
    }

    override val timeshiftOffsetPts: Long
        get() = 0

    override fun setSpeed(tvhSpeed: Int) {
        runBlocking {
            htspConnection.request(
                "subscriptionSpeed",
                mapOf(
                    "subscriptionId" to subscriptionId,
                    "speed" to tvhSpeed
                )
            )
        }
    }

    override val timeshiftStartTime: Long
        get() = 0

    override val timeshiftStartPts: Long
        get() = 0

    override fun resume() {
        Timber.d("Resuming subscription data source (%d)", dataSourceNumber)
        runBlocking {
            htspConnection.request(
                "subscriptionSpeed",
                mapOf("subscriptionId" to subscriptionId, "speed" to 100)
            )
        }
    }

    /**
     * Writes a framed message into ring buffer:
     * [int32 payloadLen][payload bytes]
     */
    private fun writeFramedMessage(message: HtspMessage) {
        val frame = try {
            HtspFramedCodec.frameMessage(message)
        } catch (t: Throwable) {
            Timber.e(t, "Failed to encode message (%d)", dataSourceNumber)
            return
        }

        lock.lock()
        try {
            ring.write(frame, 0, frame.size) { needed ->
                while (subscriptionStarted && needed > ring.free()) {
                    try {
                        notFull.await()
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@write false
                    }
                }
                needed <= ring.free()
            }
            notEmpty.signalAll()
        } finally {
            lock.unlock()
        }
    }

    private class RingBuffer(capacity: Int) {
        private val buf = ByteArray(capacity)
        private var head = 0 // read
        private var tail = 0 // write
        private var size = 0

        fun size(): Int = size
        fun free(): Int = buf.size - size

        fun clear() {
            head = 0
            tail = 0
            size = 0
        }

        fun write(src: ByteArray, off: Int, len: Int, spacePolicy: (needed: Int) -> Boolean) {
            if (len <= 0) return
            if (!spacePolicy(len)) return

            var remaining = len
            var srcPos = off
            while (remaining > 0) {
                val chunk = min(remaining, buf.size - tail)
                System.arraycopy(src, srcPos, buf, tail, chunk)
                tail = (tail + chunk) % buf.size
                size += chunk
                srcPos += chunk
                remaining -= chunk
            }
        }

        fun read(dst: ByteArray, off: Int, len: Int): Int {
            if (len <= 0 || size == 0) return 0
            val toRead = min(len, size)

            var remaining = toRead
            var dstPos = off
            while (remaining > 0) {
                val chunk = min(remaining, buf.size - head)
                System.arraycopy(buf, head, dst, dstPos, chunk)
                head = (head + chunk) % buf.size
                size -= chunk
                dstPos += chunk
                remaining -= chunk
            }
            return toRead
        }
    }

    companion object {
        private val dataSourceCount = AtomicInteger()
        private val subscriptionCount = AtomicInteger()

        private const val BUFFER_SIZE = 10 * 1024 * 1024
    }
}
