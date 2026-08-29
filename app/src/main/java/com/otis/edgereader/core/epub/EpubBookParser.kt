package com.otis.edgereader.core.epub

import com.otis.edgereader.core.model.Book
import com.otis.edgereader.core.model.Chapter
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

class EpubParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

object EpubBookParser {
    private data class ManifestItem(
        val id: String,
        val href: String,
        val mediaType: String,
        val properties: Set<String>,
    )

    fun parse(archive: EpubArchive): Book {
        try {
            val containerBytes = archive.read("META-INF/container.xml")
                ?: throw EpubParseException("EPUB thiếu META-INF/container.xml")
            val container = parseXml(containerBytes)
            val rootfile = elements(container, "rootfile").firstOrNull()
                ?: throw EpubParseException("EPUB không có rootfile")
            val opfPath = rootfile.attr("full-path").takeIf { it.isNotBlank() }
                ?.let(EpubPath::normalize)
                ?: throw EpubParseException("EPUB rootfile thiếu full-path")

            val opfBytes = archive.read(opfPath)
                ?: throw EpubParseException("Không đọc được package OPF: $opfPath")
            val opf = parseXml(opfBytes)

            val title = elements(opf, "title").firstOrNull()?.textContent?.cleanInline()
                ?.takeIf { it.isNotBlank() }
                ?: "EPUB"
            val identifier = elements(opf, "identifier").firstOrNull()?.textContent?.cleanInline()
                ?.takeIf { it.isNotBlank() }
                ?: "epub:${title.hashCode()}"

            val manifest = LinkedHashMap<String, ManifestItem>()
            elements(opf, "item").forEach { element ->
                val id = element.attr("id")
                val href = element.attr("href")
                if (id.isNotBlank() && href.isNotBlank()) {
                    manifest[id] = ManifestItem(
                        id = id,
                        href = href,
                        mediaType = element.attr("media-type"),
                        properties = element.attr("properties")
                            .split(Regex("\\s+"))
                            .filter { it.isNotBlank() }
                            .toSet(),
                    )
                }
            }

            val chapters = ArrayList<Chapter>()
            elements(opf, "itemref").forEach { itemRef ->
                if (itemRef.attr("linear").equals("no", ignoreCase = true)) return@forEach
                val item = manifest[itemRef.attr("idref")] ?: return@forEach
                if (!isReadableManifestItem(item)) return@forEach

                val chapterPath = EpubPath.resolve(opfPath, item.href)
                val chapterBytes = archive.read(chapterPath) ?: return@forEach
                val extracted = extractChapter(chapterBytes)
                if (extracted.text.isBlank()) return@forEach
                if (isLikelyCover(item, chapterPath, extracted.text)) return@forEach

                val chapterTitle = extracted.title
                    ?.takeIf { it.isNotBlank() }
                    ?: extracted.text.lineSequence().firstOrNull { it.isNotBlank() }
                    ?.take(120)
                    ?: "Chương ${chapters.size + 1}"

                chapters += Chapter(
                    id = item.id,
                    title = chapterTitle,
                    text = extracted.text,
                )
            }

            if (chapters.isEmpty()) {
                throw EpubParseException("Không tìm thấy chương đọc được trong EPUB")
            }
            return Book(id = identifier, title = title, chapters = chapters)
        } catch (e: EpubParseException) {
            throw e
        } catch (e: Exception) {
            throw EpubParseException("EPUB không hợp lệ: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    private data class ExtractedChapter(val title: String?, val text: String)

    private fun extractChapter(bytes: ByteArray): ExtractedChapter {
        val document = parseXml(bytes)
        val heading = sequenceOf("h1", "h2", "h3")
            .flatMap { elements(document, it).asSequence() }
            .map { it.textContent.cleanInline() }
            .firstOrNull { it.isNotBlank() }
        val htmlTitle = elements(document, "title").firstOrNull()?.textContent?.cleanInline()
            ?.takeIf { it.isNotBlank() }

        val body = elements(document, "body").firstOrNull() ?: document.documentElement
        val builder = StringBuilder()
        appendText(body, builder)
        val text = builder.toString().cleanReadingText()
        return ExtractedChapter(heading ?: htmlTitle, text)
    }

    private fun appendText(node: Node, out: StringBuilder) {
        when (node.nodeType) {
            Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> out.append(node.nodeValue ?: "")
            Node.ELEMENT_NODE -> {
                val tag = localName(node)
                if (tag in setOf("script", "style", "svg", "nav")) return
                if (tag == "br") {
                    out.append('\n')
                    return
                }
                val block = tag in BLOCK_TAGS
                if (block && out.isNotEmpty() && out.last() != '\n') out.append('\n')
                var child = node.firstChild
                while (child != null) {
                    appendText(child, out)
                    child = child.nextSibling
                }
                if (block && out.isNotEmpty() && out.last() != '\n') out.append('\n')
            }
        }
    }

    private fun isReadableManifestItem(item: ManifestItem): Boolean {
        if ("nav" in item.properties) return false
        val type = item.mediaType.lowercase()
        return type == "application/xhtml+xml" || type == "text/html" ||
            item.href.substringBefore('#').lowercase().let {
                it.endsWith(".xhtml") || it.endsWith(".html") || it.endsWith(".htm")
            }
    }

    private fun isLikelyCover(item: ManifestItem, path: String, text: String): Boolean {
        if (text.length >= 500) return false
        val marker = "${item.id} ${item.href} $path".lowercase()
        return "cover" in marker || "bìa" in marker
    }

    private fun parseXml(bytes: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            isXIncludeAware = false
            setExpandEntityReferences(false)
        }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    private fun elements(document: Document, wanted: String): List<Element> {
        val nodes = document.getElementsByTagName("*")
        val out = ArrayList<Element>()
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as? Element ?: continue
            if (localName(element) == wanted) out += element
        }
        return out
    }

    private fun localName(node: Node): String =
        (node.localName ?: node.nodeName.substringAfter(':')).lowercase()

    private fun Element.attr(name: String): String = getAttribute(name).orEmpty().trim()

    private fun String.cleanInline(): String =
        replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim()

    private fun String.cleanReadingText(): String =
        replace('\u00A0', ' ')
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(Regex("[ \\t\\u000B\\f]+"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

    private val BLOCK_TAGS = setOf(
        "address", "article", "aside", "blockquote", "div", "footer", "header",
        "h1", "h2", "h3", "h4", "h5", "h6", "li", "main", "p", "pre",
        "section", "table", "tr", "ul", "ol",
    )
}
