package cz.preclikos.tvhstream.htsp

import cz.preclikos.tvhstream.core.HtspTls
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
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import kotlin.concurrent.thread

/**
 * End-to-end: HtspService speaks HTSP inside TLS to a minimal fake Tvheadend that
 * answers `hello`, using a self-signed certificate like Tvheadend's --htsp_tls_port.
 */
class HtspServiceTlsTest {

    private val password = "changeit".toCharArray()
    private var server: SSLServerSocket? = null
    private val htsp = HtspService(ioDispatcher = Dispatchers.IO)

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

    private fun startFakeTvheadend(): Int {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore(), password)
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        val s = ctx.serverSocketFactory.createServerSocket(0, 1, InetAddress.getLoopbackAddress())
            as SSLServerSocket
        server = s
        thread(isDaemon = true) {
            runCatching {
                while (true) {
                    val c = s.accept()
                    thread(isDaemon = true) {
                        runCatching {
                            c.use {
                                val inp = BufferedInputStream(c.getInputStream())
                                val out = c.getOutputStream()
                                while (true) {
                                    val req = HtspCodec.readMessage(inp)
                                    if (req.method == "hello") {
                                        HtspCodec.writeMessage(
                                            out, "hello",
                                            mapOf(
                                                "seq" to req.seq,
                                                "htspversion" to 43,
                                                "servername" to "Fake Tvheadend",
                                            )
                                        )
                                        out.flush()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return s.localPort
    }

    @Test
    fun pinnedSelfSignedCertificate_connectsAndSpeaksHtsp() = runBlocking {
        val port = startFakeTvheadend()

        htsp.connect(
            host = "127.0.0.1",
            port = port,
            tls = true,
            tlsFingerprint = selfSignedFingerprint(),
            connectTimeoutMs = 5_000,
            responseTimeoutMs = 5_000,
        )

        val state = htsp.state.value
        assertTrue("state=$state", state is ConnectionState.Connected)
        assertEquals(43, (state as ConnectionState.Connected).htspVersion)
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
        val silent = java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        try {
            thread(isDaemon = true) { runCatching { silent.accept().getInputStream().read() } }
            try {
                withTimeout(5_000) {
                    htsp.connect(
                        host = "127.0.0.1",
                        port = silent.localPort,
                        connectTimeoutMs = 1_000,
                        responseTimeoutMs = 1_000,
                    )
                }
                fail("connect must fail when the server never answers hello")
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

    @Test
    fun selfSignedCertificate_withoutPin_failsWithFingerprintToTrust() = runBlocking {
        val port = startFakeTvheadend()
        try {
            htsp.connect(
                host = "127.0.0.1",
                port = port,
                tls = true,
                tlsFingerprint = null,
                connectTimeoutMs = 5_000,
            )
            fail("untrusted self-signed certificate must not connect")
        } catch (e: UntrustedCertificateException) {
            assertEquals(selfSignedFingerprint(), e.fingerprint)
        }
        assertTrue(htsp.state.value !is ConnectionState.Connected)
    }

    @Test
    fun plainConnection_toTlsPort_fails() = runBlocking {
        val port = startFakeTvheadend()
        try {
            htsp.connect(
                host = "127.0.0.1",
                port = port,
                tls = false,
                connectTimeoutMs = 2_000,
                responseTimeoutMs = 2_000,
            )
            fail("plain HTSP against the TLS port must not succeed")
        } catch (_: Throwable) {
            // expected: the server never answers a plain hello
        }
        assertTrue(htsp.state.value !is ConnectionState.Connected)
    }
}
