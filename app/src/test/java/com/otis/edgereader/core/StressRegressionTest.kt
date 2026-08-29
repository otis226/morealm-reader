package com.otis.edgereader.core

import com.otis.edgereader.core.model.Book
import com.otis.edgereader.core.model.Chapter
import com.otis.edgereader.core.model.ReadingPosition
import com.otis.edgereader.core.playback.PlaybackStateMachine
import com.otis.edgereader.core.text.SentenceChunker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StressRegressionTest {
    @Test
    fun millionCharacterChapterIsChunkedWithBoundedSegments() {
        val sentence = "Đêm mưa trên sân ga, đoàn tàu vẫn chưa tới. "
        val text = buildString(1_050_000) {
            while (length < 1_000_000) append(sentence)
        }
        var offset = 0
        var chunks = 0
        var total = 0
        while (true) {
            val chunk = SentenceChunker.nextChunk(text, offset) ?: break
            assertEquals(offset, chunk.start)
            assertTrue(chunk.text.length <= 1_100)
            total += chunk.text.length
            offset = chunk.endExclusive
            chunks++
        }
        assertEquals(text.length, total)
        assertEquals(text.length, offset)
        assertTrue(chunks > 500)
    }

    @Test
    fun tenThousandRapidSeeksEndAtLastRequestedPosition() {
        val text = "x".repeat(200_000)
        val book = Book("stress", "Stress", listOf(Chapter("c", "C", text)))
        val state = PlaybackStateMachine()
        state.load(book)
        state.requestPlay()

        var expected = 0
        repeat(10_000) { i ->
            expected = (i * 7919) % text.length
            state.seek(ReadingPosition(0, expected))
        }

        assertEquals(ReadingPosition(0, expected), state.snapshot().position)
    }
}
