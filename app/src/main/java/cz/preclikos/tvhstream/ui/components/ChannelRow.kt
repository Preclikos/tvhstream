package cz.preclikos.tvhstream.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader

private val RowShape = RoundedCornerShape(12.dp)
private val PiconPlate = Color(0xFF1B2332)

/** "20:00 • Title" when the start time is known, otherwise just the title. */
fun channelProgrammeLine(programStart: String?, programTitle: String): String =
    if (programStart.isNullOrBlank()) programTitle else "$programStart • $programTitle"

/**
 * One channel in a list. The D-pad-focused row is a rounded primary-blue pill; the
 * selected row (kept while focus is elsewhere, e.g. on the side rail) gets a dim tint.
 */
@Composable
fun ChannelRow(
    modifier: Modifier = Modifier,
    number: Int,
    name: String,
    programTitle: String,
    progress: Float?,
    imageLoader: ImageLoader,
    piconPath: String?,
    focused: Boolean,
    onFocus: () -> Unit,
    onConfirm: () -> Unit,
    programStart: String? = null,
) {
    // `focused` = the selected row; hasFocus = the D-pad is actually on this row.
    var hasFocus by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    // Our blue palette: solid primary pill for D-pad focus, dim primary tint for selection.
    val bg = when {
        hasFocus -> colors.primary
        focused -> colors.primary.copy(alpha = 0.16f)
        else -> Color.Transparent
    }
    val titleColor = if (hasFocus) colors.onPrimary else colors.onSurface
    val subtitleColor = if (hasFocus) colors.onPrimary.copy(alpha = 0.85f) else colors.onSurfaceVariant
    val progressColor = if (hasFocus) colors.onPrimary else colors.primary
    val progressTrack = if (hasFocus) colors.onPrimary.copy(alpha = 0.3f) else colors.onSurface.copy(alpha = 0.12f)

    Column(
        modifier
            .fillMaxWidth()
            .clip(RowShape)
            .background(bg)
            .semantics(mergeDescendants = true) { role = Role.Button }
            .onFocusChanged {
                hasFocus = it.isFocused
                if (it.isFocused) onFocus()
            }
            .focusable()
            .onKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown &&
                    (ev.key == Key.Enter || ev.key == Key.NumPadEnter || ev.key == Key.DirectionCenter)
                ) {
                    onConfirm()
                    true
                } else false
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onConfirm() }
            .padding(vertical = 8.dp, horizontal = 10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Fixed dark plate so logos look the same on every row state.
            Box(
                Modifier
                    .width(72.dp)
                    .height(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(PiconPlate),
                contentAlignment = Alignment.Center
            ) {
                PiconBox(
                    imageLoader = imageLoader,
                    piconPath = piconPath,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .padding(4.dp),
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = "$number  $name",
                    style = MaterialTheme.typography.titleSmall,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = channelProgrammeLine(programStart, programTitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = subtitleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (progress != null) {
            Spacer(Modifier.height(6.dp))
            // Thin plain strip instead of LinearProgressIndicator (no M3 gap/stop dot).
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(progressTrack)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(progressColor)
                )
            }
        }
    }
}
