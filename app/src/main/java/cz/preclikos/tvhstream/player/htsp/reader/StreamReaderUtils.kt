package cz.preclikos.tvhstream.player.htsp.reader

import androidx.media3.common.Format

internal class StreamReaderUtils private constructor() {
    init {
        throw IllegalAccessError("Utility class")
    }

    companion object {

        fun frameDurationToFrameRate(frameDuration: Int): Float {
            var frameRate = Format.NO_VALUE.toFloat()

            if (frameDuration != Format.NO_VALUE) {
                // 1000000 = 1 second, in microseconds.
                frameRate = 1000000 / frameDuration.toFloat()
            }

            return frameRate
        }
    }
}