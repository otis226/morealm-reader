package com.otis.edgereader.core.tts

class FakeTtsEngine : TtsEngine {
    private val cancelled = mutableSetOf<Long>()
    private var allCancelled = false
    var synthCount: Int = 0
        private set

    override fun synthesize(request: TtsRequest): TtsAudio {
        check(!allCancelled && request.generation !in cancelled) { "generation cancelled" }
        synthCount += 1
        val words = request.text.split(Regex("\\s+")).filter { it.isNotBlank() }
        val boundaries = words.mapIndexed { index, word ->
            WordBoundary(word, offsetMs = index * 250L, durationMs = 180L)
        }
        return TtsAudio(
            encodedAudio = request.text.encodeToByteArray(),
            boundaries = boundaries,
            generation = request.generation,
        )
    }

    override fun cancelGeneration(generation: Long) {
        cancelled += generation
    }

    override fun cancelAll() {
        allCancelled = true
    }
}
