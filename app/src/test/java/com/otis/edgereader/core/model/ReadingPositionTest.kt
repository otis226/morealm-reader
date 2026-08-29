package com.otis.edgereader.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingPositionTest {
    private val book = Book(
        id = "book",
        title = "Book",
        chapters = listOf(
            Chapter("c1", "One", "abc"),
            Chapter("c2", "Two", "12345"),
        ),
    )

    @Test
    fun clampsChapterAndOffset() {
        assertEquals(ReadingPosition(1, 5), ReadingPosition(99, 99).normalized(book))
        assertEquals(ReadingPosition(0, 0), ReadingPosition(-2, -1).normalized(book))
    }
}
