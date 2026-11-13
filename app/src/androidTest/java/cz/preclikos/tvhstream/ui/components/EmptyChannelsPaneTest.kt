package cz.preclikos.tvhstream.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertTextEquals
import androidx.test.platform.app.InstrumentationRegistry
import cz.preclikos.tvhstream.R
import cz.preclikos.tvhstream.core.ChannelsEmptyState
import cz.preclikos.tvhstream.core.ConnectionProblem
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class EmptyChannelsPaneTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun loading_showsProgress_withoutSettingsButton() {
        rule.setContent {
            MaterialTheme { EmptyChannelsPane(ChannelsEmptyState.LOADING, onOpenSettings = {}) }
        }
        rule.onNodeWithTag("emptyLoading").assertIsDisplayed()
        rule.onNodeWithTag("openSettings").assertDoesNotExist()
    }

    @Test
    fun noChannels_focusesOpenSettings_andNavigates() {
        var opened = false
        rule.setContent {
            MaterialTheme { EmptyChannelsPane(ChannelsEmptyState.NO_CHANNELS, onOpenSettings = { opened = true }) }
        }
        rule.onNodeWithTag("openSettings").assertIsDisplayed().assertIsFocused()
        rule.onNodeWithTag("openSettings").performClick()
        assertTrue(opened)
    }

    @Test
    fun knownProblem_showsItsTitle_evenWhileReconnecting() {
        rule.setContent {
            MaterialTheme {
                EmptyChannelsPane(
                    ChannelsEmptyState.LOADING,
                    onOpenSettings = {},
                    connectionProblem = ConnectionProblemUi(ConnectionProblem.AUTH_FAILED, retrying = true),
                )
            }
        }
        val title = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.conn_auth_title)
        rule.onNodeWithTag("emptyTitle").assertTextEquals(title)
        rule.onNodeWithTag("emptyLoading").assertDoesNotExist()
        rule.onNodeWithTag("openSettings").assertIsFocused()
    }

    @Test
    fun notConfigured_showsSetupCopy() {
        rule.setContent {
            MaterialTheme {
                EmptyChannelsPane(
                    ChannelsEmptyState.NO_CHANNELS,
                    onOpenSettings = {},
                    connectionProblem = ConnectionProblemUi(ConnectionProblem.NOT_CONFIGURED, retrying = false),
                )
            }
        }
        val title = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.conn_not_configured_title)
        rule.onNodeWithTag("emptyTitle").assertTextEquals(title)
    }
}
