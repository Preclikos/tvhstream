package cz.preclikos.tvhstream.ui.components

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction

/**
 * A D-pad friendly text field for Android TV.
 *
 * The soft keyboard is intentionally NOT opened on focus: while navigating the
 * settings form with the D-pad the field is [readOnly] so the IME stays hidden.
 * Only when the user explicitly confirms a field (OK / center) does it enter the
 * editing state (editingId == id), become editable and show the keyboard.
 * Pressing Back, moving focus away or completing the IME action leaves editing.
 */
@Composable
fun TvOutlinedTextField(
    id: String,
    editingId: String?,
    setEditingId: (String?) -> Unit,
    value: String,
    onValueChange: (String) -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    singleLine: Boolean = true,
) {
    val isEditing = editingId == id
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val okRelease = remember { OpenOnOkRelease() }

    EndEditWhenKeyboardHides(isEditing) { setEditingId(null) }

    LaunchedEffect(isEditing) {
        if (isEditing) {
            focusRequester.requestFocus()
            keyboard?.show()
        } else {
            keyboard?.hide()
        }
    }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        readOnly = !isEditing,
        singleLine = singleLine,
        keyboardOptions = keyboardOptions.copy(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { setEditingId(null) }),
        modifier = modifier
            .focusRequester(focusRequester)
            .onFocusChanged { state ->
                // If we lose focus while editing (e.g. user navigates away), stop editing.
                if (!state.isFocused && isEditing) setEditingId(null)
            }
            .onPreviewKeyEvent { ev ->
                if (okRelease.onKey(ev, isEditing) { setEditingId(id) }) return@onPreviewKeyEvent true
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (ev.key) {

                    Key.Back -> {
                        if (isEditing) {
                            setEditingId(null)
                            true
                        } else false
                    }

                    // While not editing the read-only field would otherwise swallow
                    // vertical D-pad events, so drive focus navigation explicitly.
                    Key.DirectionDown -> {
                        if (!isEditing) {
                            focusManager.moveFocus(FocusDirection.Down)
                            true
                        } else false
                    }

                    Key.DirectionUp -> {
                        if (!isEditing) {
                            focusManager.moveFocus(FocusDirection.Up)
                            true
                        } else false
                    }

                    else -> false
                }
            }
    )
}
