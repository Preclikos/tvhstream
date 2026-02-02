package cz.preclikos.tvhstream.ui.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks

data class UiTrack(
    val group: Tracks.Group,
    val trackIndexInGroup: Int,
    val label: String
)

fun collectTracks(tracks: Tracks, trackType: Int): List<UiTrack> {
    val out = mutableListOf<UiTrack>()
    for (g in tracks.groups) {
        if (g.type != trackType) continue
        if (!g.isSupported) continue

        for (i in 0 until g.length) {
            if (!g.isTrackSupported(i)) continue
            val f = g.getTrackFormat(i)

            val lang = f.language?.takeIf { it.isNotBlank() && it != "und" } ?: ""
            val ch = if (f.channelCount != Format.NO_VALUE) "${f.channelCount}ch" else ""
            val sr = if (f.sampleRate != Format.NO_VALUE) "${f.sampleRate}Hz" else ""
            val role =
                when {
                    (f.roleFlags and C.ROLE_FLAG_MAIN) != 0 -> "main"
                    (f.roleFlags and C.ROLE_FLAG_ALTERNATE) != 0 -> "alt"
                    (f.roleFlags and C.ROLE_FLAG_COMMENTARY) != 0 -> "comm"
                    else -> ""
                }

            val codec = f.sampleMimeType ?: ""

            val label = listOf(lang, ch, sr, role, codec)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
                .ifBlank { "Track ${i + 1}" }

            out += UiTrack(g, i, label)
        }
    }
    return out
}

fun selectAudioTrack(player: Player, choice: UiTrack) {
    val params = player.trackSelectionParameters
        .buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
        .addOverride(
            TrackSelectionOverride(
                choice.group.mediaTrackGroup,
                listOf(choice.trackIndexInGroup)
            )
        )
        .build()

    player.trackSelectionParameters = params
}

fun selectTextTrack(player: Player, choice: UiTrack?) {
    val builder = player.trackSelectionParameters.buildUpon()
        .clearOverridesOfType(C.TRACK_TYPE_TEXT)

    if (choice == null) {

        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
    } else {
        builder
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .addOverride(
                TrackSelectionOverride(
                    choice.group.mediaTrackGroup,
                    listOf(choice.trackIndexInGroup)
                )
            )
    }

    player.trackSelectionParameters = builder.build()
}
