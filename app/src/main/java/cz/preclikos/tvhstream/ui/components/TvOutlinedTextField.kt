package cz.preclikos.tvhstream.ui.components

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

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
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    var focused by remember { mutableStateOf(false) }
    val isEditing = editingId == id

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
        keyboardOptions = keyboardOptions,
        singleLine = singleLine,
        readOnly = !isEditing,

        modifier = modifier
            .focusRequester(focusRequester)
            .onFocusChanged {
                focused = it.isFocused
                if (!it.isFocused && isEditing) setEditingId(null)
            }
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false

                when (ev.key) {
                    Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                        if (focused && !isEditing) {
                            setEditingId(id) // LaunchedEffect pak otevře IME
                            true
                        } else false
                    }

                    Key.Back -> {
                        if (isEditing) {
                            setEditingId(null) // LaunchedEffect schová IME
                            true
                        } else false
                    }

                    else -> false
                }
            }
    )
}
