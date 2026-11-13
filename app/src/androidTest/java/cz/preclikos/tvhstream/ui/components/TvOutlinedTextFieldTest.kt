package cz.preclikos.tvhstream.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import org.junit.Rule
import org.junit.Test

/**
 * Verifies the fix for issue #9: while navigating settings with the D-pad the field
 * must NOT enter editing (which is what triggers the soft keyboard). Editing only
 * starts after the user confirms the field with OK / center.
 */
@OptIn(ExperimentalTestApi::class)
class TvOutlinedTextFieldTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun field_entersEditing_onlyAfterOkPressed() {
        composeTestRule.setContent {
            var editingId by remember { mutableStateOf<String?>(null) }
            var value by remember { mutableStateOf("") }
            MaterialTheme {
                Column {
                    TvOutlinedTextField(
                        id = "host",
                        editingId = editingId,
                        setEditingId = { editingId = it },
                        value = value,
                        onValueChange = { value = it },
                        label = { Text("Host") },
                        modifier = Modifier.testTag("field")
                    )
                    Text(
                        text = "editing=${editingId ?: "none"}",
                        modifier = Modifier.testTag("state")
                    )
                }
            }
        }

        // Focusing the field (D-pad navigation) must not start editing.
        composeTestRule.onNodeWithTag("state").assertTextEquals("editing=none")
        composeTestRule.onNodeWithTag("field").requestFocus()
        composeTestRule.onNodeWithTag("state").assertTextEquals("editing=none")

        // OK opens the field on release, not on press: Gboard for TV would otherwise take
        // the release as "Next" and jump to the next field.
        composeTestRule.onNodeWithTag("field").performKeyInput {
            keyDown(Key.DirectionCenter)
        }
        composeTestRule.onNodeWithTag("state").assertTextEquals("editing=none")
        composeTestRule.onNodeWithTag("field").performKeyInput {
            keyUp(Key.DirectionCenter)
        }
        composeTestRule.onNodeWithTag("state").assertTextEquals("editing=host")

        // Back leaves editing again.
        composeTestRule.onNodeWithTag("field").performKeyInput {
            pressKey(Key.Back)
        }
        composeTestRule.onNodeWithTag("state").assertTextEquals("editing=none")
    }
}
