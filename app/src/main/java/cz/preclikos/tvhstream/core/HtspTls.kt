package cz.preclikos.tvhstream.core

import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * The server certificate is not trusted by the system (self-signed, unknown CA, or the
 * host name doesn't match). The user can compare [fingerprint] with the one Tvheadend
 * shows (Configuration > General > Base) and pin it.
 */
class UntrustedCertificateException(val fingerprint: String, cause: Throwable?) :
    SSLException("Server certificate is not trusted (SHA-256 $fingerprint)", cause)

/** A pinned certificate is configured but the server presented a different one. */
class CertificateChangedException(val fingerprint: String, val pinned: String) :
    SSLException("Server certificate changed (SHA-256 $fingerprint, pinned $pinned)")

/**
 * TLS for the encrypted HTSP port (Tvheadend `--htsp_tls_port`, standard 9943).
 * Same trust model as Tvheadend's Python client:
 *  - with a pinned SHA-256 fingerprint, accept exactly that certificate (self-signed);
 *  - otherwise verify the chain against the system trust store and the host name.
 * TLS 1.2 or newer only.
 */
object HtspTls {
    const val DEFAULT_PLAIN_PORT = 9982
    const val DEFAULT_TLS_PORT = 9943

    private val PROTOCOLS = listOf("TLSv1.3", "TLSv1.2")

    /** SHA-256 of the DER certificate as "AB:CD:...". */
    fun fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString(":") { "%02X".format(it) }

    /** Accepts "ab:cd", "AB CD", "abcd"; null/blank means no pin. */
    fun normalize(fingerprint: String?): String? =
        fingerprint?.replace(":", "")?.replace(" ", "")?.uppercase()?.takeIf { it.isNotEmpty() }

    /** "ABCD.." (any form) -> "AB:CD:.." for display/storage. */
    fun format(fingerprint: String): String =
        normalize(fingerprint).orEmpty().chunked(2).joinToString(":")

    /** Port to suggest when the TLS switch is toggled (keeps a custom port). */
    fun portForToggle(currentPort: Int?, tls: Boolean): Int = when {
        tls && (currentPort == null || currentPort == DEFAULT_PLAIN_PORT) -> DEFAULT_TLS_PORT
        !tls && (currentPort == null || currentPort == DEFAULT_TLS_PORT) -> DEFAULT_PLAIN_PORT
        else -> currentPort!!
    }

    /**
     * Upgrades an already connected [plain] socket to TLS and completes the handshake.
     * Throws [UntrustedCertificateException] / [CertificateChangedException] so the UI
     * can offer pinning; other failures surface as [SSLException].
     */
    fun wrap(
        plain: Socket,
        host: String,
        port: Int,
        pinnedFingerprint: String?,
        systemTrust: X509TrustManager = defaultTrustManager(),
    ): SSLSocket {
        val pin = normalize(pinnedFingerprint)
        val trust = CapturingTrustManager(pin, systemTrust)
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        val factory: SSLSocketFactory = ctx.socketFactory

        val ssl = factory.createSocket(plain, host, port, /* autoClose = */ true) as SSLSocket
        ssl.enabledProtocols = ssl.supportedProtocols.filter { it in PROTOCOLS }.toTypedArray()
        ssl.sslParameters = ssl.sslParameters.apply {
            // Host name verification only for the CA path; a pin replaces it.
            if (pin == null) endpointIdentificationAlgorithm = "HTTPS"
        }

        try {
            ssl.startHandshake()
        } catch (e: SSLException) {
            runCatching { ssl.close() }
            trust.failure?.let { throw it }
            throw e
        }
        return ssl
    }

    fun defaultTrustManager(): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private class CapturingTrustManager(
        private val pin: String?,
        private val system: X509TrustManager,
    ) : X509ExtendedTrustManager() {

        @Volatile
        var failure: SSLException? = null

        private fun check(chain: Array<out X509Certificate>, verify: () -> Unit) {
            if (chain.isEmpty()) throw CertificateException("Empty certificate chain")
            val fp = fingerprint(chain[0])
            if (pin != null) {
                if (normalize(fp) != pin) {
                    failure = CertificateChangedException(fp, format(pin))
                    throw CertificateException("Certificate fingerprint mismatch")
                }
                return
            }
            try {
                verify()
            } catch (e: CertificateException) {
                failure = UntrustedCertificateException(fp, e)
                throw e
            }
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket?) =
            check(chain) {
                if (system is X509ExtendedTrustManager) {
                    system.checkServerTrusted(chain, authType, socket)
                } else {
                    system.checkServerTrusted(chain, authType)
                }
            }

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine?) =
            check(chain) {
                if (system is X509ExtendedTrustManager) {
                    system.checkServerTrusted(chain, authType, engine)
                } else {
                    system.checkServerTrusted(chain, authType)
                }
            }

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) =
            check(chain) { system.checkServerTrusted(chain, authType) }

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket?) =
            throw CertificateException("Client certificates are not supported")

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine?) =
            throw CertificateException("Client certificates are not supported")

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
            throw CertificateException("Client certificates are not supported")

        override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
    }
}
