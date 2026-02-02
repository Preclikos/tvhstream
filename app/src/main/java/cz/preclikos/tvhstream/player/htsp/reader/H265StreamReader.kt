package cz.preclikos.tvhstream.player.htsp.reader

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.HevcConfig
import androidx.media3.extractor.TrackOutput
import cz.preclikos.tvhstream.htsp.HtspMessage
import timber.log.Timber
import kotlin.math.abs

@OptIn(UnstableApi::class)
internal class H265StreamReader : PlainStreamReader(C.TRACK_TYPE_VIDEO) {

    private var track: TrackOutput? = null
    private var baseFormat: Format? = null

    private var configured = false
    private var lastPixelRatio: Float = Format.NO_VALUE.toFloat()

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

        if (stream.fields.contains("meta")) {
            try {
                val hevcConfig = HevcConfig.parse(ParsableByteArray(stream.bin("meta")!!))
                initData = hevcConfig.initializationData

                // Zkus SAR ze SPS v initData (když je SPS dostupné)
                val sps =
                    initData.firstOrNull { looksLikeHevcSpsNal(it) } ?: findHevcSpsInsideInitData(
                        initData
                    )
                if (sps != null) {
                    pixelRatio = parseHevcPixelWidthHeightRatioFromSpsNal(sps)
                }
            } catch (_: ParserException) {
                // ignore
            } catch (t: Throwable) {
                Log.w("H265StreamReader", "HVCC/SPS SAR parse failed: ${t.message}")
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
            .setSampleMimeType(MimeTypes.VIDEO_H265)
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
        val frameType = message.int("frametype") ?: -1
        val isKey = (frameType == -1 || frameType == 73) // tvh I-frame

        if (lastFormatUpdatePts == Long.MIN_VALUE) {
            lastFormatUpdatePts = pts + FORMAT_UPDATE_COOLDOWN_US
        }

        val shouldProbe =
            (!configured && (isKey || lastFormatUpdatePts == Long.MIN_VALUE)) ||
                    ((pts - lastFormatUpdatePts) >= FORMAT_UPDATE_COOLDOWN_US && isKey)

        if (shouldProbe) {
            val spsNal = extractFirstHevcSpsNalAnnexB(payload)
            if (spsNal != null) {
                val newRatio = parseHevcPixelWidthHeightRatioFromSpsNal(spsNal)
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

                            Log.d(
                                "H265StreamReader",
                                "Format updated: pixelWidthHeightRatio=$newRatio"
                            )
                        }
                    } else {
                        configured = true
                        lastFormatUpdatePts = pts
                    }
                }
            }
        }

        // --- sample flags ---
        var bufferFlags = 0
        if (isKey) bufferFlags = bufferFlags or C.BUFFER_FLAG_KEY_FRAME

        // --- write sample (tvoje cesta) ---
        val pba = ParsableByteArray(payload)
        track!!.sampleData(pba, payload.size)
        track!!.sampleMetadata(pts, bufferFlags, payload.size, 0, null)
    }

    override val trackType: Int
        get() = C.TRACK_TYPE_VIDEO

    // ------------------------------------------------------------
    // HEVC SPS extraction (AnnexB) + SAR parsing from SPS (VUI)
    // ------------------------------------------------------------

    /**
     * Najde první HEVC SPS NAL (nal_unit_type = 33) v AnnexB payloadu a vrátí NAL bytes bez start code.
     */
    private fun extractFirstHevcSpsNalAnnexB(payload: ByteArray): ByteArray? {
        var i = 0
        val limit = minOf(payload.size - 6, 4096)

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
            if (nalStart + 2 > payload.size) return null

            // HEVC NAL header = 2 bytes, nal_unit_type is bits [1..6] of first byte
            val b0 = payload[nalStart].toInt() and 0xFF
            val nalUnitType = (b0 ushr 1) and 0x3F
            if (nalUnitType == 33) {
                // find end
                var j = nalStart + 2
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

    private fun looksLikeHevcSpsNal(nal: ByteArray): Boolean {
        if (nal.size < 2) return false
        val b0 = nal[0].toInt() and 0xFF
        val nalUnitType = (b0 ushr 1) and 0x3F
        return nalUnitType == 33
    }

    private fun findHevcSpsInsideInitData(initData: List<ByteArray>): ByteArray? {
        // někdy initData obsahuje více NALů; zkus projít a najít SPS
        for (b in initData) {
            if (looksLikeHevcSpsNal(b)) return b
        }
        return null
    }

    /**
     * Vytáhne pixelWidthHeightRatio (SAR) z HEVC SPS NAL.
     * NAL je bez start code, obsahuje 2B header.
     * Vrací Format.NO_VALUE.toFloat() pokud VUI/aspect není přítomné.
     */
    private fun parseHevcPixelWidthHeightRatioFromSpsNal(spsNalNoStart: ByteArray): Float {
        // Remove emulation prevention bytes, ale až po odstranění 2B NAL headeru
        if (spsNalNoStart.size < 3) return Format.NO_VALUE.toFloat()

        val rbsp = unescapeRbsp(spsNalNoStart)

        val br = BitReader(rbsp)

        // NAL header (2 bytes) – přeskoč
        br.readBits(16)

        // sps_video_parameter_set_id: u(4)
        br.readBits(4)
        // sps_max_sub_layers_minus1: u(3)
        val maxSubLayersMinus1 = br.readBits(3)
        // sps_temporal_id_nesting_flag: u(1)
        br.readBits(1)

        // profile_tier_level(1, sps_max_sub_layers_minus1)
        skipProfileTierLevel(br, maxSubLayersMinus1)

        // sps_seq_parameter_set_id: ue(v)
        br.readUe()

        val chromaFormatIdc = br.readUe()
        if (chromaFormatIdc == 3) {
            br.readBits(1) // separate_colour_plane_flag
        }

        br.readUe() // pic_width_in_luma_samples
        br.readUe() // pic_height_in_luma_samples

        val conformanceWindowFlag = br.readBits(1) == 1
        if (conformanceWindowFlag) {
            br.readUe(); br.readUe(); br.readUe(); br.readUe()
        }

        br.readUe() // bit_depth_luma_minus8
        br.readUe() // bit_depth_chroma_minus8
        br.readUe() // log2_max_pic_order_cnt_lsb_minus4

        val spsSubLayerOrderingInfoPresent = br.readBits(1) == 1
        val startLayer = if (spsSubLayerOrderingInfoPresent) 0 else maxSubLayersMinus1
        for (i in startLayer..maxSubLayersMinus1) {
            br.readUe() // sps_max_dec_pic_buffering_minus1
            br.readUe() // sps_max_num_reorder_pics
            br.readUe() // sps_max_latency_increase_plus1
        }

        br.readUe() // log2_min_luma_coding_block_size_minus3
        br.readUe() // log2_diff_max_min_luma_coding_block_size
        br.readUe() // log2_min_luma_transform_block_size_minus2
        br.readUe() // log2_diff_max_min_luma_transform_block_size
        br.readUe() // max_transform_hierarchy_depth_inter
        br.readUe() // max_transform_hierarchy_depth_intra

        val scalingListEnabled = br.readBits(1) == 1
        if (scalingListEnabled) {
            val spsScalingListDataPresent = br.readBits(1) == 1
            if (spsScalingListDataPresent) {
                // scaling_list_data() je velké → přeskočíme “hrubě” by parse, ale pro SAR ho nepotřebujeme.
                // V praxi u broadcastu často není, nebo je to OK ignorovat přes "try-catch" styl.
                // Zde radši rychle skončíme bez SAR, než riskovat desync bitreaderu.
                return Format.NO_VALUE.toFloat()
            }
        }

        br.readBits(1) // amp_enabled_flag
        br.readBits(1) // sample_adaptive_offset_enabled_flag

        val pcmEnabled = br.readBits(1) == 1
        if (pcmEnabled) {
            br.readBits(4) // pcm_sample_bit_depth_luma_minus1
            br.readBits(4) // pcm_sample_bit_depth_chroma_minus1
            br.readUe()    // log2_min_pcm_luma_coding_block_size_minus3
            br.readUe()    // log2_diff_max_min_pcm_luma_coding_block_size
            br.readBits(1) // pcm_loop_filter_disabled_flag
        }

        val numShortTermRefPicSets = br.readUe()
        for (i in 0 until numShortTermRefPicSets) {
            // short_term_ref_pic_set() je dost složité
            // Pro broadcast SPS se to obvykle dá přeskočit jen pokud bys měl plný parser.
            // Bez něj riskuješ rozhození bit pozice -> radši to vzdáme.
            return Format.NO_VALUE.toFloat()
        }

        val longTermRefPicsPresent = br.readBits(1) == 1
        if (longTermRefPicsPresent) {
            val numLongTerm = br.readUe()
            repeat(numLongTerm) {
                // lt_ref_pic_poc_lsb_sps: u(v) kde v = log2_max_pic_order_cnt_lsb_minus4 + 4
                // nemáme v ruce přesně, ale je to br.readBits(...)
                // radši ne
                return Format.NO_VALUE.toFloat()
            }
        }

        br.readBits(1) // sps_temporal_mvp_enabled_flag
        br.readBits(1) // strong_intra_smoothing_enabled_flag

        val vuiPresent = br.readBits(1) == 1
        if (!vuiPresent) return Format.NO_VALUE.toFloat()

        // ---- VUI ----
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

    private fun skipProfileTierLevel(br: BitReader, maxSubLayersMinus1: Int) {
        br.readBits(2) // general_profile_space
        br.readBits(1) // general_tier_flag
        br.readBits(5) // general_profile_idc
        br.readBits(32) // general_profile_compatibility_flags
        br.readBits(1)  // general_progressive_source_flag
        br.readBits(1)  // general_interlaced_source_flag
        br.readBits(1)  // general_non_packed_constraint_flag
        br.readBits(1)  // general_frame_only_constraint_flag
        br.readBits(44) // general_reserved_zero_44bits (constraint flags)
        br.readBits(8)  // general_level_idc

        val subLayerProfilePresent = BooleanArray(maxSubLayersMinus1)
        val subLayerLevelPresent = BooleanArray(maxSubLayersMinus1)
        for (i in 0 until maxSubLayersMinus1) {
            subLayerProfilePresent[i] = br.readBits(1) == 1
            subLayerLevelPresent[i] = br.readBits(1) == 1
        }
        if (maxSubLayersMinus1 > 0) {
            for (i in maxSubLayersMinus1 until 8) br.readBits(2) // reserved_zero_2bits
        }
        for (i in 0 until maxSubLayersMinus1) {
            if (subLayerProfilePresent[i]) {
                br.readBits(2)
                br.readBits(1)
                br.readBits(5)
                br.readBits(32)
                br.readBits(1)
                br.readBits(1)
                br.readBits(1)
                br.readBits(1)
                br.readBits(44)
            }
            if (subLayerLevelPresent[i]) {
                br.readBits(8)
            }
        }
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
    }
}
