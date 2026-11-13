package cz.preclikos.tvhstream.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import cz.preclikos.tvhstream.R

/**
 * Trust-on-first-use for Tvheadend's self-signed TLS certificate: shows the SHA-256
 * fingerprint to compare with Tvheadend (Configuration > General > Base) and lets the
 * user remember it. A *changed* certificate gets a warning and focuses "Don't connect".
 */
@Composable
fun CertificateTrustDialog(
    host: String,
    fingerprint: String,
    previousFingerprint: String?,
    onTrust: () -> Unit,
    onReject: () -> Unit,
) {
    val changed = previousFingerprint != null
    val trustFocus = remember { FocusRequester() }
    val rejectFocus = remember { FocusRequester() }

    AlertDialog(
        onDismissRequest = onReject,
        title = {
            Text(
                stringResource(if (changed) R.string.tls_changed_title else R.string.tls_trust_title),
                color = if (changed) MaterialTheme.colorScheme.error else Color.Unspecified,
            )
        },
        text = {
            Column {
                Text(
                    stringResource(
                        if (changed) R.string.tls_changed_message else R.string.tls_trust_message,
                        host
                    )
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "SHA-256",
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    fingerprint,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("certFingerprint"),
                )
                if (changed) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.tls_previous, previousFingerprint!!),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onTrust,
                modifier = Modifier
                    .focusRequester(trustFocus)
                    .testTag("certTrust"),
            ) { Text(stringResource(R.string.tls_trust)) }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onReject,
                modifier = Modifier
                    .focusRequester(rejectFocus)
                    .testTag("certReject"),
            ) { Text(stringResource(R.string.tls_reject)) }
        },
    )

    LaunchedEffect(changed) {
        runCatching { (if (changed) rejectFocus else trustFocus).requestFocus() }
    }
}
