package com.otis.edgereader.core.model

data class Chapter(
    val id: String,
    val title: String,
    val text: String,
) {
    init {
        require(id.isNotBlank()) { "Chapter id must not be blank" }
    }
}

data class Book(
    val id: String,
    val title: String,
    val chapters: List<Chapter>,
) {
    init {
        require(id.isNotBlank()) { "Book id must not be blank" }
        require(chapters.isNotEmpty()) { "Book must contain at least one chapter" }
    }

    val lastChapterIndex: Int get() = chapters.lastIndex

    fun chapter(index: Int): Chapter = chapters[index.coerceIn(0, lastChapterIndex)]
}
