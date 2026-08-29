package com.otis.edgereader.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceChunkerTest {
    @Test
    fun chunksCoverTextWithoutOverlapOrGap() {
        val text = buildString {
            repeat(250) { i -> append("Câu số $i đi qua một sân ga rất yên tĩnh. ") }
        }
        val chunks = mutableListOf<TextChunk>()
        var offset = 0
        while (true) {
            val chunk = SentenceChunker.nextChunk(text, offset, targetChars = 180, maxChars = 280) ?: break
            chunks += chunk
            offset = chunk.endExclusive
        }

        assertTrue(chunks.size > 5)
        assertEquals(text, chunks.joinToString("") { it.text })
        chunks.zipWithNext().forEach { (a, b) -> assertEquals(a.endExclusive, b.start) }
        assertTrue(chunks.all { it.text.length <= 280 || it.endExclusive == text.length })
    }

    @Test
    fun longTextWithoutPunctuationIsHardBounded() {
        val text = "x".repeat(10_000)
        val chunk = SentenceChunker.nextChunk(text, 0, targetChars = 300, maxChars = 500)!!
        assertEquals(0, chunk.start)
        assertEquals(500, chunk.endExclusive)
        assertEquals(500, chunk.text.length)
    }

    @Test
    fun seekStartsAtSentenceBoundaryAndUsesShortFirstChunk() {
        val text = "Một câu đầu tiên. Câu thứ hai khá dài để thử seek. Câu thứ ba kết thúc."
        val insideSecond = text.indexOf("khá")
        val chunk = SentenceChunker.firstChunkAfterSeek(text, insideSecond)!!
        assertEquals(text.indexOf("Câu thứ hai"), chunk.start)
        assertTrue(chunk.text.startsWith("Câu thứ hai"))
    }
}
