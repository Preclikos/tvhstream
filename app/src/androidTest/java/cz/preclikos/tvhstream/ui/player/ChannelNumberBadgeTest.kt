package cz.preclikos.tvhstream.ui.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import cz.preclikos.tvhstream.R
import org.junit.Rule
import org.junit.Test

class ChannelNumberBadgeTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun showsDigits_andMatchedChannel() {
        rule.setContent { MaterialTheme { ChannelNumberBadge(digits = "12", channelName = "12  Nova") } }
        rule.onNodeWithTag("channelNumberDigits").assertTextEquals("12")
        rule.onNodeWithTag("channelNumberName").assertTextEquals("12  Nova")
    }

    @Test
    fun unknownNumber_saysNoChannel() {
        val none = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.channel_number_none)
        rule.setContent { MaterialTheme { ChannelNumberBadge(digits = "7", channelName = null) } }
        rule.onNodeWithTag("channelNumberName").assertTextEquals(none)
    }
}
