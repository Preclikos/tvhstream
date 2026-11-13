package cz.preclikos.tvhstream.ui.recordings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cz.preclikos.tvhstream.R
import cz.preclikos.tvhstream.recordings.Recording
import cz.preclikos.tvhstream.recordings.RecordingsRepository
import org.koin.compose.koinInject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Simple list of finished (and in-progress) recordings; OK plays one. */
@Composable
fun RecordingsScreen(
    repo: RecordingsRepository = koinInject(),
    onPlay: (Recording) -> Unit,
) {
    val recordings by repo.recordings.collectAsState()
    RecordingsList(recordings = recordings, onPlay = onPlay)
}

@Composable
fun RecordingsList(recordings: List<Recording>, onPlay: (Recording) -> Unit) {
    val firstFocus = remember { FocusRequester() }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(14.dp)
    ) {
        Text(
            stringResource(R.string.recordings_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(10.dp))

        if (recordings.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.recordings_empty),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("recordingsEmpty"),
                )
            }
            return@Column
        }

        Surface(
            tonalElevation = 2.dp,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.focusGroup(),
            ) {
                items(recordings, key = { it.id }) { rec ->
                    RecordingRow(
                        recording = rec,
                        onClick = { onPlay(rec) },
                        modifier = if (rec == recordings.first()) Modifier.focusRequester(firstFocus)
                        else Modifier,
                    )
                }
            }
        }
        LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    }
}

@Composable
private fun RecordingRow(recording: Recording, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val colors = MaterialTheme.colorScheme

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (focused) colors.primary.copy(alpha = 0.35f) else colors.surface)
            .then(if (focused) Modifier.border(2.dp, colors.primary, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .semantics(mergeDescendants = true) { role = Role.Button }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .testTag("recording-${recording.id}")
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                recording.title ?: "—",
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = listOfNotNull(
                recording.channelName,
                formatStart(recording.start),
                stringResource(R.string.recordings_duration_min, recording.durationSec / 60),
            ).joinToString("  •  ")
            Text(
                sub,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (recording.isRecording) {
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.recordings_in_progress),
                style = MaterialTheme.typography.labelLarge,
                color = colors.error,
            )
        }
    }
}

private val startFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)

private fun formatStart(epochSec: Long): String? =
    if (epochSec <= 0) null
    else startFormatter.format(Instant.ofEpochSecond(epochSec).atZone(ZoneId.systemDefault()))
