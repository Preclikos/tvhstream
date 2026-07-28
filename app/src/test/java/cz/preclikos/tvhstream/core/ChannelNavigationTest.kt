package cz.preclikos.tvhstream.core

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelNavigationTest {

    private val channels = listOf(10, 20, 30)

    @Test
    fun next_returnsFollowingChannel() {
        assertEquals(20, ChannelNavigation.adjacentId(channels, 10, 1))
    }

    @Test
    fun previous_returnsPrecedingChannel() {
        assertEquals(20, ChannelNavigation.adjacentId(channels, 30, -1))
    }

    @Test
    fun next_wrapsAfterLastChannel() {
        assertEquals(10, ChannelNavigation.adjacentId(channels, 30, 1))
    }

    @Test
    fun previous_wrapsBeforeFirstChannel() {
        assertEquals(30, ChannelNavigation.adjacentId(channels, 10, -1))
    }

    @Test
    fun staleCurrentChannel_fallsBackToFirstCurrentChannel() {
        assertEquals(10, ChannelNavigation.adjacentId(channels, 99, 1))
        assertEquals(10, ChannelNavigation.adjacentId(channels, 99, -1))
    }

    @Test
    fun emptyChannelList_hasNoAdjacentChannel() {
        assertNull(ChannelNavigation.adjacentId(emptyList(), 10, 1))
    }

    @Test
    fun channelKeys_mapToNavigationDirection() {
        assertEquals(1, ChannelNavigation.directionForKeyCode(KeyEvent.KEYCODE_CHANNEL_UP))
        assertEquals(-1, ChannelNavigation.directionForKeyCode(KeyEvent.KEYCODE_CHANNEL_DOWN))
    }

    @Test
    fun unrelatedKey_hasNoNavigationDirection() {
        assertNull(ChannelNavigation.directionForKeyCode(KeyEvent.KEYCODE_DPAD_UP))
    }
}
