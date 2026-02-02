package cz.preclikos.tvhstream.player.htsp.reader

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import cz.preclikos.tvhstream.htsp.HtspMessage

@OptIn(UnstableApi::class)
abstract class PlainStreamReader(private val mTrackType: Int) : StreamReader {
    private var mTrackOutput: TrackOutput? = null

    protected abstract val trackType: Int

    override fun createTracks(stream: HtspMessage, output: ExtractorOutput) {
        val streamIndex = stream.int("index")!!
        mTrackOutput = output.track(streamIndex, trackType)
        mTrackOutput!!.format(buildFormat(streamIndex, stream))
    }


    override fun consume(message: HtspMessage) {
        val pts = (message.fields["pts"] as? Number)?.toLong() ?: 0L
        val frameType = message.int("frametype") ?: -1
        val payload = message.bin("payload")!!
        val pba = ParsableByteArray(payload)

        var bufferFlags = 0

        if (mTrackType == C.TRACK_TYPE_VIDEO) {
            // We're looking at a Video stream, be picky about what frames are called keyframes

            // Type -1 = TVHeadend has not provided us a frame type, so everything "is a keyframe"
            // Type 73 = I - Intra-coded picture - Full Picture
            // Type 66 = B - Predicted picture - Depends on previous frames
            // Type 80 = P - Bidirectional predicted picture - Depends on previous+future frames
            if (frameType == -1 || frameType == 73) {
                bufferFlags = bufferFlags or C.BUFFER_FLAG_KEY_FRAME
            }
        } else {
            // We're looking at a Audio / Text etc stream, consider everything a key frame
            bufferFlags = bufferFlags or C.BUFFER_FLAG_KEY_FRAME
        }

        mTrackOutput!!.sampleData(pba, payload.size)
        mTrackOutput!!.sampleMetadata(pts, bufferFlags, payload.size, 0, null)
    }

    protected abstract fun buildFormat(streamIndex: Int, stream: HtspMessage): Format
}