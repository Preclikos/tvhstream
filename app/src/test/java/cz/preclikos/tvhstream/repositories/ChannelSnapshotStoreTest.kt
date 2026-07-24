package cz.preclikos.tvhstream.repositories

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelSnapshotStoreTest {

    @Test
    fun initialSync_publishesOnlyTheCompleteSortedSnapshot() {
        val store = ChannelSnapshotStore()

        assertNull(store.upsert(channel(id = 30, name = "Thirty", number = 30)))
        assertNull(store.upsert(channel(id = 10, name = "Ten", number = 10)))
        assertNull(store.upsert(channel(id = 20, name = "Twenty", number = 20)))

        assertEquals(listOf(10, 20, 30), store.completeInitialSync().map { it.id })
    }

    @Test
    fun changesAfterInitialSync_publishUpdatedSnapshots() {
        val store = ChannelSnapshotStore()
        store.upsert(channel(id = 10, name = "Ten", number = 10))
        store.completeInitialSync()

        val added = store.upsert(channel(id = 20, name = "Twenty", number = 20))
        val deleted = store.delete(10)

        assertEquals(listOf(10, 20), added?.map { it.id })
        assertEquals(listOf(20), deleted?.map { it.id })
    }

    @Test
    fun reset_stagesTheNextConnectionUntilItsInitialSyncCompletes() {
        val store = ChannelSnapshotStore()
        store.upsert(channel(id = 10, name = "Ten", number = 10))
        store.completeInitialSync()

        store.reset()

        assertNull(store.upsert(channel(id = 40, name = "Forty", number = 40)))
        assertEquals(listOf(40), store.completeInitialSync().map { it.id })
    }

    private fun channel(id: Int, name: String, number: Int) =
        ChannelMetadata(id = id, name = name, number = number, icon = null)
}
