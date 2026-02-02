package cz.preclikos.tvhstream.player.htsp.reader

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import cz.preclikos.tvhstream.htsp.HtspMessage

@OptIn(UnstableApi::class)
internal class Mpeg2VideoStreamReader : PlainStreamReader(C.TRACK_TYPE_VIDEO) {
    private var lastAspectInfo: Int? = null
    private var lastFormatUpdatePts: Long = Long.MIN_VALUE
    private val FORMAT_UPDATE_COOLDOWN_US = 1_000_000L // 1s, uprav dle chuti
    private val RATIO_EPS = 0.0005f

    private var track: TrackOutput? = null
    private var baseFormat: Format? = null
    private var lastPixelRatio: Float = Format.NO_VALUE.toFloat()
    private var configured = false

    override fun createTracks(stream: HtspMessage, output: ExtractorOutput) {
        // id může být streamIndex, nebo postupně – ty to máš přes factory, takhle je OK:
        val streamIndex = stream.int("index") ?: 0
        val t = output.track(streamIndex, C.TRACK_TYPE_VIDEO)
        track = t

        val fmt = buildFormat(streamIndex, stream)
        baseFormat = fmt
        t.format(fmt)

        configured = false
        lastPixelRatio = fmt.pixelWidthHeightRatio
    }

    override fun buildFormat(streamIndex: Int, stream: HtspMessage): Format {
        val width = stream.int("width") ?: Format.NO_VALUE
        val height = stream.int("height") ?: Format.NO_VALUE

        val duration = stream.int("duration") ?: Format.NO_VALUE
        val frameRate =
            if (duration != Format.NO_VALUE)
                StreamReaderUtils.frameDurationToFrameRate(duration)
            else
                Format.NO_VALUE.toFloat()

        return Format.Builder()
            .setId(streamIndex.toString())
            .setSampleMimeType(MimeTypes.VIDEO_MPEG2)
            .setWidth(width)
            .setHeight(height)
            .apply {
                if (frameRate != Format.NO_VALUE.toFloat()) setFrameRate(frameRate)
                // pixelWidthHeightRatio zatím neznáme – doplníme po přečtení seq headeru z payloadu
            }
            .build()
    }

    override fun consume(message: HtspMessage) {
        val payload = message.bin("payload") ?: return

        val pts = (message.fields["pts"] as? Number)?.toLong() ?: 0L
        val frameType = message.int("frametype") ?: -1

        // --- 1) Update format only when needed ---
        // Skenujeme:
        // - pokud ještě nejsme configured
        // - nebo (volitelně) když uběhl cooldown (pro případ, že se AR změní v čase)
        val shouldProbe =
            !configured || (pts - lastFormatUpdatePts) >= FORMAT_UPDATE_COOLDOWN_US

        if (shouldProbe) {
            val ar = parseMpeg2AspectRatioFromSequenceHeader(payload)
            if (ar != null) {
                // když se AR nezměnil, nemusíme nic dělat
                val arChanged = (lastAspectInfo == null || lastAspectInfo != ar)

                val (width, height) = (baseFormat?.width ?: Format.NO_VALUE) to (baseFormat?.height
                    ?: Format.NO_VALUE)
                val pixelRatio = mpeg2ArToPixelRatio(width, height, ar)

                if (pixelRatio != null) {
                    val ratioChanged = lastPixelRatio == Format.NO_VALUE.toFloat() ||
                            kotlin.math.abs(pixelRatio - lastPixelRatio) > RATIO_EPS

                    if (arChanged || ratioChanged) {
                        val t = track
                        val base = baseFormat
                        if (t != null && base != null) {
                            val updated = base.buildUpon()
                                .setPixelWidthHeightRatio(pixelRatio)
                                .build()

                            t.format(updated)
                            baseFormat = updated
                            lastPixelRatio = pixelRatio
                            lastAspectInfo = ar
                            configured = true
                            lastFormatUpdatePts = pts

                            Log.d(
                                "Mpeg2VideoStreamReader",
                                "Format updated: ratio=$pixelRatio (AR=$ar) ${width}x${height}"
                            )
                        }
                    } else {
                        // máme to stejné, jen si ulož, že už víme AR (ať se to uklidní)
                        configured = true
                        lastAspectInfo = ar
                        lastFormatUpdatePts = pts
                    }
                }
            }
        }

        // --- 2) Sample flags ---
        var bufferFlags = 0
        if (frameType == -1 || frameType == 73) {
            bufferFlags = bufferFlags or C.BUFFER_FLAG_KEY_FRAME
        }

        // --- 3) Write sample ---
        val pba = ParsableByteArray(payload)
        track!!.sampleData(pba, payload.size)
        track!!.sampleMetadata(pts, bufferFlags, payload.size, 0, null)
    }

    /**
     * MPEG-2 sequence header start code: 00 00 01 B3
     * Za tím:
     * - horizontal_size_value 12b
     * - vertical_size_value   12b
     * - aspect_ratio_info      4b  <-- to nás zajímá
     * - frame_rate_code        4b
     *
     * Vrací aspect_ratio_info (Int), typicky:
     * 2=4:3, 3=16:9, 4=2.21:1
     */
    private fun parseMpeg2AspectRatioFromSequenceHeader(payload: ByteArray): Int? {
        // projdeme prvních pár KB, stačí bohatě
        val limit = minOf(payload.size - 7, 4096)
        var i = 0
        while (i < limit) {
            if (payload[i] == 0.toByte() && payload[i + 1] == 0.toByte() && payload[i + 2] == 1.toByte()
                && payload[i + 3] == 0xB3.toByte()
            ) {
                // bytes after start code
                // We need bits from payload[i+4..]
                val b4 = payload[i + 4].toInt() and 0xFF
                val b5 = payload[i + 5].toInt() and 0xFF
                val b6 = payload[i + 6].toInt() and 0xFF
                val b7 = payload[i + 7].toInt() and 0xFF

                // Skip width/height bits (12+12 = 24 bits).
                // Next 4 bits are aspect_ratio_info = top 4 bits of next nibble.
                // Layout:
                // width:  b4(8) + high4(b5)
                // height: low4(b5) + b6(8) + high4(b7)
                // next nibble: low4(b7) is aspect_ratio_info?  -> POZOR, přesně:
                //
                // After start code:
                // bits:
                // b4: width[11..4]
                // b5: width[3..0] height[11..8]
                // b6: height[7..0]
                // b7: aspect_ratio_info[3..0] frame_rate_code[3..0]
                //
                // => aspect_ratio_info = high nibble of b7
                val aspectRatioInfo = (b7 ushr 4) and 0x0F
                if (aspectRatioInfo in 1..4) return aspectRatioInfo
                return null
            }
            i++
        }
        return null
    }

    /**
     * Převod MPEG2 aspect_ratio_info + rozlišení -> pixelWidthHeightRatio (PAR).
     *
     * Pro SD se reálně používá:
     * - PAL 720x576: 4:3 => 16/15, 16:9 => 64/45
     * - NTSC 720x480: 4:3 => 8/9, 16:9 => 32/27
     *
     * Pro HD obvykle 1:1 (už je square pixels).
     */
    private fun mpeg2ArToPixelRatio(width: Int, height: Int, aspectRatioInfo: Int): Float? {
        if (width <= 0 || height <= 0 || width == Format.NO_VALUE || height == Format.NO_VALUE) return null

        val is16by9 = (aspectRatioInfo == 3)
        val is4by3 = (aspectRatioInfo == 2)

        // HD / square pixels
        if (width >= 1280 || height >= 720) {
            return 1f
        }

        // PAL SD
        if (width == 720 && height == 576) {
            return when {
                is16by9 -> 64f / 45f
                is4by3 -> 16f / 15f
                else -> null
            }
        }

        // NTSC SD
        if (width == 720 && height == 480) {
            return when {
                is16by9 -> 32f / 27f
                is4by3 -> 8f / 9f
                else -> null
            }
        }

        // 704 varianta (někdy v DVB)
        if (width == 704 && height == 576) {
            // PAL 704:
            // 16:9 => 16/11? (záleží), jednoduchý fallback: dopočti z DAR
            return when {
                is16by9 -> (16f / 9f) / (width.toFloat() / height.toFloat())
                is4by3 -> (4f / 3f) / (width.toFloat() / height.toFloat())
                else -> null
            }
        }

        // generic fallback: dopočti PAR z DAR a rozlišení
        return when {
            is16by9 -> (16f / 9f) / (width.toFloat() / height.toFloat())
            is4by3 -> (4f / 3f) / (width.toFloat() / height.toFloat())
            else -> null
        }
    }

    override val trackType: Int
        get() = C.TRACK_TYPE_VIDEO
}
