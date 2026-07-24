package cz.preclikos.tvhstream.repositories

internal data class ChannelMetadata(
    val id: Int,
    val name: String,
    val number: Int?,
    val icon: String?
)

internal class ChannelSnapshotStore {
    private val channels = linkedMapOf<Int, ChannelMetadata>()
    private var initialSyncCompleted = false

    val size: Int
        get() = channels.size

    fun reset() {
        channels.clear()
        initialSyncCompleted = false
    }

    operator fun get(id: Int): ChannelMetadata? = channels[id]

    fun upsert(channel: ChannelMetadata): List<ChannelMetadata>? {
        channels[channel.id] = channel
        return snapshotIfReady()
    }

    fun delete(id: Int): List<ChannelMetadata>? {
        channels.remove(id)
        return snapshotIfReady()
    }

    fun completeInitialSync(): List<ChannelMetadata> {
        initialSyncCompleted = true
        return snapshot()
    }

    fun isEmpty(): Boolean = channels.isEmpty()

    fun ids(): List<Int> = channels.keys.toList()

    fun snapshot(): List<ChannelMetadata> = channels.values.sortedWith(channelComparator)

    private fun snapshotIfReady(): List<ChannelMetadata>? =
        if (initialSyncCompleted) snapshot() else null

    private companion object {
        val channelComparator = compareBy<ChannelMetadata>(
            { it.number == null },
            { it.number ?: Int.MAX_VALUE },
            { it.name.lowercase() },
            { it.id }
        )
    }
}
