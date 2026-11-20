package cz.preclikos.tvhstream.core

import kotlinx.coroutines.TimeoutCancellationException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException

/** Why the app has no connection, in terms a viewer can act on. */
enum class ConnectionProblem {
    /** No server address saved yet. */
    NOT_CONFIGURED,

    /** Host unknown, connection refused or no route. */
    UNREACHABLE,

    /** Connected, but the server didn't answer HTSP in time (often the wrong port). */
    TIMEOUT,

    /** Tvheadend rejected the username or password. */
    AUTH_FAILED,

    /** Self-signed TLS certificate waiting for the user to trust it. */
    TLS_UNTRUSTED,

    /** The pinned TLS certificate changed. */
    TLS_CHANGED,

    /** TLS is on, but the server doesn't offer or refused startTLS. */
    TLS_NOT_OFFERED,

    /** The server requires encryption and TLS is off in the app. */
    TLS_REQUIRED,

    /** Any other TLS failure (handshake, old TLS version, ...). */
    TLS_FAILED,

    /** Anything else. */
    OTHER;

    companion object {
        /**
         * Classifies the last connection error. Returns null when there is nothing to
         * report (no error, or a deliberate disconnect/reconnect cancellation).
         */
        fun classify(hostConfigured: Boolean, error: Throwable?): ConnectionProblem? {
            if (!hostConfigured) return NOT_CONFIGURED
            if (error == null) return null

            val chain = generateSequence(error) { it.cause }.take(10).toList()

            fun has(predicate: (Throwable) -> Boolean) = chain.any(predicate)

            return when {
                has { it is UntrustedCertificateException } -> TLS_UNTRUSTED
                has { it is CertificateChangedException } -> TLS_CHANGED
                has { it is StartTlsNotOfferedException || it is StartTlsRefusedException } -> TLS_NOT_OFFERED
                has { it is TlsRequiredException } -> TLS_REQUIRED
                has { it is UnknownHostException || it is ConnectException ||
                        it is NoRouteToHostException || it is PortUnreachableException } -> UNREACHABLE
                has { it is IllegalStateException &&
                        it.message?.contains("authentication failed", ignoreCase = true) == true } -> AUTH_FAILED
                has { it is SocketTimeoutException || it is TimeoutCancellationException } -> TIMEOUT
                has { it is SSLException } -> TLS_FAILED
                // Our own disconnect()/reconnect cancels the session; not a problem to show.
                error is CancellationException -> null
                else -> OTHER
            }
        }
    }
}
