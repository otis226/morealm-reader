package com.otis.edgereader.core.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SynthesisCoordinatorTest {
    @Test
    fun rapidSeekOnlyAcceptsNewestGenerationEvenIfOldEngineWorkFinishesLater() {
        val engine = SlowEngine()
        val coordinator = SynthesisCoordinator(engine)
        val accepted = CopyOnWriteArrayList<String>()
        val errors = CopyOnWriteArrayList<Throwable>()
        val newestAccepted = CountDownLatch(1)

        fun submit(label: String, generation: Long) {
            coordinator.submit(
                generation = generation,
                spec = SynthesisSpec(label, "voice", 1f, 0),
                onAccepted = {
                    accepted += it.encodedAudio.decodeToString()
                    if (label == "20%") newestAccepted.countDown()
                },
                onError = { errors += it },
            )
        }

        val g10 = coordinator.beginGeneration()
        submit("10%", g10)
        Thread.sleep(10)
        val g80 = coordinator.beginGeneration()
        submit("80%", g80)
        Thread.sleep(10)
        val g20 = coordinator.beginGeneration()
        submit("20%", g20)

        assertTrue(newestAccepted.await(2, TimeUnit.SECONDS))
        Thread.sleep(300) // allow stale work to finish; callbacks must still be suppressed
        assertEquals(listOf("20%"), accepted.toList())
        assertTrue(errors.isEmpty())
        coordinator.close()
    }

    @Test
    fun cacheReusesAudioAcrossGenerationsWithoutResynthesis() {
        val engine = CountingEngine()
        val coordinator = SynthesisCoordinator(engine)
        val spec = SynthesisSpec("same text", "voice", 1f, -40)
        val firstDone = CountDownLatch(1)

        val g1 = coordinator.beginGeneration()
        coordinator.submit(g1, spec, { firstDone.countDown() }, { throw it })
        assertTrue(firstDone.await(1, TimeUnit.SECONDS))

        val generations = mutableListOf<Long>()
        val g2 = coordinator.beginGeneration()
        coordinator.submit(g2, spec, { generations += it.generation }, { throw it })

        assertEquals(1, engine.calls.get())
        assertEquals(listOf(g2), generations)
        coordinator.close()
    }

    private class SlowEngine : TtsEngine {
        override fun synthesize(request: TtsRequest): TtsAudio {
            val delay = when (request.text) {
                "10%" -> 220L
                "80%" -> 160L
                else -> 30L
            }
            Thread.sleep(delay)
            return TtsAudio(request.text.encodeToByteArray(), emptyList(), request.generation)
        }

        // Intentionally ignore cancellation to prove coordinator stale-result protection.
        override fun cancelGeneration(generation: Long) = Unit
        override fun cancelAll() = Unit
    }

    private class CountingEngine : TtsEngine {
        val calls = AtomicInteger(0)
        override fun synthesize(request: TtsRequest): TtsAudio {
            calls.incrementAndGet()
            return TtsAudio(request.text.encodeToByteArray(), emptyList(), request.generation)
        }
        override fun cancelGeneration(generation: Long) = Unit
        override fun cancelAll() = Unit
    }
}
