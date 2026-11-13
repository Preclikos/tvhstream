package cz.preclikos.tvhstream.ui

import androidx.compose.ui.unit.dp

/**
 * Android TV layout guideline: keep content ~5% from the screen edges (48 dp
 * horizontal, 27 dp vertical on a 960x540 dp screen) so overscan doesn't clip it.
 * Backgrounds and video may still bleed to the edges.
 */
object TvSafeArea {
    val Horizontal = 48.dp
    val Vertical = 27.dp
}
