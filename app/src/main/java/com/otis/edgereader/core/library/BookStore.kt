package com.otis.edgereader.core.library

import com.otis.edgereader.core.model.Book

data class BookSummary(
    val id: String,
    val title: String,
    val chapterCount: Int,
)

interface BookStore {
    fun save(book: Book)
    fun load(bookId: String): Book?
    fun list(): List<BookSummary>
    fun remove(bookId: String)
}
