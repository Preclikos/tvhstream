package cz.preclikos.tvhstream.player

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.text.TextRenderer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cz.preclikos.tvhstream.htsp.HtspMessage
import cz.preclikos.tvhstream.player.htsp.LegacyRenderer
import cz.preclikos.tvhstream.player.htsp.reader.DvbsubStreamReader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DVB subtitles only work because LegacyRenderer enables the (experimental) legacy
 * TextRenderer decoding path. These tests fail if a media3 upgrade breaks that.
 */
@RunWith(AndroidJUnit4::class)
class DvbsubSupportTest {

    private val stream = HtspMessage(
        method = null,
        seq = null,
        fields = mapOf("composition_id" to 0x0102, "ancillary_id" to 0x0304, "language" to "ces")
    )

    @Test
    fun buildFormat_encodesCompositionAndAncillaryIds() {
        val format = DvbsubStreamReader().formatForTest(5, stream)

        assertEquals(MimeTypes.APPLICATION_DVBSUBS, format.sampleMimeType)
        assertEquals("5", format.id)
        assertEquals("cs", format.language) // media3 normalises ISO 639-2 "ces"
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04), format.initializationData.single())
    }

    @Test
    fun legacyRenderer_textRendererHandlesDvbsub() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val renderers = LegacyRenderer(context).createRenderers(
            android.os.Handler(Looper.getMainLooper()),
            object : androidx.media3.exoplayer.video.VideoRendererEventListener {},
            object : androidx.media3.exoplayer.audio.AudioRendererEventListener {},
            { },
            { },
        )
        val text = renderers.filterIsInstance<TextRenderer>().single()
        assertEquals(C.TRACK_TYPE_TEXT, text.trackType)

        val support = text.supportsFormat(DvbsubStreamReader().formatForTest(0, stream))
        assertEquals(
            C.FORMAT_HANDLED,
            RendererCapabilities.getFormatSupport(support)
        )
    }
}
