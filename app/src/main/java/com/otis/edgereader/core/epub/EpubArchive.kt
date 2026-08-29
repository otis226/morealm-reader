package com.otis.edgereader.core.epub

import java.io.ByteArrayInputStream
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
