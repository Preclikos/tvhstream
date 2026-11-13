package cz.preclikos.tvhstream.core

import cz.preclikos.tvhstream.htsp.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelsEmptyStateTest {

    @Test
    fun withChannels_showsContent_regardlessOfConnection() {
        listOf(
            ConnectionState.Disconnected,
            ConnectionState.Connecting("tvh", 9982),
            ConnectionState.Connected("tvh", 9982, 34),
            ConnectionState.Error(RuntimeException()),
        ).forEach {
            assertEquals(ChannelsEmptyState.NONE, ChannelsEmptyState.resolve(true, it))
        }
    }

    @Test
    fun connecting_showsLoading() {
        assertEquals(
            ChannelsEmptyState.LOADING,
            ChannelsEmptyState.resolve(false, ConnectionState.Connecting("tvh", 9982))
        )
    }

    @Test
    fun connectedButStillSyncing_showsLoading() {
        assertEquals(
            ChannelsEmptyState.LOADING,
            ChannelsEmptyState.resolve(false, ConnectionState.Connected("tvh", 9982, 34))
        )
    }

    @Test
    fun disconnectedOrError_pointsToSettings() {
        assertEquals(
            ChannelsEmptyState.NO_CHANNELS,
            ChannelsEmptyState.resolve(false, ConnectionState.Disconnected)
        )
        assertEquals(
            ChannelsEmptyState.NO_CHANNELS,
            ChannelsEmptyState.resolve(false, ConnectionState.Error(RuntimeException()))
        )
    }
}
