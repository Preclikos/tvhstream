package cz.preclikos.tvhstream.core

import android.view.KeyEvent

object ChannelNavigation {
    fun directionForKeyCode(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_CHANNEL_UP -> 1
        KeyEvent.KEYCODE_CHANNEL_DOWN -> -1
        else -> null
    }

    fun adjacentId(
        orderedIds: List<Int>,
        currentId: Int,
        direction: Int,
    ): Int? {
        if (orderedIds.isEmpty()) return null

        val currentIndex = orderedIds.indexOf(currentId)
        if (currentIndex < 0) return orderedIds.first()

        val offset = if (direction < 0) -1 else 1
        return orderedIds[Math.floorMod(currentIndex + offset, orderedIds.size)]
    }
}
