package cz.preclikos.tvhstream.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CertificateTrustDialogTest {

    @get:Rule
    val rule = createComposeRule()

    private val fp = "AB:CD:EF:01"

    @Test
    fun firstConnection_showsFingerprint_focusesTrust_andTrusts() {
        var trusted = false
        rule.setContent {
            MaterialTheme {
                CertificateTrustDialog(
                    host = "tvh.local",
                    fingerprint = fp,
                    previousFingerprint = null,
                    onTrust = { trusted = true },
                    onReject = {},
                )
            }
        }
        rule.onNodeWithTag("certFingerprint").assertTextEquals(fp)
        rule.onNodeWithTag("certTrust").assertIsFocused()
        rule.onNodeWithTag("certTrust").performClick()
        assertTrue(trusted)
    }

    @Test
    fun changedCertificate_showsPrevious_andFocusesReject() {
        var rejected = false
        rule.setContent {
            MaterialTheme {
                CertificateTrustDialog(
                    host = "tvh.local",
                    fingerprint = fp,
                    previousFingerprint = "11:22:33:44",
                    onTrust = {},
                    onReject = { rejected = true },
                )
            }
        }
        rule.onNodeWithText("11:22:33:44", substring = true).assertExists()
        rule.onNodeWithTag("certReject").assertIsFocused()
        rule.onNodeWithTag("certReject").performClick()
        assertTrue(rejected)
    }
}
