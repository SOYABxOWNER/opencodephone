package io.github.oustery.opencodephone.core.opencode

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessageRefreshTest {
    @Test fun `continuous events still refresh and a final pending event is delivered`() = runTest {
        val events = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
        val times = mutableListOf<Long>()
        val job = launch { events.throttledInvalidations().collect { times += currentTime } }
        try {
            runCurrent()
            repeat(40) {
                events.emit(Unit)
                runCurrent()
                advanceTimeBy(50)
                runCurrent()
            }
            val duringStream = times.size
            assertTrue(duringStream >= 5)
            assertTrue(duringStream <= 10)
            advanceTimeBy(400)
            runCurrent()
            assertTrue(times.size > duringStream)
            assertEquals(200L, times.first())
            assertTrue(times.zipWithNext().all { (a, b) -> b - a >= 200 })
        } finally { job.cancel() }
    }

    @Test fun `cancellation during throttle delay never starts a refresh`() = runTest {
        val events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        var calls = 0
        val job = launch { events.throttledInvalidations().collect { calls++ } }
        runCurrent()
        events.emit(Unit)
        runCurrent()
        advanceTimeBy(100)
        job.cancel()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(0, calls)
    }
}
