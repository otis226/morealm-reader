package com.otis.edgereader.core.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsEngineContractTest {
    @Test
    fun fakePreservesGenerationAndProvidesWordBoundaries() {
        val engine = FakeTtsEngine()
        val result = engine.synthesize(
            TtsRequest(
                text = "xin chào bạn",
                voice = "fake",
                speed = 1f,
                pitchHz = 0,
                generation = 7L,
            )
        )
        assertEquals(7L, result.generation)
        assertEquals(listOf("xin", "chào", "bạn"), result.boundaries.map { it.text })
        assertEquals(1, engine.synthCount)
    }

    @Test
    fun cancelledGenerationCannotSynthesize() {
        val engine = FakeTtsEngine()
        engine.cancelGeneration(2L)
        val failed = runCatching {
            engine.synthesize(TtsRequest("hello", "fake", 1f, 0, 2L))
        }.isFailure
        assertTrue(failed)
    }
}
