package cz.preclikos.tvhstream.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.Rule
import org.junit.Test

class RoundIconButtonTest {

    @get:Rule
    val rule = createComposeRule()

    /** Icon-only overlay buttons must be announced by TalkBack. */
    @Test
    fun exposesContentDescription_andButtonRole() {
        rule.setContent {
            MaterialTheme {
                RoundIconButton(
                    icon = { Text("16:9") },
                    onClick = {},
                    focusRequester = FocusRequester(),
                    onFocused = {},
                    contentDescription = "Aspect ratio",
                )
            }
        }
        rule.onNodeWithContentDescription("Aspect ratio")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
    }
}
