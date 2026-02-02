package cz.preclikos.tvhstream.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import cz.preclikos.tvhstream.R

@Composable
fun AudioTrackDialog(player: Player, onDismiss: () -> Unit) {
    val tracks = player.currentTracks
    val items = remember(tracks) { collectTracks(tracks, C.TRACK_TYPE_AUDIO) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.audio_track)) },
        text = {
            if (items.isEmpty()) {
                Text(stringResource(R.string.no_audio_tracks))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items.forEach { t ->
                        OutlinedButton(
                            onClick = {
                                selectAudioTrack(player, t)
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(t.label)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

@Composable
fun SubtitleTrackDialog(player: Player, onDismiss: () -> Unit) {
    val tracks = player.currentTracks
    val items = remember(tracks) { collectTracks(tracks, C.TRACK_TYPE_TEXT) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.subtitles)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

                OutlinedButton(
                    onClick = {
                        selectTextTrack(player, null)
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.subtitles_off))
                }

                if (items.isEmpty()) {
                    Text(stringResource(R.string.no_subtitles))
                } else {
                    items.forEach { t ->
                        OutlinedButton(
                            onClick = {
                                selectTextTrack(player, t)
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(t.label)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}