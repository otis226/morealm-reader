package com.otis.edgereader.core.library

import com.otis.edgereader.core.model.Book
import com.otis.edgereader.core.model.Chapter
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Small, deterministic, file-backed book store.
 *
 * The format uses 32-bit length-prefixed UTF-8 strings instead of DataOutput.writeUTF,
 * so very large chapters are supported. Writes go through a temp file and rename to
 * avoid leaving a partially-written book after process death.
 */
class FileBookStore(
    private val root: File,
) : BookStore {
    init {
        if (!root.exists() && !root.mkdirs()) {
            throw IOException("Cannot create book store directory: ${root.absolutePath}")
        }
    }

    @Synchronized
    override fun save(book: Book) {
        val target = fileFor(book.id)
        val temp = File(root, target.name + ".tmp")
        DataOutputStream(BufferedOutputStream(temp.outputStream())).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeString32(book.id)
            out.writeString32(book.title)
            out.writeInt(book.chapters.size)
            book.chapters.forEach { chapter ->
                out.writeString32(chapter.id)
                out.writeString32(chapter.title)
                out.writeString32(chapter.text)
            }
            out.flush()
        }
        if (target.exists() && !target.delete()) {
            temp.delete()
            throw IOException("Cannot replace stored book ${book.id}")
        }
        if (!temp.renameTo(target)) {
            temp.delete()
            throw IOException("Cannot commit stored book ${book.id}")
        }
    }

    @Synchronized
    override fun load(bookId: String): Book? {
        val file = fileFor(bookId)
        if (!file.isFile) return null
        return runCatching { decode(file) }.getOrNull()
    }

    @Synchronized
    override fun list(): List<BookSummary> = root.listFiles()
        .orEmpty()
        .asSequence()
        .filter { it.isFile && it.extension == EXTENSION }
        .mapNotNull { file ->
            runCatching { decode(file) }
                .getOrNull()
                ?.let { BookSummary(it.id, it.title, it.chapters.size) }
        }
        .sortedBy { it.title.lowercase() }
        .toList()

    @Synchronized
    override fun remove(bookId: String) {
        fileFor(bookId).delete()
    }

    private fun decode(file: File): Book {
        DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
            require(input.readInt() == MAGIC) { "Invalid book file" }
            require(input.readInt() == VERSION) { "Unsupported book format" }
            val id = input.readString32()
            val title = input.readString32()
            val chapterCount = input.readInt()
            require(chapterCount in 1..MAX_CHAPTERS) { "Invalid chapter count" }
            val chapters = ArrayList<Chapter>(chapterCount)
            repeat(chapterCount) {
                chapters += Chapter(
                    id = input.readString32(),
                    title = input.readString32(),
                    text = input.readString32(),
                )
            }
            return Book(id = id, title = title, chapters = chapters)
        }
    }

    private fun fileFor(bookId: String): File = File(root, sha256(bookId) + ".$EXTENSION")

    private fun DataOutputStream.writeString32(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "String too large for book store" }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString32(): String {
        val length = readInt()
        require(length in 0..MAX_STRING_BYTES) { "Invalid string length" }
        val bytes = ByteArray(length)
        readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val MAGIC = 0x53525231 // SRR1
        private const val VERSION = 1
        private const val EXTENSION = "storybook"
        private const val MAX_CHAPTERS = 20_000
        private const val MAX_STRING_BYTES = 64 * 1024 * 1024
    }
}
