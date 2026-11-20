package cz.preclikos.tvhstream.htsp

import cz.preclikos.tvhstream.core.HtspTls
import cz.preclikos.tvhstream.core.StartTlsNotOfferedException
import cz.preclikos.tvhstream.core.StartTlsRefusedException
import cz.preclikos.tvhstream.core.TlsRequiredException
import cz.preclikos.tvhstream.core.UntrustedCertificateException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Collections
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * End-to-end: HtspService against a minimal fake Tvheadend that implements STARTTLS
 * like the real server: hello lists "starttls" in servercapability, the startTLS reply
 * goes in plain text, then the TLS handshake runs on the same connection. Uses a
 * self-signed certificate like the one Tvheadend generates.
 */
class HtspServiceTlsTest {

    private val password = "changeit".toCharArray()
    private var server: ServerSocket? = null
    private val htsp = HtspService(ioDispatcher = Dispatchers.IO)

    /** Methods the fake server received, with "+tls" when they arrived inside TLS. */
    private val received: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @After
    fun tearDown() {
        runBlocking { runCatching { htsp.disconnect() } }
        runCatching { server?.close() }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("PKCS12").apply {
        HtspServiceTlsTest::class.java.classLoader!!
            .getResourceAsStream("tls/self-signed.p12")!!.use { load(it, password) }
    }

    private fun selfSignedFingerprint(): String =
        HtspTls.fingerprint(keyStore().getCertificate("server") as X509Certificate)

    /**
     * @param offerStartTls list "starttls" in the hello capabilities (encryption enabled)
     * @param refuseStartTls answer startTLS with an error
     * @param requireTls Tvheadend's "Require encryption": refuse requests before TLS
     */
    private fun startFakeTvheadend(
        offerStartTls: Boolean = true,
        refuseStartTls: Boolean = false,
        requireTls: Boolean = false,
    ): Int {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore(), password)
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        val s = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        server = s
        thread(isDaemon = true) {
            runCatching {
                while (true) {
                    val c = s.accept()
                    thread(isDaemon = true) { runCatching { serve(c, ctx, offerStartTls, refuseStartTls, requireTls) } }
                }
            }
        }
        return s.localPort
    }

    private fun serve(c: Socket, ctx: SSLContext, offer: Boolean, refuse: Boolean, require: Boolean) {
        var sock: Socket = c
        var inp = BufferedInputStream(sock.getInputStream())
        var out = sock.getOutputStream()
        var tls = false
        try {
            while (true) {
                val req = HtspCodec.readMessage(inp)
                received += req.method + if (tls) "+tls" else ""
                fun reply(fields: Map<String, Any?>) {
                    HtspCodec.writeMessage(out, "reply", fields + ("seq" to req.seq))
                    out.flush()
                }
                when {
                    req.method == "hello" -> reply(
                        mapOf(
                            "htspversion" to 43,
                            "servername" to "Fake Tvheadend",
                            "challenge" to ByteArray(32) { it.toByte() },
                            "servercapability" to buildList {
                                add("timeshift")
                                if (offer && !tls) add("starttls")
                            },
                        )
                    )

                    req.method == "startTLS" && refuse ->
                        reply(mapOf("error" to "TLS is not available (no usable certificate)"))

                    req.method == "startTLS" -> {
                        reply(emptyMap())
                        val ssl = ctx.socketFactory.createSocket(sock, null, sock.port, true) as SSLSocket
                        ssl.useClientMode = false
                        ssl.startHandshake()
                        sock = ssl
                        inp = BufferedInputStream(ssl.inputStream)
                        out = ssl.outputStream
                        tls = true
                    }

                    require && !tls ->
                        reply(mapOf("error" to "Encryption required, use startTLS", "noaccess" to 1, "tlsrequired" to 1))

                    else -> reply(emptyMap())
                }
            }
        } finally {
            runCatching { sock.close() }
        }
    }

    @Test
    fun startTls_withPinnedCertificate_authenticatesInsideTls() = runBlocking {
        val port = startFakeTvheadend()

        htsp.connect(
            host = "127.0.0.1",
            port = port,
            username = "user",
            password = "secret",
            tls = true,
            tlsFingerprint = selfSignedFingerprint(),
            connectTimeoutMs = 5_000,
            responseTimeoutMs = 5_000,
        )

        val state = htsp.state.value
        assertTrue("state=$state", state is ConnectionState.Connected)
        assertEquals(43, (state as ConnectionState.Connected).htspVersion)
        // hello and startTLS in plain text, credentials only inside TLS, no second hello
        assertEquals(listOf("hello", "startTLS", "authenticate+tls"), received.toList())
    }

    /**
     * Regression: disconnect() used to join the reader before closing the socket, so it
     * waited for the blocking read's soTimeout (60 s) on an idle connection.
     */
    @Test
    fun disconnect_onIdleConnection_isImmediate() = runBlocking {
        val port = startFakeTvheadend()
        htsp.connect(
            host = "127.0.0.1",
            port = port,
            tls = true,
            tlsFingerprint = selfSignedFingerprint(),
            connectTimeoutMs = 5_000,
        )

        val tookMs = kotlin.system.measureTimeMillis {
            withTimeout(3_000) { htsp.disconnect() }
        }

        assertTrue("disconnect took ${tookMs}ms", tookMs < 3_000)
        assertEquals(ConnectionState.Disconnected, htsp.state.value)
    }

    /**
     * Regression: a failed hello during connect() deadlocked on connectMutex (connect
     * holds it, failAll() wanted it again), leaving the service stuck "Connecting" forever.
     */
    @Test
    fun connect_toServerThatNeverAnswers_failsWithinTimeout() = runBlocking {
        for (tls in listOf(false, true)) {
            val silent = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
            try {
                thread(isDaemon = true) { runCatching { silent.accept().getInputStream().read() } }
                try {
                    withTimeout(5_000) {
                        htsp.connect(
                            host = "127.0.0.1",
                            port = silent.localPort,
                            tls = tls,
                            connectTimeoutMs = 1_000,
                            responseTimeoutMs = 1_000,
                        )
                    }
                    fail("connect must fail when the server never answers hello (tls=$tls)")
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    // withTimeout(5_000) above firing would mean the deadlock is back.
                    if (e.message?.contains("5000") == true) throw AssertionError("connect() deadlocked", e)
                } catch (_: Throwable) {
                    // expected: hello timed out and connect() cleaned up
                }
                assertTrue(htsp.state.value !is ConnectionState.Connected)
            } finally {
                silent.close()
            }
        }
    }

    @Test
    fun selfSignedCertificate_withoutPin_failsWithFingerprintToTrust() = runBlocking {
        val port = startFakeTvheadend()
        try {
            htsp.connect(
                host = "127.0.0.1",
                port = port,
                username = "user",
                password = "secret",
                tls = true,
                tlsFingerprint = null,
                connectTimeoutMs = 5_000,
            )
            fail("untrusted self-signed certificate must not connect")
        } catch (e: UntrustedCertificateException) {
            assertEquals(selfSignedFingerprint(), e.fingerprint)
        }
        assertTrue(htsp.state.value !is ConnectionState.Connected)
        assertTrue("no credentials before TLS: $received", received.none { it.startsWith("authenticate") })
    }

    @Test
    fun serverWithoutStartTls_failsInsteadOfFallingBackToPlain() = runBlocking {
        val port = startFakeTvheadend(offerStartTls = false)
        try {
            htsp.connect(
                host = "127.0.0.1",
                port = port,
                username = "user",
                password = "secret",
                tls = true,
                tlsFingerprint = selfSignedFingerprint(),
                connectTimeoutMs = 5_000,
            )
            fail("TLS on and the server doesn't offer starttls: must not connect in plain text")
        } catch (_: StartTlsNotOfferedException) {
        }
        assertTrue(htsp.state.value !is ConnectionState.Connected)
        assertEquals(listOf("hello"), received.toList())
    }

    @Test
    fun startTlsRefused_failsWithReason() = runBlocking {
        val port = startFakeTvheadend(refuseStartTls = true)
        try {
            htsp.connect(
                host = "127.0.0.1",
                port = port,
                tls = true,
                tlsFingerprint = selfSignedFingerprint(),
                connectTimeoutMs = 5_000,
            )
            fail("refused startTLS must not connect")
        } catch (e: StartTlsRefusedException) {
            assertTrue(e.reason, e.reason.contains("certificate"))
        }
        assertTrue(htsp.state.value !is ConnectionState.Connected)
    }

    @Test
    fun plainClient_withoutTls_stillWorks() = runBlocking {
        val port = startFakeTvheadend()
        htsp.connect(
            host = "127.0.0.1",
            port = port,
            username = "user",
            password = "secret",
            tls = false,
            connectTimeoutMs = 5_000,
        )
        assertTrue(htsp.state.value is ConnectionState.Connected)
        assertEquals(listOf("hello", "authenticate"), received.toList())
    }

    @Test
    fun serverRequiringEncryption_withTlsOff_reportsTlsRequired() = runBlocking {
        val port = startFakeTvheadend(requireTls = true)
        try {
            htsp.connect(
                host = "127.0.0.1",
                port = port,
                username = "user",
                password = "secret",
                tls = false,
                connectTimeoutMs = 5_000,
            )
            fail("server requires encryption")
        } catch (_: TlsRequiredException) {
        }
        assertTrue(htsp.state.value !is ConnectionState.Connected)
    }

    @Test
    fun serverRequiringEncryption_withTls_connects() = runBlocking {
        val port = startFakeTvheadend(requireTls = true)
        htsp.connect(
            host = "127.0.0.1",
            port = port,
            username = "user",
            password = "secret",
            tls = true,
            tlsFingerprint = selfSignedFingerprint(),
            connectTimeoutMs = 5_000,
        )
        assertTrue(htsp.state.value is ConnectionState.Connected)
    }
}
