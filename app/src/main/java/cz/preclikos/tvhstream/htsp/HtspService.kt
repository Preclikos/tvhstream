package cz.preclikos.tvhstream.htsp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.text.Charsets.UTF_8

class HtspService(
    ioDispatcher: CoroutineDispatcher
) {
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

    private val pending = ConcurrentHashMap<Int, CompletableDeferred<HtspMessage>>()
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

    suspend fun connect(
        host: String,
        port: Int,
        username: String? = null,
        password: String? = null,
        clientName: String = "TVHStream",
        clientVersion: String = "0.1",
        htspVersion: Int = 43,
        timeoutMs: Long = 100_000,
        soTimeoutMs: Int = 15_000,
        socketBufferBytes: Int = 64 * 1024
    ) {
        connectMutex.withLock {
            if (isConnectedUnsafe()) return

            disconnectInternal(CancellationException("Reconnect"))

            val s = Socket(host, port).apply {
                soTimeout = soTimeoutMs
                keepAlive = true
                tcpNoDelay = true
            }

            val inp = BufferedInputStream(s.getInputStream(), socketBufferBytes)
            val out = BufferedOutputStream(s.getOutputStream(), socketBufferBytes)

            socket = s
            input = inp
            output = out

            readerJob = scope.launch { readerLoop() }

            try {
                val hello = withTimeout(timeoutMs) {
                    request(
                        method = "hello",
                        fields = mapOf(
                            "htspversion" to htspVersion,
                            "clientname" to clientName,
                            "clientversion" to clientVersion
                        ),
                        timeoutMs = timeoutMs,
                        flush = true
                    )
                }

                challenge = hello.bin("challenge")
                val serverMax = hello.int("htspversion") ?: htspVersion
                negotiatedHtspVersion = min(htspVersion, serverMax)
                val user = username?.trim().orEmpty()
                val pass = password?.trim().orEmpty()

                if (user.isNotEmpty() && pass.isNotEmpty() && challenge != null) {
                    val digest = makeDigest(pass, challenge!!)
                    val auth = withTimeout(timeoutMs) {
                        request(
                            method = "authenticate",
                            fields = mapOf("username" to user, "digest" to digest),
                            timeoutMs = timeoutMs,
                            flush = true
                        )
                    }
                    if (auth.int("noaccess") == 1) {
                        throw IllegalStateException("HTSP authentication failed (noaccess=1)")
                    }
                }

            } catch (t: Throwable) {
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
                flush = true
            )
            withTimeout(timeoutMs) { def.await() }
        } finally {
            if (initialSyncDef === def) initialSyncDef = null
        }
    }

    suspend fun request(
        method: String,
        fields: Map<String, Any?> = emptyMap(),
        timeoutMs: Long = 10_000,
        flush: Boolean = true
    ): HtspMessage {
        val s = seq.getAndIncrement()
        val def = CompletableDeferred<HtspMessage>()
        pending[s] = def

        val out = output ?: run {
            pending.remove(s)
            throw IllegalStateException("Not connected")
        }

        try {
            val msgFields = HashMap<String, Any?>(fields.size + 1)
            msgFields.putAll(fields)
            msgFields["seq"] = s

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
            throw t
        }
    }

    fun disconnect() {
        scope.launch {
            connectMutex.withLock {
                disconnectInternal(CancellationException("Disconnected"))
            }
        }
    }

    private suspend fun readerLoop() {
        val inp = input ?: return
        try {
            while (currentCoroutineContext().isActive) {
                val msg = try {
                    HtspCodec.readMessage(inp)
                } catch (t: SocketTimeoutException) {
                    yield()
                    continue
                }

                // Special-cased latch
                if (msg.seq == null && msg.method == "initialSyncCompleted") {
                    initialSyncDef?.complete(Unit)
                }

                // 1) Replies to pending requests: complete and DO NOT broadcast
                val seqNo = msg.seq
                if (seqNo != null) {
                    val def = pending.remove(seqNo)
                    if (def != null) {
                        def.complete(msg)
                        continue
                    }
                    // else fall-through: unsolicited message with seq -> publish as control
                }

                // 2) Stream plane vs control plane
                if (msg.method == "muxpkt") {
                    _muxEvents.tryEmit(msg) // only stream consumers get it
                } else {
                    _controlEvents.tryEmit(HtspEvent.ServerMessage(msg))
                }
            }
        } catch (t: NoSuchElementException) {
            failAll(EOFException("Broken/EOF HTSP stream").apply { initCause(t) })
            return
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
        defs.forEach { it.completeExceptionally(t) }

        initialSyncDef?.completeExceptionally(t)
        initialSyncDef = null

        readerJob?.cancel()
        readerJob = null

        try {
            input?.close()
        } catch (_: Throwable) {
        }
        try {
            output?.close()
        } catch (_: Throwable) {
        }
        try {
            socket?.close()
        } catch (_: Throwable) {
        }

        input = null
        output = null
        socket = null
        challenge = null
        negotiatedHtspVersion = null
    }

    private fun failAll(t: Throwable) {
        _controlEvents.tryEmit(HtspEvent.ConnectionError(t))

        val defs = pending.values.toList()
        pending.clear()
        defs.forEach { it.completeExceptionally(t) }

        initialSyncDef?.completeExceptionally(t)
        initialSyncDef = null

        try {
            input?.close()
        } catch (_: Throwable) {
        }
        try {
            output?.close()
        } catch (_: Throwable) {
        }
        try {
            socket?.close()
        } catch (_: Throwable) {
        }

        input = null
        output = null
        socket = null
        challenge = null
        negotiatedHtspVersion = null

        readerJob?.cancel()
        readerJob = null
    }
}
