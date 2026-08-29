package com.otis.edgereader

import android.content.Context
import android.net.Uri
import android.text.Html
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

object EpubParser {
    data class Result(val title: String?, val text: String)

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

        val manifest = LinkedHashMap<String, String>()
        val spine = ArrayList<String>()
        var title: String? = null
        val parser = newParser(opfBytes)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name.substringAfter(':').lowercase()) {
                    "item" -> {
                        val id = parser.getAttributeValue(null, "id")
                        val href = parser.getAttributeValue(null, "href")
                        if (!id.isNullOrBlank() && !href.isNullOrBlank()) manifest[id] = href
                    }
                    "itemref" -> parser.getAttributeValue(null, "idref")?.let(spine::add)
                    "title" -> if (title == null) title = parser.nextText().trim().ifBlank { null }
                }
            }
            event = parser.next()
        }

        val baseDir = opfPath.substringBeforeLast('/', "")
        val parts = ArrayList<String>()
        for (id in spine) {
            val href = manifest[id] ?: continue
            val path = resolve(baseDir, href.substringBefore('#'))
            val bytes = entries[path] ?: continue
            val html = bytes.toString(Charsets.UTF_8)
            val plain = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString()
                .replace('\u00A0', ' ')
                .replace(Regex("[ \\t]+\\n"), "\n")
                .replace(Regex("\\n{3,}"), "\n\n")
                .trim()
            if (plain.isNotBlank()) parts.add(plain)
        }

        if (parts.isEmpty()) error("Không lấy được nội dung đọc từ EPUB")
        return Result(title, parts.joinToString("\n\n"))
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
