package com.otis.edgereader.core.tts

data class TtsRequest(
    val text: String,
    val voice: String,
    val speed: Float,
    val pitchHz: Int,
    val generation: Long,
)

data class WordBoundary(
    val text: String,
    val offsetMs: Long,
    val durationMs: Long,
)

data class TtsAudio(
    val encodedAudio: ByteArray,
    val boundaries: List<WordBoundary>,
    val generation: Long,
)

/**
 * Blocking synthesis boundary. The playback coordinator owns threading,
 * cancellation and generation validity; engines only synthesize one request.
 */
interface TtsEngine {
    fun synthesize(request: TtsRequest): TtsAudio
    fun cancelGeneration(generation: Long)
    fun cancelAll()
}
