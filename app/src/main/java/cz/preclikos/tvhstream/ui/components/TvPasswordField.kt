package cz.preclikos.tvhstream.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import cz.preclikos.tvhstream.R

@Composable
fun TvPasswordField(
    id: String,
    editingId: String?,
    setEditingId: (String?) -> Unit,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current

    val textFocus = remember { FocusRequester() }
    val eyeFocus = remember { FocusRequester() }

    var passwordVisible by remember { mutableStateOf(false) }
    var textFocused by remember { mutableStateOf(false) }
    var eyeFocused by remember { mutableStateOf(false) }

    val isEditing = editingId == id

    // když vstoupíš do edit mode, vynuť focus + otevři IME
    LaunchedEffect(isEditing) {
        if (isEditing) {
            textFocus.requestFocus()
            keyboard?.show()
        } else {
            keyboard?.hide()
        }
    }

    val icon = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility
    val desc =
        stringResource(if (passwordVisible) R.string.hide_password else R.string.show_password)

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.password)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),

        // klávesnice jen v edit mode
        readOnly = !isEditing,

        visualTransformation = if (passwordVisible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },

        modifier = modifier
            .focusRequester(textFocus)
            .onFocusChanged {
                textFocused = it.isFocused
                // když odfokusuješ text a edituješ, ukonči edit
                if (!it.isFocused && isEditing) setEditingId(null)
            }
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false

                when (ev.key) {
                    // OK na textu -> vstup do edit
                    Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                        if (textFocused && !isEditing) {
                            setEditingId(id) // LaunchedEffect otevře IME
                            true
                        } else false
                    }

                    // BACK -> konec edit
                    Key.Back -> {
                        if (isEditing) {
                            setEditingId(null)
                            true
                        } else false
                    }

                    // RIGHT -> když nejsi v edit, přesuň se na očičko
                    Key.DirectionRight -> {
                        if (textFocused && !isEditing) {
                            eyeFocus.requestFocus()
                            true
                        } else false
                    }

                    else -> false
                }
            },

        trailingIcon = {
            IconButton(
                onClick = { passwordVisible = !passwordVisible },
                interactionSource = remember { MutableInteractionSource() },
                modifier = Modifier
                    .focusRequester(eyeFocus)
                    .onFocusChanged { eyeFocused = it.isFocused }
                    .onKeyEvent { ev ->
                        if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false

                        when (ev.key) {
                            // OK na očku -> toggle
                            Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                                passwordVisible = !passwordVisible
                                true
                            }

                            // LEFT -> zpět do textu (a zůstaň mimo edit mode)
                            Key.DirectionLeft -> {
                                textFocus.requestFocus()
                                true
                            }

                            // BACK -> když jsi na očku a náhodou edituješ, ukonči edit
                            Key.Back -> {
                                if (isEditing) {
                                    setEditingId(null)
                                    true
                                } else false
                            }

                            else -> false
                        }
                    }
            ) {
                Icon(imageVector = icon, contentDescription = desc)
            }
        }
    )
}
