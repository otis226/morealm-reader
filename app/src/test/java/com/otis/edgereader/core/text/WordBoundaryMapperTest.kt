package com.otis.edgereader.core.text

import com.otis.edgereader.core.tts.WordBoundary
import org.junit.Assert.assertEquals
import org.junit.Test

class WordBoundaryMapperTest {
    @Test
    fun repeatedVietnameseWordsMapMonotonically() {
        val text = "mưa rơi, mưa rơi rất chậm"
        val mapped = WordBoundaryMapper.map(
            text,
            listOf(
                WordBoundary("mưa", 0, 100),
                WordBoundary("rơi", 120, 100),
                WordBoundary("mưa", 300, 100),
                WordBoundary("rơi", 430, 100),
                WordBoundary("rất", 600, 90),
                WordBoundary("chậm", 720, 120),
            ),
        )
        assertEquals(listOf(0, 4, 9, 13, 17, 21), mapped.map { it.charOffset })
        assertEquals(17, WordBoundaryMapper.charOffsetAtPlayback(mapped, 650))
        assertEquals(21, WordBoundaryMapper.charOffsetAtPlayback(mapped, 2_000))
    }

    @Test
    fun missingMetadataWordFallsForwardWithoutGoingBackwards() {
        val text = "Ga tàu cuối cùng"
        val mapped = WordBoundaryMapper.map(
            text,
            listOf(
                WordBoundary("Ga", 0, 10),
                WordBoundary("không-có", 20, 10),
                WordBoundary("cuối", 30, 10),
            ),
        )
        assertEquals(0, mapped[0].charOffset)
        assertEquals(2, mapped[1].charOffset)
        assertEquals(7, mapped[2].charOffset)
    }
}
