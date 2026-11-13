package cz.preclikos.tvhstream.htsp

import cz.preclikos.tvhstream.core.HtspTls
import cz.preclikos.tvhstream.BuildConfig
import cz.preclikos.tvhstream.core.ConnectionPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import cz.preclikos.tvhstream.core.drainEach
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.text.Charsets.UTF_8

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data class Connecting(val host: String, val port: Int) : ConnectionState()
    data class Connected(val host: String, val port: Int, val htspVersion: Int?) : ConnectionState()
    data class Error(val throwable: Throwable) : ConnectionState()
}

class HtspService(
    ioDispatcher: CoroutineDispatcher
) {
    companion object {
        /** See [muxEvents]; guarded by a unit test so it isn't raised back (OOM). */
        internal const val MUX_EVENT_BUFFER_CAPACITY = 512
    }

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val controlEventStream = HtspEventStream()
    val controlEvents: SharedFlow<HtspEvent> = controlEventStream.events

    // Only a hand-off to the data source's 10 MB ring buffer, which is the real
    // jitter buffer. Each muxpkt holds a whole video frame (tens of KB), so 8192 of
    // them could reach hundreds of MB and OOM'd 128 MB-heap TVs while the player
    // wasn't draining. 512 is plenty to absorb bursts from the reader loop.
    private val _muxEvents = MutableSharedFlow<HtspMessage>(
        extraBufferCapacity = MUX_EVENT_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val muxEvents: SharedFlow<HtspMessage> = _muxEvents

    private val pending = ConcurrentHashMap<Int, PendingReq>()

    private data class PendingReq(
        val def: CompletableDeferred<HtspMessage>,
        val startedAtMs: Long
    )

    private val seq = AtomicInteger(1)

    private val writeMutex = Mutex()
    private val connectMutex = Mutex()

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var input: InputStream? = null

    @Volatile
    private var output: OutputStream? = null

    @Volatile
    private var readerJob: Job? = null

    @Volatile
    private var challenge: ByteArray? = null

    @Volatile
    private var negotiatedHtspVersion: Int? = null

    @Volatile
    private var initialSyncDef: CompletableDeferred<Unit>? = null

    // ---- health ----
    @Volatile
    private var lastReadAtMs: Long = 0L

    suspend fun connect(
        host: String,
        port: Int,
        username: String? = null,
        password: String? = null,
        clientName: String = "TVHStream / " + BuildConfig.VERSION_NAME,
        clientVersion: String = BuildConfig.VERSION_NAME,
        htspVersion: Int = 43,

        connectTimeoutMs: Int = 10_000,
        responseTimeoutMs: Long = 5_000,

        soTimeoutMs: Int = 60_000,

        socketBufferBytes: Int = 64 * 1024,

        forceReconnect: Boolean = false,

        /** Use the encrypted HTSP port (TLS 1.2+). */
        tls: Boolean = false,
        /** Pinned SHA-256 fingerprint for a self-signed certificate; null = CA + host name. */
        tlsFingerprint: String? = null,
    ) {
        connectMutex.withLock {
            if (!forceReconnect && isConnectedUnsafe()) return

            _state.value = ConnectionState.Connecting(host, port)

            disconnectInternal(CancellationException("Reconnect"))

            val plain = Socket()
            try {
                plain.tcpNoDelay = true
                plain.keepAlive = true
                plain.soTimeout = connectTimeoutMs
                plain.connect(InetSocketAddress(host, port), connectTimeoutMs)

                // Same HTSP protocol, inside a TLS tunnel when enabled.
                val s: Socket = if (tls) {
                    try {
                        HtspTls.wrap(plain, host, port, tlsFingerprint)
                    } catch (t: Throwable) {
                        runCatching { plain.close() }
                        throw t
                    }
                } else plain
                s.soTimeout = soTimeoutMs

                val inp = BufferedInputStream(s.getInputStream(), socketBufferBytes)
                val out = BufferedOutputStream(s.getOutputStream(), socketBufferBytes)

                socket = s
                input = inp
                output = out
                lastReadAtMs = System.currentTimeMillis()

                if (readerJob != null) {
                    throw IllegalStateException("Reader job already running")
                }
                readerJob = scope.launch {
                    readerLoop(
                        responseTimeoutMs = responseTimeoutMs
                    )
                }

                val hello = request(
                    method = "hello",
                    fields = mapOf(
                        "htspversion" to htspVersion,
                        "clientname" to clientName,
                        "clientversion" to clientVersion
                    ),
                    timeoutMs = responseTimeoutMs,
                    flush = true,
                    // We hold connectMutex here and failAll() needs it too (Mutex is not
                    // reentrant -> permanent deadlock). The catch below cleans up instead.
                    disconnectOnTimeout = false
                )

                challenge = hello.bin("challenge")
                val serverMax = hello.int("htspversion") ?: htspVersion
                negotiatedHtspVersion = min(htspVersion, serverMax)

                val user = username?.trim().orEmpty()
                val pass = password?.trim().orEmpty()

                if (ConnectionPolicy.shouldAuthenticate(username, password) && challenge != null) {
                    val digest = makeDigest(pass, challenge!!)
                    val auth = request(
                        method = "authenticate",
                        fields = mapOf("username" to user, "digest" to digest),
                        timeoutMs = responseTimeoutMs,
                        flush = true,
                        disconnectOnTimeout = false
                    )
                    if (auth.int("noaccess") == 1) {
                        throw IllegalStateException("HTSP authentication failed (noaccess=1)")
                    }
                }

                _state.value = ConnectionState.Connected(host, port, negotiatedHtspVersion)

            } catch (t: Throwable) {
                _state.value = ConnectionState.Error(t)
                disconnectInternal(t)
                throw t
            }
        }
    }

    suspend fun enableAsyncMetadataAndWaitInitialSync(timeoutMs: Long = 30_000) {
        if (!isConnectedUnsafe()) throw IllegalStateException("Not connected")

        val def = CompletableDeferred<Unit>()
        initialSyncDef = def

        try {
            request(
                method = "enableAsyncMetadata",
                fields = emptyMap(),
                timeoutMs = timeoutMs,
                flush = true,
                disconnectOnTimeout = true
            )
            withTimeout(timeoutMs) { def.await() }
        } finally {
            if (initialSyncDef === def) initialSyncDef = null
        }
    }

    suspend fun request(
        method: String,
        fields: Map<String, Any?> = emptyMap(),
        timeoutMs: Long = 5_000,
        flush: Boolean = true,
        disconnectOnTimeout: Boolean = true
    ): HtspMessage {
        val s = seq.getAndIncrement()
        val def = CompletableDeferred<HtspMessage>()
        pending[s] = PendingReq(def, System.currentTimeMillis())

        val out = output ?: run {
            pending.remove(s)
            throw IllegalStateException("Not connected")
        }

        try {
            val msgFields = HashMap<String, Any?>(fields.size + 1).apply {
                putAll(fields)
                this["seq"] = s
            }

            writeMutex.withLock {
                HtspCodec.writeMessage(out, method, msgFields)
                if (flush) out.flush()
            }
        } catch (t: Throwable) {
            pending.remove(s)
            def.completeExceptionally(t)
            throw t
        }

        return try {
            withTimeout(timeoutMs) { def.await() }
        } catch (t: Throwable) {
            pending.remove(s)

            if (disconnectOnTimeout) {
                failAll(SocketTimeoutException("HTSP request '$method' timed out after ${timeoutMs}ms").apply {
                    initCause(
                        t
                    )
                })
            }

            throw t
        }
    }

    suspend fun fileOpen(path: String, timeoutMs: Long = 5_000): Int {
        val p = if (path.startsWith("/")) path else "/$path"
        val msg = request(
            method = "fileOpen",
            fields = mapOf("file" to p),
            timeoutMs = timeoutMs,
            flush = true,
            disconnectOnTimeout = false
        )
        return msg.int("id") ?: error("fileOpen: missing id")
    }

    suspend fun fileRead(id: Int, size: Int, timeoutMs: Long = 5_000): ByteArray {
        val msg = request(
            method = "fileRead",
            fields = mapOf("id" to id, "size" to size),
            timeoutMs = timeoutMs,
            flush = true,
            disconnectOnTimeout = false
        )
        return msg.bin("data") ?: ByteArray(0) // EOF => empty
    }

    suspend fun fileClose(id: Int, timeoutMs: Long = 5_000) {
        request(
            method = "fileClose",
            fields = mapOf("id" to id),
            timeoutMs = timeoutMs,
            flush = true,
            disconnectOnTimeout = false
        )
    }

    suspend fun disconnect() {
        connectMutex.withLock {
            disconnectInternal(CancellationException("Disconnected"))
        }
    }

    private suspend fun readerLoop(responseTimeoutMs: Long) {
        val inp = input ?: return

        val pendingMaxSilentMs = responseTimeoutMs * 2

        try {
            while (currentCoroutineContext().isActive) {
                try {
                    val msg = HtspCodec.readMessage(inp)
                    lastReadAtMs = System.currentTimeMillis()

                    // Special-cased latch
                    if (msg.seq == null && msg.method == "initialSyncCompleted") {
                        initialSyncDef?.complete(Unit)
                    }

                    val seqNo = msg.seq
                    if (seqNo != null) {
                        val pr = pending.remove(seqNo)
                        if (pr != null) {
                            pr.def.complete(msg)
                            continue
                        }
                    }

                    if (msg.method == "muxpkt") {
                        _muxEvents.tryEmit(msg)
                    } else {
                        controlEventStream.emit(HtspEvent.ServerMessage(msg))
                    }
                } catch (t: SocketTimeoutException) {
                    val now = System.currentTimeMillis()
                    if (pending.isNotEmpty()) {
                        val silent = now - lastReadAtMs
                        if (silent >= pendingMaxSilentMs) {
                            failAll(SocketTimeoutException("HTSP no incoming data for ${silent}ms with ${pending.size} pending requests"))
                            return
                        }
                    }
                    continue
                }
            }
        } catch (t: NoSuchElementException) {
            if (!currentCoroutineContext().isActive) return
            failAll(EOFException("Broken/EOF HTSP stream").apply { initCause(t) })
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            // Socket closed by disconnect/failAll while cancelling us: just stop.
            if (!currentCoroutineContext().isActive) return
            failAll(t)
        }
    }

    private fun makeDigest(password: String, challenge: ByteArray): ByteArray {
        val p = password.toByteArray(UTF_8)
        val all = ByteArray(p.size + challenge.size)
        System.arraycopy(p, 0, all, 0, p.size)
        System.arraycopy(challenge, 0, all, p.size, challenge.size)
        return MessageDigest.getInstance("SHA-1").digest(all)
    }

    private fun isConnectedUnsafe(): Boolean {
        val sj = readerJob
        val s = socket
        return sj?.isActive == true &&
                output != null &&
                s?.isConnected == true && !s.isClosed
    }

    /** Fails every in-flight request (race-safe, see [drainEach]). */
    private fun failPending(t: Throwable) {
        pending.drainEach { it.def.completeExceptionally(t) }
    }

    private fun closeStreams() {
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
        try {
            input?.close()
        } catch (_: Throwable) {
        }
        try {
            output?.close()
        } catch (_: Throwable) {
        }
    }

    private suspend fun disconnectInternal(t: Throwable) {
        failPending(t)

        initialSyncDef?.completeExceptionally(t)
        initialSyncDef = null

        val job = readerJob
        readerJob = null
        job?.cancel()
        // Close before joining: the reader is blocked in a socket read that coroutine
        // cancellation can't interrupt, so joining first waited for soTimeout (60 s).
        closeStreams()
        val self = currentCoroutineContext()[Job]
        if (job != null && job !== self) {
            job.join()
        }


        input = null
        output = null
        socket = null
        challenge = null
        negotiatedHtspVersion = null

        _state.value = ConnectionState.Disconnected
    }

    private suspend fun failAll(t: Throwable) {
        // Fail waiting requests right away, without the lock: if connect() is in progress
        // (holding connectMutex) its hello/authenticate fails now instead of timing out.
        failPending(t)
        connectMutex.withLock {
            _state.value = ConnectionState.Error(t)
            controlEventStream.emit(HtspEvent.ConnectionError(t))

            failPending(t)

            initialSyncDef?.completeExceptionally(t)
            initialSyncDef = null

            val job = readerJob
            readerJob = null
            job?.cancel()
            closeStreams()

            val self = currentCoroutineContext()[Job]
            if (job != null && job !== self) {
                try {
                    job.join()
                } catch (_: Throwable) {
                }
            }


            input = null
            output = null
            socket = null
            challenge = null
            negotiatedHtspVersion = null

            _state.value = ConnectionState.Disconnected
        }
    }
}
