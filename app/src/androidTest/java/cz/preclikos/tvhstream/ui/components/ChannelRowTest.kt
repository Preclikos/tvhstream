package cz.preclikos.tvhstream.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import coil3.ImageLoader
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChannelRowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun showsChannelAndProgramme_andConfirmsOnClick() {
        var confirmed = false
        composeTestRule.setContent {
            MaterialTheme {
                ChannelRow(
                    modifier = Modifier.testTag("row"),
                    number = 3,
                    name = "ČT1 HD",
                    programTitle = "Večerní zprávy",
                    progress = 0.5f,
                    imageLoader = ImageLoader(LocalContext.current),
                    piconPath = null,
                    focused = false,
                    onFocus = {},
                    onConfirm = { confirmed = true }
                )
            }
        }

        composeTestRule.onNodeWithText("ČT1 HD").assertIsDisplayed()
        composeTestRule.onNodeWithText("Večerní zprávy").assertIsDisplayed()

        composeTestRule.onNodeWithTag("row").performClick()
        assertTrue(confirmed)
    }
}
