package cz.preclikos.tvhstream.core

import cz.preclikos.tvhstream.htsp.ConnectionState

/** What a channel-based screen should show instead of an empty list. */
enum class ChannelsEmptyState {
    /** Channels are available – render the normal content. */
    NONE,

    /** Connecting or syncing – show a progress indicator. */
    LOADING,

    /** Not connected (no/invalid server, or the server has no channels) – point to Settings. */
    NO_CHANNELS;

    companion object {
        fun resolve(hasChannels: Boolean, connection: ConnectionState): ChannelsEmptyState = when {
            hasChannels -> NONE
            connection is ConnectionState.Connecting -> LOADING
            // Connected but the list is still empty: the initial sync is still running.
            connection is ConnectionState.Connected -> LOADING
            else -> NO_CHANNELS
        }
    }
}
