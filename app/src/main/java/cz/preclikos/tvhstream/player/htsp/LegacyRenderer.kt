package cz.preclikos.tvhstream.player.htsp

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer

@OptIn(UnstableApi::class)
class LegacyRenderer(context: android.content.Context) : DefaultRenderersFactory(context) {
    override fun buildTextRenderers(
        context: android.content.Context,
        output: TextOutput,
        outputLooper: android.os.Looper,
        extensionRendererMode: Int,
        out: ArrayList<androidx.media3.exoplayer.Renderer>
    ) {
        val textRenderer = TextRenderer(output, outputLooper)
        @Suppress("DEPRECATION")
        textRenderer.experimentalSetLegacyDecodingEnabled(true)
        out.add(textRenderer)
    }
}