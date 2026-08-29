package com.otis.edgereader.core.library

import com.otis.edgereader.core.model.Book
import com.otis.edgereader.core.model.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class FileBookStoreTest {
    @Test
    fun roundTripsLargeVietnameseBook() {
        val root = Files.createTempDirectory("story-reader-books").toFile()
        try {
            val longText = buildString(140_000) {
                append("Đêm xuống rất chậm. ")
                while (length < 140_000) append("Gió lùa qua ga tàu cũ, tiếng mưa rơi đều. ")
            }
            val book = Book(
                id = "ga-tau-cuoi",
                title = "Ga Tàu Cuối Cùng",
                chapters = listOf(
                    Chapter("c1", "Chương 1 · Sân ga", "Một câu chuyện tiếng Việt có dấu."),
                    Chapter("c2", "Chương 2 · Đêm mưa", longText),
                ),
            )
            val store = FileBookStore(root)

            store.save(book)

            assertEquals(book, store.load(book.id))
            assertEquals(
                listOf(BookSummary(book.id, book.title, 2)),
                store.list(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun overwriteIsAtomicAtLogicalLevelAndRemoveWorks() {
        val root = Files.createTempDirectory("story-reader-books").toFile()
        try {
            val store = FileBookStore(root)
            val first = Book("id", "Old", listOf(Chapter("1", "One", "A")))
            val second = Book(
                "id",
                "New",
                listOf(
                    Chapter("1", "One", "B"),
                    Chapter("2", "Two", "C"),
                ),
            )

            store.save(first)
            store.save(second)

            assertEquals(second, store.load("id"))
            assertEquals(listOf(BookSummary("id", "New", 2)), store.list())

            store.remove("id")
            assertNull(store.load("id"))
            assertEquals(emptyList<BookSummary>(), store.list())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun corruptedFilesAreIgnoredByListing() {
        val root = Files.createTempDirectory("story-reader-books").toFile()
        try {
            root.resolve("broken.storybook").writeBytes(byteArrayOf(1, 2, 3, 4))
            val store = FileBookStore(root)
            assertEquals(emptyList<BookSummary>(), store.list())
        } finally {
            root.deleteRecursively()
        }
    }
}
