package cz.preclikos.tvhstream.player.htsp.reader

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.NalUnitUtil
import androidx.media3.extractor.AvcConfig
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import cz.preclikos.tvhstream.htsp.HtspMessage
import timber.log.Timber
import kotlin.math.abs

@OptIn(UnstableApi::class)
internal class H264StreamReader : PlainStreamReader(C.TRACK_TYPE_VIDEO) {
    private var track: TrackOutput? = null
    private var baseFormat: Format? = null

    private var configured = false
    private var lastPixelRatio: Float = Format.NO_VALUE.toFloat()

    // aby se payload nescannoval pořád
    private var lastFormatUpdatePts: Long = Long.MIN_VALUE
    private val FORMAT_UPDATE_COOLDOWN_US = 1_000_000L // 1s
    private val RATIO_EPS = 0.0005f

    override fun createTracks(stream: HtspMessage, output: ExtractorOutput) {
        val streamIndex = stream.int("index") ?: 0
        val t = output.track(streamIndex, C.TRACK_TYPE_VIDEO)
        track = t

        val fmt = buildFormat(streamIndex, stream)
        baseFormat = fmt
        t.format(fmt)

        configured = (fmt.pixelWidthHeightRatio != Format.NO_VALUE.toFloat())
        lastPixelRatio = fmt.pixelWidthHeightRatio
        lastFormatUpdatePts = Long.MIN_VALUE
    }

    override fun buildFormat(streamIndex: Int, stream: HtspMessage): Format {
        var initData: List<ByteArray> = emptyList()
        var pixelRatio = Format.NO_VALUE.toFloat()

        // --- parse meta (AVCC) ---
        if (stream.fields.contains("meta")) {
            try {
                val avcConfig = AvcConfig.parse(ParsableByteArray(stream.bin("meta")!!))
                initData = avcConfig.initializationData

                // AvcConfig.initializationData typicky = [SPS, PPS] bez start code
                val sps = initData.firstOrNull()
                if (sps != null && sps.isNotEmpty()) {
                    pixelRatio = parseH264PixelWidthHeightRatioFromSps(sps)
                }
            } catch (e: ParserException) {
                Timber.e("Failed to parse H264 meta, discarding: ${e.message}")
            } catch (t: Throwable) {
                Timber.e("SPS SAR parse failed: ${t.message}")
            }
        }

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
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setWidth(width)
            .setHeight(height)
            .apply {
                if (frameRate != Format.NO_VALUE.toFloat()) setFrameRate(frameRate)
                if (initData.isNotEmpty()) setInitializationData(initData)
                if (pixelRatio != Format.NO_VALUE.toFloat() && pixelRatio > 0f && pixelRatio.isFinite()) {
                    setPixelWidthHeightRatio(pixelRatio)
                    Timber.d("Init pixelWidthHeightRatio=$pixelRatio for ${width}x${height}")
                }
            }
            .build()
    }

    override fun consume(message: HtspMessage) {
        val payload = message.bin("payload") ?: return

        val pts = (message.fields["pts"] as? Number)?.toLong() ?: 0L
        val frameType = message.int("frametype") ?: -1 // tvh: 73=I, 66=B, 80=P, -1 unknown

        // --- 1) Update format only when needed ---
        // Pro H264 chceme updatovat nejbezpečněji na keyframe / I-frame
        val isKey = (frameType == -1 || frameType == 73)

        if (lastFormatUpdatePts == Long.MIN_VALUE) {
            lastFormatUpdatePts = pts + FORMAT_UPDATE_COOLDOWN_US
        }

        val shouldProbe =
            (!configured && isKey) ||
                    (isKey && (pts - lastFormatUpdatePts) >= FORMAT_UPDATE_COOLDOWN_US)

        if (shouldProbe) {
            // Najdi SPS v payloadu a spočti pixel ratio
            val sps = extractFirstSpsNal(payload)
            if (sps != null && sps.isNotEmpty()) {
                val newRatio = parseH264PixelWidthHeightRatioFromSps(sps)

                val ratioValid = newRatio != Format.NO_VALUE.toFloat() &&
                        newRatio > 0f && newRatio.isFinite()

                if (ratioValid) {
                    val ratioChanged =
                        (lastPixelRatio == Format.NO_VALUE.toFloat()) ||
                                abs(newRatio - lastPixelRatio) > RATIO_EPS

                    if (ratioChanged) {
                        val t = track
                        val base = baseFormat
                        if (t != null && base != null) {
                            val updated = base.buildUpon()
                                .setPixelWidthHeightRatio(newRatio)
                                .build()
                            t.format(updated)

                            baseFormat = updated
                            lastPixelRatio = newRatio
                            configured = true
                            lastFormatUpdatePts = pts

                            Timber.d("Format updated: pixelWidthHeightRatio=$newRatio")
                        }
                    } else {
                        configured = true
                        lastFormatUpdatePts = pts
                    }
                }
            }
        }

        // --- 2) sample flags ---
        var bufferFlags = 0
        if (isKey) bufferFlags = bufferFlags or C.BUFFER_FLAG_KEY_FRAME

        // --- 3) write sample ---
        val pba = ParsableByteArray(payload)
        track!!.sampleData(pba, payload.size)
        track!!.sampleMetadata(pts, bufferFlags, payload.size, 0, null)
    }

    override val trackType: Int
        get() = C.TRACK_TYPE_VIDEO

    /**
     * Najde první SPS NAL v AnnexB payloadu a vrátí jeho bytes (bez start code).
     * Podporuje start code 00 00 01 i 00 00 00 01.
     *
     * Pozn.: Pokud tvůj payload není AnnexB (ale AVCC length-prefixed), tak řekni – upravím i pro AVCC.
     */
    private fun extractFirstSpsNal(payload: ByteArray): ByteArray? {
        var i = 0
        val limit = minOf(payload.size - 4, 4096)

        fun isStart3(pos: Int): Boolean =
            pos + 2 < payload.size &&
                    payload[pos] == 0.toByte() &&
                    payload[pos + 1] == 0.toByte() &&
                    payload[pos + 2] == 1.toByte()

        fun isStart4(pos: Int): Boolean =
            pos + 3 < payload.size &&
                    payload[pos] == 0.toByte() &&
                    payload[pos + 1] == 0.toByte() &&
                    payload[pos + 2] == 0.toByte() &&
                    payload[pos + 3] == 1.toByte()

        while (i < limit) {
            val startLen = when {
                isStart4(i) -> 4
                isStart3(i) -> 3
                else -> 0
            }
            if (startLen == 0) {
                i++
                continue
            }

            val nalStart = i + startLen
            if (nalStart >= payload.size) return null

            val nalHeader = payload[nalStart].toInt() and 0xFF
            val nalType = nalHeader and 0x1F
            if (nalType == 7) {
                // find nal end (next start code or end)
                var j = nalStart + 1
                while (j < payload.size - 3) {
                    if (isStart3(j) || isStart4(j)) break
                    j++
                }
                return payload.copyOfRange(nalStart, j)
            }

            i = nalStart
        }
        return null
    }

    /**
     * Vrátí SAR jako pixelWidthHeightRatio (sarWidth/sarHeight) ze SPS (bez start code).
     * Když v SPS není VUI/aspect info, vrátí Format.NO_VALUE.toFloat()
     */
    private fun parseH264PixelWidthHeightRatioFromSps(spsNal: ByteArray): Float {
        return try {
            // spsNal: NAL unit bez start code, ale S NAL headerem (typicky to, co máš)
            val sps = NalUnitUtil.parseSpsNalUnit(spsNal, 0, spsNal.size)
            sps.pixelWidthHeightRatio
        } catch (t: Throwable) {
            Format.NO_VALUE.toFloat()
        }
    }

    private fun parseSpsStandard(rbsp: ByteArray, hadNalHeader: Boolean): Float {
        val br = BitReader(rbsp)
        if (hadNalHeader) br.readBits(8)

        val profileIdc = br.readBits(8)
        br.readBits(8) // constraint_set_flags + reserved
        br.readBits(8) // level_idc
        br.readUe()    // seq_parameter_set_id

        val highProfile = profileIdc in setOf(
            100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135
        )

        if (highProfile) {
            val chromaFormatIdc = br.readUe()
            if (chromaFormatIdc == 3) br.readBits(1) // separate_colour_plane_flag
            br.readUe() // bit_depth_luma_minus8
            br.readUe() // bit_depth_chroma_minus8
            br.readBits(1) // qpprime_y_zero_transform_bypass_flag
            val seqScalingMatrixPresent = br.readBits(1) == 1
            if (seqScalingMatrixPresent) {
                val scalingListCount = if (chromaFormatIdc != 3) 8 else 12
                for (i in 0 until scalingListCount) {
                    val present = br.readBits(1) == 1
                    if (present) skipScalingList(br, if (i < 6) 16 else 64)
                }
            }
        }

        br.readUe() // log2_max_frame_num_minus4
        val picOrderCntType = br.readUe()
        if (picOrderCntType == 0) {
            br.readUe()
        } else if (picOrderCntType == 1) {
            br.readBits(1)
            br.readSe()
            br.readSe()
            val numRefFrames = br.readUe()
            repeat(numRefFrames) { br.readSe() }
        }

        br.readUe()
        br.readBits(1)
        br.readUe()
        br.readUe()
        val frameMbsOnly = br.readBits(1) == 1
        if (!frameMbsOnly) br.readBits(1)
        br.readBits(1)

        val frameCropping = br.readBits(1) == 1
        if (frameCropping) {
            br.readUe(); br.readUe(); br.readUe(); br.readUe()
        }

        val vuiPresent = br.readBits(1) == 1
        if (!vuiPresent) return Format.NO_VALUE.toFloat()

        val aspectPresent = br.readBits(1) == 1
        if (!aspectPresent) return Format.NO_VALUE.toFloat()

        val aspectRatioIdc = br.readBits(8)
        if (aspectRatioIdc == 255) {
            val sarW = br.readBits(16)
            val sarH = br.readBits(16)
            return if (sarW > 0 && sarH > 0) sarW.toFloat() / sarH.toFloat() else Format.NO_VALUE.toFloat()
        }

        val (sarW, sarH) = when (aspectRatioIdc) {
            1 -> 1 to 1
            2 -> 12 to 11
            3 -> 10 to 11
            4 -> 16 to 11
            5 -> 40 to 33
            6 -> 24 to 11
            7 -> 20 to 11
            8 -> 32 to 11
            9 -> 80 to 33
            10 -> 18 to 11
            11 -> 15 to 11
            12 -> 64 to 33
            13 -> 160 to 99
            14 -> 4 to 3
            15 -> 3 to 2
            16 -> 2 to 1
            else -> return Format.NO_VALUE.toFloat()
        }
        return sarW.toFloat() / sarH.toFloat()
    }

    private fun unescapeRbsp(nal: ByteArray): ByteArray {
        val out = ByteArray(nal.size)
        var outLen = 0
        var zeros = 0
        for (b in nal) {
            val v = b.toInt() and 0xFF
            if (zeros == 2 && v == 0x03) {
                zeros = 0
                continue
            }
            out[outLen++] = b
            zeros = if (v == 0) zeros + 1 else 0
        }
        return out.copyOf(outLen)
    }

    private fun skipScalingList(br: BitReader, size: Int) {
        var lastScale = 8
        var nextScale = 8
        (0 until size).forEach { j ->
            if (nextScale != 0) {
                val delta = br.readSe()
                nextScale = (lastScale + delta + 256) % 256
            }
            lastScale = if (nextScale == 0) lastScale else nextScale
        }
    }

    private class BitReader(private val data: ByteArray) {
        private var bitPos = 0

        fun readBits(n: Int): Int {
            var out = 0
            repeat(n) {
                val byteIndex = bitPos ushr 3
                val bitInByte = 7 - (bitPos and 7)
                val bit = (data[byteIndex].toInt() ushr bitInByte) and 1
                out = (out shl 1) or bit
                bitPos++
            }
            return out
        }

        fun readUe(): Int {
            var zeros = 0
            while (bitPos < data.size * 8 && readBits(1) == 0) zeros++
            var value = 1
            repeat(zeros) { value = (value shl 1) or readBits(1) }
            return value - 1
        }

        fun readSe(): Int {
            val ue = readUe()
            val sign = if ((ue and 1) == 0) -1 else 1
            return sign * ((ue + 1) / 2)
        }
    }
}
