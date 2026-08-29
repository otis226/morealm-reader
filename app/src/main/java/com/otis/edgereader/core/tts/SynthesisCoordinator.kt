package com.otis.edgereader.core.tts

import com.otis.edgereader.core.playback.GenerationClock
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class SynthesisSpec(
    val text: String,
    val voice: String,
    val speed: Float,
    val pitchHz: Int,
)

class SynthesisCoordinator(
    private val engine: TtsEngine,
    private val cache: BoundedTtsCache = BoundedTtsCache(),
    private val executor: ExecutorService = Executors.newFixedThreadPool(3),
) : AutoCloseable {
    private val generations = GenerationClock()

    fun beginGeneration(): Long {
        val previous = generations.current()
        val next = generations.next()
        if (previous > 0) engine.cancelGeneration(previous)
        return next
    }

    fun currentGeneration(): Long = generations.current()

    fun submit(
        generation: Long,
        spec: SynthesisSpec,
        onAccepted: (TtsAudio) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        if (!generations.isCurrent(generation)) return
        val key = TtsCacheKey(spec.text, spec.voice, spec.speed, spec.pitchHz)
        cache.get(key)?.let { cached ->
            if (generations.isCurrent(generation)) {
                onAccepted(cached.copy(generation = generation))
            }
            return
        }

        executor.execute {
            if (!generations.isCurrent(generation)) return@execute
            try {
                val result = engine.synthesize(
                    TtsRequest(
                        text = spec.text,
                        voice = spec.voice,
                        speed = spec.speed,
                        pitchHz = spec.pitchHz,
                        generation = generation,
                    )
                )
                cache.put(key, result)
                if (generations.isCurrent(generation)) {
                    onAccepted(result.copy(generation = generation))
                }
            } catch (t: Throwable) {
                if (generations.isCurrent(generation) && t !is TtsCancelledException) {
                    onError(t)
                }
            }
        }
    }

    fun invalidateCurrentGeneration(): Long {
        val previous = generations.current()
        val next = generations.next()
        if (previous > 0) engine.cancelGeneration(previous)
        return next
    }

    override fun close() {
        invalidateCurrentGeneration()
        engine.cancelAll()
        executor.shutdownNow()
        cache.clear()
    }
}

class TtsCancelledException(message: String = "TTS generation cancelled") : RuntimeException(message)
