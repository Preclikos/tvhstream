package cz.preclikos.tvhstream.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * Real TLS handshakes against a local server, using test keystores in
 * src/test/resources/tls (a test CA, a CA-signed "localhost" cert and a self-signed
 * "tvheadend" cert, like the one Tvheadend generates for --htsp_tls_port).
 */
class HtspTlsTest {

    private val password = "changeit".toCharArray()
    private var server: SSLServerSocket? = null

    @After
    fun tearDown() {
        runCatching { server?.close() }
    }

    private fun keyStore(name: String): KeyStore =
        KeyStore.getInstance("PKCS12").apply {
            HtspTlsTest::class.java.classLoader!!.getResourceAsStream("tls/$name")!!.use { load(it, password) }
        }

    private fun serverCert(name: String): X509Certificate =
        keyStore(name).getCertificate("server") as X509Certificate

    /** Trust manager that trusts only the test CA (stands in for the system store). */
    private fun testCaTrust(): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(keyStore("truststore.p12"))
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /** Starts a one-shot TLS server that answers each connection with byte 42. */
    private fun startServer(keystore: String): Int {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore(keystore), password)
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        val s = ctx.serverSocketFactory.createServerSocket(0, 1, InetAddress.getLoopbackAddress())
            as SSLServerSocket
        server = s
        thread(isDaemon = true) {
            runCatching {
                s.accept().use { c ->
                    c.getOutputStream().apply { write(42); flush() }
                    c.getInputStream().read()
                }
            }
        }
        return s.localPort
    }

    private fun connect(host: String, port: Int, pin: String?): SSLSocket {
        val plain = Socket().apply {
            soTimeout = 5_000
            connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 5_000)
        }
        return HtspTls.wrap(plain, host, port, pin, systemTrust = testCaTrust())
    }

    @Test
    fun caSignedCertificate_withMatchingHost_connects() {
        val port = startServer("ca-signed-server.p12")
        connect("localhost", port, pin = null).use { ssl ->
            assertTrue(ssl.session.protocol in setOf("TLSv1.2", "TLSv1.3"))
            assertEquals(42, ssl.inputStream.read())
        }
    }

    @Test
    fun caSignedCertificate_withWrongHost_isUntrusted() {
        val port = startServer("ca-signed-server.p12")
        val expected = HtspTls.fingerprint(serverCert("ca-signed-server.p12"))
        try {
            connect("tvh.example.org", port, pin = null)
            fail("host name mismatch must not be accepted")
        } catch (e: UntrustedCertificateException) {
            assertEquals(expected, e.fingerprint)
        }
    }

    @Test
    fun selfSigned_withoutPin_reportsFingerprintForPinning() {
        val port = startServer("self-signed.p12")
        val expected = HtspTls.fingerprint(serverCert("self-signed.p12"))
        try {
            connect("tvheadend", port, pin = null)
            fail("self-signed certificate must not be trusted without a pin")
        } catch (e: UntrustedCertificateException) {
            assertEquals(expected, e.fingerprint)
        }
    }

    @Test
    fun selfSigned_withPin_connects_evenIfHostDiffers() {
        val port = startServer("self-signed.p12")
        // Pin as a user might type it: lower case, no colons.
        val pin = HtspTls.fingerprint(serverCert("self-signed.p12")).replace(":", "").lowercase()
        connect("192.168.1.10", port, pin).use { ssl ->
            assertEquals(42, ssl.inputStream.read())
        }
    }

    @Test
    fun selfSigned_withDifferentPin_isRejectedAsChanged() {
        val port = startServer("self-signed.p12")
        val otherPin = HtspTls.fingerprint(serverCert("ca-signed-server.p12"))
        try {
            connect("tvheadend", port, otherPin)
            fail("a different certificate than the pinned one must be rejected")
        } catch (e: CertificateChangedException) {
            assertEquals(HtspTls.fingerprint(serverCert("self-signed.p12")), e.fingerprint)
            assertEquals(otherPin, e.pinned)
        }
    }

    @Test
    fun fingerprint_isColonSeparatedUpperHexSha256() {
        val fp = HtspTls.fingerprint(serverCert("self-signed.p12"))
        assertTrue(fp, Regex("([0-9A-F]{2}:){31}[0-9A-F]{2}").matches(fp))
    }

    @Test
    fun normalize_and_format() {
        assertEquals("ABCD01", HtspTls.normalize("ab:cd 01"))
        assertNull(HtspTls.normalize("  "))
        assertNull(HtspTls.normalize(null))
        assertEquals("AB:CD:01", HtspTls.format("abcd01"))
    }

    @Test
    fun portForToggle_switchesOnlyDefaultPorts() {
        assertEquals(9983, HtspTls.portForToggle(9982, tls = true))
        assertEquals(9982, HtspTls.portForToggle(9983, tls = false))
        assertEquals(9983, HtspTls.portForToggle(null, tls = true))
        assertEquals(12345, HtspTls.portForToggle(12345, tls = true))
        assertEquals(12345, HtspTls.portForToggle(12345, tls = false))
    }
}
