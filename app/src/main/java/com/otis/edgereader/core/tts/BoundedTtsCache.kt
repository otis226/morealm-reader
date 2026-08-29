package com.otis.edgereader.core.tts

import java.util.LinkedHashMap

data class TtsCacheKey(
    val text: String,
    val voice: String,
    val speed: Float,
    val pitchHz: Int,
)

class BoundedTtsCache(
    private val maxEntries: Int = 32,
    private val maxAudioBytes: Long = 12L * 1024L * 1024L,
) {
    init {
        require(maxEntries > 0)
        require(maxAudioBytes > 0)
    }

    private val entries = LinkedHashMap<TtsCacheKey, TtsAudio>(16, 0.75f, true)
    private var audioBytes: Long = 0

    @Synchronized
    fun get(key: TtsCacheKey): TtsAudio? = entries[key]

    @Synchronized
    fun put(key: TtsCacheKey, value: TtsAudio) {
        val cacheValue = value.copy(generation = 0L)
        entries.remove(key)?.let { audioBytes -= it.encodedAudio.size }
        entries[key] = cacheValue
        audioBytes += cacheValue.encodedAudio.size
        evictIfNeeded()
    }

    @Synchronized
    fun clear() {
        entries.clear()
        audioBytes = 0
    }

    @Synchronized
    fun entryCount(): Int = entries.size

    @Synchronized
    fun byteCount(): Long = audioBytes

    private fun evictIfNeeded() {
        val iterator = entries.entries.iterator()
        while ((entries.size > maxEntries || audioBytes > maxAudioBytes) && iterator.hasNext()) {
            val eldest = iterator.next()
            audioBytes -= eldest.value.encodedAudio.size
            iterator.remove()
        }
    }
}
