package cz.preclikos.tvhstream.ui.recordings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cz.preclikos.tvhstream.recordings.Recording
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RecordingsListTest {

    @get:Rule
    val rule = createComposeRule()

    private val film = Recording(
        id = 7, title = "Film", channelName = "ČT1",
        start = 1_700_000_000, stop = 1_700_005_400, state = "completed",
    )
    private val news = Recording(
        id = 8, title = "Zprávy", channelName = "ČT24",
        start = 1_700_010_000, stop = 1_700_011_800, state = "recording",
    )

    @Test
    fun empty_showsNoRecordings() {
        rule.setContent { MaterialTheme { RecordingsList(emptyList(), onPlay = {}) } }
        rule.onNodeWithTag("recordingsEmpty").assertIsDisplayed()
    }

    @Test
    fun list_focusesFirst_andPlaysTheClickedRecording() {
        var played: Recording? = null
        rule.setContent { MaterialTheme { RecordingsList(listOf(news, film), onPlay = { played = it }) } }

        rule.onNodeWithTag("recording-8").assertIsFocused()
        rule.onNodeWithText("Film").assertIsDisplayed()
        rule.onNodeWithTag("recording-7").performClick()
        assertEquals(7, played?.id)
    }
}
