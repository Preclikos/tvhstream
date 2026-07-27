package cz.preclikos.tvhstream.core

object LastPlayedChannelPolicy {
    fun resolve(orderedIds: List<Int>, persistedId: Int?, enabled: Boolean): Int? {
        if (orderedIds.isEmpty()) return null
        if (!enabled) return orderedIds.first()
        return persistedId?.takeIf(orderedIds::contains) ?: orderedIds.first()
    }
}
