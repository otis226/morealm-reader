package com.otis.edgereader.core.epub

import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

interface EpubArchive {
    fun read(path: String): ByteArray?
}

class ZipBytesEpubArchive(bytes: ByteArray) : EpubArchive {
    private val entries = LinkedHashMap<String, ByteArray>()

    init {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    val normalized = EpubPath.normalize(entry.name)
                    entries[normalized] = zip.readBytes()
                }
                zip.closeEntry()
            }
        }
    }

    override fun read(path: String): ByteArray? = entries[EpubPath.normalize(path)]
}

/**
 * File-backed archive for Android imports. Only the requested EPUB entry is read
 * into memory, so a large book does not require loading the entire ZIP at once.
 */
class ZipFileEpubArchive(file: File) : EpubArchive, AutoCloseable {
    private val zip = ZipFile(file)
    private val entriesByNormalizedPath = zip.entries().asSequence()
        .filterNot { it.isDirectory }
        .associateBy { EpubPath.normalize(it.name) }

    override fun read(path: String): ByteArray? {
        val entry = entriesByNormalizedPath[EpubPath.normalize(path)] ?: return null
        return zip.getInputStream(entry).use { it.readBytes() }
    }

    override fun close() {
        zip.close()
    }
}

internal object EpubPath {
    fun normalize(path: String): String {
        val stack = ArrayDeque<String>()
        path.replace('\\', '/').split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.addLast(part)
            }
        }
        return stack.joinToString("/")
    }

    fun resolve(baseFile: String, relative: String): String {
        val baseDir = normalize(baseFile).substringBeforeLast('/', "")
        val cleanRelative = relative.substringBefore('#').substringBefore('?')
        return normalize(if (baseDir.isBlank()) cleanRelative else "$baseDir/$cleanRelative")
    }
}
