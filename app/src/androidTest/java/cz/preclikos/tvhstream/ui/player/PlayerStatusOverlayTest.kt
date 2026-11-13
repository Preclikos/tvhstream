package cz.preclikos.tvhstream.ui.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.media3.common.PlaybackException
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PlayerStatusOverlayTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun buffering_showsSpinner() {
        rule.setContent {
            MaterialTheme { PlayerStatusOverlay(PlayerStatus(buffering = true, error = null), onRetry = {}) }
        }
        rule.onNodeWithTag("playerBuffering").assertIsDisplayed()
        rule.onNodeWithTag("playerError").assertDoesNotExist()
    }

    @Test
    fun playing_showsNothing() {
        rule.setContent {
            MaterialTheme { PlayerStatusOverlay(PlayerStatus(buffering = false, error = null), onRetry = {}) }
        }
        rule.onNodeWithTag("playerBuffering").assertDoesNotExist()
        rule.onNodeWithTag("playerError").assertDoesNotExist()
    }

    @Test
    fun error_showsFocusedRetry_andRetries() {
        var retried = false
        val error = PlaybackException(
            "boom", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        )
        rule.setContent {
            MaterialTheme {
                PlayerStatusOverlay(PlayerStatus(buffering = true, error = error), onRetry = { retried = true })
            }
        }
        rule.onNodeWithTag("playerError").assertIsDisplayed()
        // Error wins over buffering.
        rule.onNodeWithTag("playerBuffering").assertDoesNotExist()
        rule.onNodeWithTag("playerRetry").assertIsFocused()
        rule.onNodeWithTag("playerRetry").performClick()
        assertTrue(retried)
    }
}
