package com.otis.edgereader.core.model

data class ReadingPosition(
    val chapterIndex: Int,
    val charOffset: Int,
) {
    fun normalized(book: Book): ReadingPosition {
        val chapter = chapterIndex.coerceIn(0, book.lastChapterIndex)
        val offset = charOffset.coerceIn(0, book.chapters[chapter].text.length)
        return ReadingPosition(chapter, offset)
    }

    companion object {
        val START = ReadingPosition(0, 0)
    }
}
