package cz.preclikos.tvhstream.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLHandshakeException

class ConnectionProblemTest {

    private fun classify(t: Throwable?) = ConnectionProblem.classify(hostConfigured = true, error = t)

    @Test
    fun blankHost_isNotConfigured_evenWithAnError() {
        assertEquals(
            ConnectionProblem.NOT_CONFIGURED,
            ConnectionProblem.classify(hostConfigured = false, error = ConnectException())
        )
    }

    @Test
    fun noError_isNothingToReport() {
        assertNull(classify(null))
    }

    @Test
    fun networkFailures_areUnreachable() {
        listOf(ConnectException("refused"), UnknownHostException("tvh"), NoRouteToHostException()).forEach {
            assertEquals(it.toString(), ConnectionProblem.UNREACHABLE, classify(it))
        }
    }

    @Test
    fun htspAuthFailure_isAuthFailed() {
        // Message thrown by HtspService.connect on noaccess=1.
        assertEquals(
            ConnectionProblem.AUTH_FAILED,
            classify(IllegalStateException("HTSP authentication failed (noaccess=1)"))
        )
    }

    @Test
    fun timeouts_areTimeout_includingCoroutineTimeouts() {
        assertEquals(ConnectionProblem.TIMEOUT, classify(SocketTimeoutException("silent")))
        val coroutineTimeout = runCatching {
            runBlocking { withTimeout(1) { delay(1_000) } }
        }.exceptionOrNull()
        assertEquals(ConnectionProblem.TIMEOUT, classify(coroutineTimeout))
    }

    @Test
    fun tlsProblems_areDistinguished() {
        assertEquals(ConnectionProblem.TLS_UNTRUSTED, classify(UntrustedCertificateException("AB:CD", null)))
        assertEquals(ConnectionProblem.TLS_CHANGED, classify(CertificateChangedException("AB:CD", "EF:01")))
        assertEquals(ConnectionProblem.TLS_FAILED, classify(SSLHandshakeException("protocol")))
    }

    @Test
    fun causeChain_isSearched() {
        val wrapped = IOException("connect failed", ConnectException("refused"))
        assertEquals(ConnectionProblem.UNREACHABLE, classify(wrapped))
    }

    @Test
    fun deliberateDisconnect_isNotReported() {
        assertNull(classify(CancellationException("Disconnected")))
    }

    @Test
    fun anythingElse_isOther() {
        assertEquals(ConnectionProblem.OTHER, classify(EOFException("Broken/EOF HTSP stream")))
    }
}
