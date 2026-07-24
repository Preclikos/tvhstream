package cz.preclikos.tvhstream.htsp

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class HtspEventStreamTest {

    @Test
    fun emit_doesNotDropEventsWhenTheCollectorIsSlowerThanTheProducer() = runBlocking {
        val stream = HtspEventStream(extraBufferCapacity = 1)
        val received = mutableListOf<Int>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            stream.events.take(3).collect { event ->
                delay(10)
                received += (event as HtspEvent.ServerMessage).msg.int("value")!!
            }
        }

        repeat(3) { value ->
            stream.emit(serverEvent(value))
        }
        collector.join()

        assertEquals(listOf(0, 1, 2), received)
    }

    private fun serverEvent(value: Int) = HtspEvent.ServerMessage(
        HtspMessage(
            method = "test",
            seq = null,
            fields = mapOf("value" to value)
        )
    )
}
