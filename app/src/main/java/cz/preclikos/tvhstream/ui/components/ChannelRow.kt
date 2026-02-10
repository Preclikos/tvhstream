package cz.preclikos.tvhstream.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun ChannelRow(
    modifier: Modifier = Modifier,
    number: Int,
    name: String,
    programTitle: String,
    progress: Float?,
    focused: Boolean,
    onFocus: () -> Unit,
    onConfirm: () -> Unit
) {
    val bg = if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    else MaterialTheme.colorScheme.surface

    val leftBar = if (focused) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.outlineVariant

    Column(
        modifier
            .fillMaxWidth()
            .background(bg)
            .onFocusChanged { if (it.isFocused) onFocus() }
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
            .padding(vertical = 10.dp, horizontal = 10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(34.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(leftBar)
            )
            Spacer(Modifier.width(10.dp))

            Text(
                text = number.toString().padStart(2, ' '),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(30.dp)
            )

            Spacer(Modifier.width(6.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = programTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (progress != null) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(MaterialTheme.shapes.small)
            )
        }
    }
}