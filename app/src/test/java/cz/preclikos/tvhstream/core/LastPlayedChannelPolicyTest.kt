package cz.preclikos.tvhstream.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LastPlayedChannelPolicyTest {
    private val channels = listOf(10, 20, 30)

    @Test
    fun enabled_restoresPersistedCurrentChannel() {
        assertEquals(20, LastPlayedChannelPolicy.resolve(channels, 20, enabled = true))
    }

    @Test
    fun disabled_preservesFirstChannelDefault() {
        assertEquals(10, LastPlayedChannelPolicy.resolve(channels, 20, enabled = false))
    }

    @Test
    fun missingPersistedChannel_fallsBackToFirstCurrentChannel() {
        assertEquals(10, LastPlayedChannelPolicy.resolve(channels, null, enabled = true))
    }

    @Test
    fun stalePersistedChannel_fallsBackToFirstCurrentChannel() {
        assertEquals(10, LastPlayedChannelPolicy.resolve(channels, 99, enabled = true))
    }

    @Test
    fun emptyChannelList_hasNoSelection() {
        assertNull(LastPlayedChannelPolicy.resolve(emptyList(), 20, enabled = true))
    }
}
