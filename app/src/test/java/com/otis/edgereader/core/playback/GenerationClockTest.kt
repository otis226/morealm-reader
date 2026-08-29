package com.otis.edgereader.core.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationClockTest {
    @Test
    fun newerGenerationInvalidatesOlderWork() {
        val clock = GenerationClock()
        val first = clock.next()
        assertTrue(clock.isCurrent(first))
        val second = clock.next()
        assertFalse(clock.isCurrent(first))
        assertTrue(clock.isCurrent(second))
    }
}
