package com.otis.edgereader

import android.content.Context
import android.net.Uri
import android.text.Html
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

object EpubParser {
    data class Chapter(
        val title: String,
        val text: String,
        val sourcePath: String,
    )

    data class Result(
        val title: String?,
        val chapters: List<Chapter>,
    ) {
        val totalCharacters: Int get() = chapters.sumOf { it.text.length }
    }

    private data class ManifestItem(
        val href: String,
        val mediaType: String?,
        val properties: String?,
    )

    fun parse(context: Context, uri: Uri): Result {
        val entries = LinkedHashMap<String, ByteArray>()
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory && shouldKeep(entry.name)) {
                        entries[normalize(entry.name)] = zip.readBytes()
                    }
                    zip.closeEntry()
                }
            }
        } ?: error("Không mở được EPUB")

        val container = entries["META-INF/container.xml"]?.toString(Charsets.UTF_8)
            ?: error("EPUB thiếu META-INF/container.xml")
        val opfPath = Regex("full-path\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
            .find(container)?.groupValues?.getOrNull(1)?.let(::normalize)
            ?: error("Không tìm thấy package OPF")
        val opfBytes = entries[opfPath] ?: error("Không đọc được OPF: $opfPath")

        val manifest = LinkedHashMap<String, ManifestItem>()
        val spine = ArrayList<String>()
        var bookTitle: String? = null
        val parser = newParser(opfBytes)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name.substringAfter(':').lowercase()) {
                    "item" -> {
                        val id = parser.getAttributeValue(null, "id")
                        val href = parser.getAttributeValue(null, "href")
                        if (!id.isNullOrBlank() && !href.isNullOrBlank()) {
                            manifest[id] = ManifestItem(
                                href = href,
                                mediaType = parser.getAttributeValue(null, "media-type"),
                                properties = parser.getAttributeValue(null, "properties"),
                            )
                        }
                    }
                    "itemref" -> parser.getAttributeValue(null, "idref")?.let(spine::add)
                    "title" -> if (bookTitle == null) {
                        bookTitle = parser.nextText().trim().ifBlank { null }
                    }
                }
            }
            event = parser.next()
        }

        val baseDir = opfPath.substringBeforeLast('/', "")
        val chapters = ArrayList<Chapter>()
        var fallbackIndex = 1
        for (id in spine) {
            val item = manifest[id] ?: continue
            if (item.properties.orEmpty().split(Regex("\\s+")).any { it.equals("nav", ignoreCase = true) }) {
                continue
            }
            val media = item.mediaType.orEmpty().lowercase()
            if (media.isNotBlank() && !media.contains("html") && !media.contains("xhtml")) continue

            val path = resolve(baseDir, item.href.substringBefore('#'))
            val bytes = entries[path] ?: continue
            val html = bytes.toString(Charsets.UTF_8)
            val plain = htmlToText(html)
            if (plain.isBlank()) continue

            val extractedTitle = extractChapterTitle(html, plain)
            val title = extractedTitle ?: "Chương ${fallbackIndex++}"

            // Một số EPUB có spine chia quá nhỏ thành nhiều file vài chữ. Không bỏ chúng hoàn toàn,
            // nhưng gộp mảnh cực ngắn vào chương trước để mục lục dễ dùng hơn.
            if (plain.length < 90 && chapters.isNotEmpty()) {
                val previous = chapters.removeAt(chapters.lastIndex)
                chapters.add(previous.copy(text = previous.text + "\n\n" + plain))
            } else {
                chapters.add(Chapter(title = title, text = plain, sourcePath = path))
            }
        }

        if (chapters.isEmpty()) error("Không lấy được nội dung đọc từ EPUB")
        return Result(bookTitle, chapters)
    }

    private fun htmlToText(html: String): String = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString()
        .replace('\u00A0', ' ')
        .replace(Regex("[ \\t]+\\n"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

    private fun extractChapterTitle(html: String, plain: String): String? {
        val heading = Regex(
            "<(h1|h2|h3)[^>]*>(.*?)</\\1>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).find(html)?.groupValues?.getOrNull(2)
            ?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString().trim() }
            ?.takeIf { it.length in 1..120 }
        if (!heading.isNullOrBlank()) return heading

        val titleTag = Regex(
            "<title[^>]*>(.*?)</title>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).find(html)?.groupValues?.getOrNull(1)
            ?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString().trim() }
            ?.takeIf { it.length in 1..120 }
        if (!titleTag.isNullOrBlank()) return titleTag

        return plain.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.length in 2..100 }
    }

    private fun shouldKeep(name: String): Boolean {
        val n = name.lowercase()
        return n == "meta-inf/container.xml" || n.endsWith(".opf") || n.endsWith(".xhtml") ||
            n.endsWith(".html") || n.endsWith(".htm") || n.endsWith(".xml")
    }

    private fun newParser(bytes: ByteArray): XmlPullParser = XmlPullParserFactory.newInstance().newPullParser().apply {
        setInput(ByteArrayInputStream(bytes), "UTF-8")
    }

    private fun normalize(path: String): String {
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

    private fun resolve(baseDir: String, relative: String): String = normalize(
        if (baseDir.isBlank()) relative else "$baseDir/$relative"
    )
}
