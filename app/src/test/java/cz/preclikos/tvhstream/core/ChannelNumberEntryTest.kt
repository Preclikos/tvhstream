package cz.preclikos.tvhstream.core

import android.view.KeyEvent
import cz.preclikos.tvhstream.htsp.ChannelUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelNumberEntryTest {

    private val channels = listOf(
        ChannelUi(id = 101, name = "ČT1", icon = null, number = 1),
        ChannelUi(id = 102, name = "ČT2", icon = null, number = 2),
        ChannelUi(id = 120, name = "Nova", icon = null, number = 12),
        ChannelUi(id = 999, name = "No number", icon = null, number = null),
        ChannelUi(id = 300, name = "Sport", icon = null, number = 300),
    )

    private fun typed(vararg d: Int) = d.fold(ChannelNumberEntry()) { e, x -> e.append(x) }

    @Test
    fun empty_isInactive() {
        assertFalse(ChannelNumberEntry().isActive)
        assertNull(ChannelNumberEntry().number)
    }

    @Test
    fun digits_accumulate() {
        val e = typed(1, 2)
        assertTrue(e.isActive)
        assertEquals("12", e.digits)
        assertEquals(12, e.number)
    }

    @Test
    fun leadingZero_isKeptForDisplay_butMatchesTheNumber() {
        val e = typed(0, 2)
        assertEquals("02", e.digits)
        assertEquals(102, e.findChannel(channels)?.id)
    }

    @Test
    fun matchesChannelNumber_notListPosition() {
        // "12" is the 3rd item in the list, but its TVHeadend number is 12.
        assertEquals(120, typed(1, 2).findChannel(channels)?.id)
        // Non-contiguous numbering works.
        assertEquals(300, typed(3, 0, 0).findChannel(channels)?.id)
    }

    @Test
    fun unknownNumber_findsNothing() {
        assertNull(typed(7).findChannel(channels))
    }

    @Test
    fun fifthDigit_startsANewNumber() {
        assertEquals("5", typed(1, 2, 3, 4, 5).digits)
    }

    @Test
    fun keyCodes_mapToDigits() {
        assertEquals(0, ChannelNumberEntry.digitForKeyCode(KeyEvent.KEYCODE_0))
        assertEquals(9, ChannelNumberEntry.digitForKeyCode(KeyEvent.KEYCODE_9))
        assertEquals(4, ChannelNumberEntry.digitForKeyCode(KeyEvent.KEYCODE_NUMPAD_4))
        assertNull(ChannelNumberEntry.digitForKeyCode(KeyEvent.KEYCODE_DPAD_CENTER))
    }
}
