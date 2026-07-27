package cz.preclikos.tvhstream.htsp

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

internal class HtspEventStream(extraBufferCapacity: Int = 256) {
    private val mutableEvents = MutableSharedFlow<HtspEvent>(
        extraBufferCapacity = extraBufferCapacity
    )

    val events: SharedFlow<HtspEvent> = mutableEvents.asSharedFlow()

    suspend fun emit(event: HtspEvent) {
        mutableEvents.emit(event)
    }
}
