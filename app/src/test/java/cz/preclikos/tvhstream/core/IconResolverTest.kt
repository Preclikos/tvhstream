package cz.preclikos.tvhstream.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IconResolverTest {

    @Test
    fun serverRelativePath_isWrappedAsHtspPicon() {
        assertEquals(
            "htsp-picon://default/imagecache/123",
            resolvePiconModel("default", "imagecache/123")
        )
        // Leading slash is normalised away.
        assertEquals(
            "htsp-picon://default/picon/foo.png",
            resolvePiconModel("default", "/picon/foo.png")
        )
    }

    @Test
    fun rawHttpUrls_areLoadedDirectly() {
        assertEquals(
            "http://host/icon.png",
            resolvePiconModel("default", "http://host/icon.png")
        )
        assertEquals(
            "https://host/icon.png",
            resolvePiconModel("default", " https://host/icon.png ")
        )
        assertEquals(
            "HTTPS://HOST/icon.png",
            resolvePiconModel("default", "HTTPS://HOST/icon.png")
        )
    }

    @Test
    fun blankInputs_resolveToNull() {
        assertNull(resolvePiconModel("default", null))
        assertNull(resolvePiconModel("default", ""))
        assertNull(resolvePiconModel("default", "   "))
        assertNull(resolvePiconModel("", "imagecache/1"))
    }
}
