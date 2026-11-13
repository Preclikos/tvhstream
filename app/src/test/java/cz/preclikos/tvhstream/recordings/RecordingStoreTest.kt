package cz.preclikos.tvhstream.recordings

import cz.preclikos.tvhstream.htsp.HtspMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingStoreTest {

    private fun msg(method: String, vararg fields: Pair<String, Any?>) =
        HtspMessage(method = method, seq = null, fields = mapOf(*fields))

    private fun add(id: Int, title: String, start: Long, state: String, vararg extra: Pair<String, Any?>) =
        msg(
            "dvrEntryAdd",
            "id" to id, "title" to title, "channelName" to "ČT1",
            "start" to start, "stop" to start + 3600, "state" to state, *extra
        )

    @Test
    fun onlyFinishedAndInProgressAreListed_newestFirst() {
        val store = RecordingStore()
        store.upsert(add(1, "Old", 1_000, "completed"))
        store.upsert(add(2, "New", 5_000, "completed"))
        store.upsert(add(3, "Now", 9_000, "recording"))
        store.upsert(add(4, "Later", 20_000, "scheduled"))
        store.upsert(add(5, "Missed", 3_000, "missed"))

        assertEquals(listOf(3, 2, 1), store.playable().map { it.id })
    }

    @Test
    fun completedWithMissingFile_isNotPlayable_butStreamErrorsAre() {
        val store = RecordingStore()
        store.upsert(add(1, "Gone", 1_000, "completed", "error" to "File missing"))
        store.upsert(add(2, "Glitchy", 2_000, "completed", "error" to "Too many errors"))
        store.upsert(add(3, "Empty", 3_000, "completed", "dataSize" to 0L))

        assertEquals(listOf(2), store.playable().map { it.id })
    }

    @Test
    fun statsOnlyUpdate_keepsTitleAndChannel() {
        val store = RecordingStore()
        store.upsert(add(7, "Film", 1_000, "recording"))
        // HTSP > 25 sends stats-only dvrEntryUpdate: id + state + dataSize.
        store.upsert(msg("dvrEntryUpdate", "id" to 7, "state" to "completed", "dataSize" to 123L))

        val r = store.playable().single()
        assertEquals("Film", r.title)
        assertEquals("ČT1", r.channelName)
        assertEquals("completed", r.state)
        assertEquals(123L, r.dataSize)
        assertEquals(3600L, r.durationSec)
        assertEquals("/dvr/7", r.filePath)
    }

    @Test
    fun errorClearedByFullUpdate() {
        val store = RecordingStore()
        store.upsert(add(1, "X", 1_000, "completed", "error" to "File missing"))
        assertTrue(store.playable().isEmpty())
        store.upsert(msg("dvrEntryUpdate", "id" to 1, "state" to "completed"))
        assertEquals(1, store.playable().size)
    }

    @Test
    fun delete_and_clear() {
        val store = RecordingStore()
        store.upsert(add(1, "A", 1_000, "completed"))
        store.upsert(add(2, "B", 2_000, "completed"))

        assertTrue(store.delete(msg("dvrEntryDelete", "id" to 1)))
        assertFalse(store.delete(msg("dvrEntryDelete", "id" to 99)))
        assertEquals(listOf(2), store.playable().map { it.id })

        store.clear()
        assertTrue(store.playable().isEmpty())
    }

    @Test
    fun messageWithoutId_isIgnored() {
        val store = RecordingStore()
        assertFalse(store.upsert(msg("dvrEntryAdd", "title" to "No id")))
        assertTrue(store.playable().isEmpty())
    }
}
