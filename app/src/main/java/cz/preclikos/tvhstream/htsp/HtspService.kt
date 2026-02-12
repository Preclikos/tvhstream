package cz.preclikos.tvhstream.htsp

import cz.preclikos.tvhstream.BuildConfig
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
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val _controlEvents = MutableSharedFlow<HtspEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val controlEvents: SharedFlow<HtspEvent> = _controlEvents

    private val _muxEvents = MutableSharedFlow<HtspMessage>(
        extraBufferCapacity = 8192,
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

        forceReconnect: Boolean = false
    ) {
        connectMutex.withLock {
            if (!forceReconnect && isConnectedUnsafe()) return

            _state.value = ConnectionState.Connecting(host, port)

            disconnectInternal(CancellationException("Reconnect"))

            val s = Socket()
            try {
                s.tcpNoDelay = true
                s.keepAlive = true
                s.soTimeout = soTimeoutMs
                s.connect(InetSocketAddress(host, port), connectTimeoutMs)

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
                    disconnectOnTimeout = true
                )

                challenge = hello.bin("challenge")
                val serverMax = hello.int("htspversion") ?: htspVersion
                negotiatedHtspVersion = min(htspVersion, serverMax)

                val user = username?.trim().orEmpty()
                val pass = password?.trim().orEmpty()

                if (user.isNotEmpty() && pass.isNotEmpty() && challenge != null) {
                    val digest = makeDigest(pass, challenge!!)
                    val auth = request(
                        method = "authenticate",
                        fields = mapOf("username" to user, "digest" to digest),
                        timeoutMs = responseTimeoutMs,
                        flush = true,
                        disconnectOnTimeout = true
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
                        _controlEvents.tryEmit(HtspEvent.ServerMessage(msg))
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
            failAll(EOFException("Broken/EOF HTSP stream").apply { initCause(t) })
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
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

    private suspend fun disconnectInternal(t: Throwable) {
        val defs = pending.values.toList()
        pending.clear()
        defs.forEach { it.def.completeExceptionally(t) }

        initialSyncDef?.completeExceptionally(t)
        initialSyncDef = null

        val job = readerJob
        readerJob = null
        job?.cancel()
        val self = currentCoroutineContext()[Job]
        if (job != null && job !== self) {
            job.join()
        }

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

        input = null
        output = null
        socket = null
        challenge = null
        negotiatedHtspVersion = null

        _state.value = ConnectionState.Disconnected
    }

    private suspend fun failAll(t: Throwable) {
        connectMutex.withLock {
            _state.value = ConnectionState.Error(t)
            _controlEvents.tryEmit(HtspEvent.ConnectionError(t))

            val defs = pending.values.toList()
            pending.clear()
            defs.forEach { it.def.completeExceptionally(t) }

            initialSyncDef?.completeExceptionally(t)
            initialSyncDef = null

            val job = readerJob
            readerJob = null
            job?.cancel()

            val self = currentCoroutineContext()[Job]
            if (job != null && job !== self) {
                try {
                    job.join()
                } catch (_: Throwable) {
                }
            }

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

            input = null
            output = null
            socket = null
            challenge = null
            negotiatedHtspVersion = null

            _state.value = ConnectionState.Disconnected
        }
    }
}
