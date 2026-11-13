package cz.preclikos.tvhstream.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/** OK / center / Enter on a TV remote. */
internal fun isOkKey(key: Key): Boolean =
    key == Key.Enter || key == Key.NumPadEnter || key == Key.DirectionCenter

/**
 * Opens a read-only TV text field on OK *release*: opening on key-down showed the
 * keyboard while OK was still held, and Gboard for TV took the release as "Next" and
 * jumped to the next field. The down press is consumed and remembered; the matching
 * up press opens the field.
 */
internal class OpenOnOkRelease {
    private var armed = false

    /** Returns true if the event was consumed. */
    fun onKey(event: KeyEvent, isEditing: Boolean, open: () -> Unit): Boolean {
        if (!isOkKey(event.key) || isEditing) {
            armed = false
            return false
        }
        return when (event.type) {
            KeyEventType.KeyDown -> {
                armed = true
                true
            }

            KeyEventType.KeyUp -> {
                if (armed) {
                    armed = false
                    open()
                    true
                } else false
            }

            else -> false
        }
    }
}

/**
 * Ends editing when the on-screen keyboard goes away by itself. The IME consumes Back
 * (it just hides the keyboard), so the field never saw it and stayed in edit mode,
 * swallowing D-pad Down. Also handles a Back that does reach the activity.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EndEditWhenKeyboardHides(isEditing: Boolean, onEnd: () -> Unit) {
    val imeVisible = WindowInsets.isImeVisible
    // Only react to a hide after the keyboard was actually shown for this edit.
    var seenVisible by remember(isEditing) { mutableStateOf(false) }

    LaunchedEffect(isEditing, imeVisible) {
        if (!isEditing) return@LaunchedEffect
        if (imeVisible) {
            seenVisible = true
        } else if (seenVisible) {
            onEnd()
        }
    }

    BackHandler(enabled = isEditing) { onEnd() }
}
