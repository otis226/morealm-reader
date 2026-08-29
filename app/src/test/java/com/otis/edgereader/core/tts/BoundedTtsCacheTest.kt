package com.otis.edgereader.core.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoundedTtsCacheTest {
    @Test
    fun evictsLeastRecentlyUsedEntryByCount() {
        val cache = BoundedTtsCache(maxEntries = 2, maxAudioBytes = 1_000)
        val a = key("a")
        val b = key("b")
        val c = key("c")
        cache.put(a, audio(10))
        cache.put(b, audio(10))
        cache.get(a) // a becomes most recently used
        cache.put(c, audio(10))

        assertNull(cache.get(b))
        assertEquals(2, cache.entryCount())
        assertEquals(20L, cache.byteCount())
    }

    @Test
    fun evictsUntilByteBudgetIsRespected() {
        val cache = BoundedTtsCache(maxEntries = 10, maxAudioBytes = 15)
        cache.put(key("a"), audio(10))
        cache.put(key("b"), audio(10))
        assertEquals(1, cache.entryCount())
        assertEquals(10L, cache.byteCount())
    }

    private fun key(text: String) = TtsCacheKey(text, "voice", 1f, 0)
    private fun audio(size: Int) = TtsAudio(ByteArray(size), emptyList(), 1L)
}
