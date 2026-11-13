package cz.preclikos.tvhstream.core

import android.view.KeyEvent
import cz.preclikos.tvhstream.htsp.ChannelUi

/**
 * Typing a channel number on the remote while watching (issue #15). Matches the
 * TVHeadend channel NUMBER (may be non-contiguous), not the position in the list.
 */
data class ChannelNumberEntry(val digits: String = "") {

    val isActive: Boolean get() = digits.isNotEmpty()

    val number: Int? get() = digits.toIntOrNull()

    /** Adds a digit; once [MAX_DIGITS] are typed the next digit starts a new number. */
    fun append(digit: Int): ChannelNumberEntry {
        require(digit in 0..9)
        val base = if (digits.length >= MAX_DIGITS) "" else digits
        return ChannelNumberEntry(base + digit)
    }

    fun findChannel(channels: List<ChannelUi>): ChannelUi? {
        val n = number ?: return null
        return channels.firstOrNull { it.number == n }
    }

    companion object {
        const val MAX_DIGITS = 4

        /** Tune this long after the last digit (OK tunes immediately). */
        const val COMMIT_DELAY_MS = 1_500L

        fun digitForKeyCode(keyCode: Int): Int? = when (keyCode) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> keyCode - KeyEvent.KEYCODE_0
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> keyCode - KeyEvent.KEYCODE_NUMPAD_0
            else -> null
        }
    }
}
