package com.otis.edgereader.core.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubBookParserTest {
    @Test
    fun parsesSpineOrderAndSkipsNavAndSmallCover() {
        val archive = MapArchive(
            mapOf(
                "META-INF/container.xml" to container("OEBPS/package.opf"),
                "OEBPS/package.opf" to """
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
                      <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:identifier id="uid">ga-tau-cuoi-cung</dc:identifier>
                        <dc:title>Ga Tàu Cuối Cùng</dc:title>
                      </metadata>
                      <manifest>
                        <item id="cover" href="cover.xhtml" media-type="application/xhtml+xml"/>
                        <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                        <item id="c2" href="Text/ch2.xhtml" media-type="application/xhtml+xml"/>
                        <item id="c1" href="Text/ch1.xhtml" media-type="application/xhtml+xml"/>
                      </manifest>
                      <spine>
                        <itemref idref="cover"/>
                        <itemref idref="nav"/>
                        <itemref idref="c1"/>
                        <itemref idref="c2"/>
                      </spine>
                    </package>
                """.trimIndent(),
                "OEBPS/cover.xhtml" to xhtml("Bìa", "Bìa"),
                "OEBPS/nav.xhtml" to xhtml("Mục lục", "Chương 1 Chương 2"),
                "OEBPS/Text/ch1.xhtml" to xhtml("Chương 1 — Chuyến tàu 00:17", "Mưa bắt đầu từ lúc mười một giờ đêm."),
                "OEBPS/Text/ch2.xhtml" to xhtml("Chương 2 — Sân ga", "Ánh đèn vàng rung nhẹ trên đường ray."),
            )
        )

        val book = EpubBookParser.parse(archive)

        assertEquals("ga-tau-cuoi-cung", book.id)
        assertEquals("Ga Tàu Cuối Cùng", book.title)
        assertEquals(listOf("c1", "c2"), book.chapters.map { it.id })
        assertEquals("Chương 1 — Chuyến tàu 00:17", book.chapters[0].title)
        assertTrue(book.chapters[0].text.contains("Mưa bắt đầu"))
        assertEquals("Chương 2 — Sân ga", book.chapters[1].title)
    }

    @Test
    fun supportsOneHundredChaptersInSpineOrder() {
        val manifest = StringBuilder()
        val spine = StringBuilder()
        val files = linkedMapOf<String, String>()
        repeat(100) { index ->
            val n = index + 1
            manifest.append("<item id=\"c$n\" href=\"Text/c$n.xhtml\" media-type=\"application/xhtml+xml\"/>")
            spine.append("<itemref idref=\"c$n\"/>")
            files["OEBPS/Text/c$n.xhtml"] = xhtml("Chương $n", "Nội dung chương $n.")
        }
        files["META-INF/container.xml"] = container("OEBPS/package.opf")
        files["OEBPS/package.opf"] = """
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Truyện dài</dc:title></metadata>
              <manifest>$manifest</manifest>
              <spine>$spine</spine>
            </package>
        """.trimIndent()

        val book = EpubBookParser.parse(MapArchive(files))
        assertEquals(100, book.chapters.size)
        assertEquals("Chương 1", book.chapters.first().title)
        assertEquals("Chương 100", book.chapters.last().title)
    }

    @Test
    fun preservesVeryLongChapterWithoutTruncation() {
        val longText = "Mưa rơi đều trên mái ga. ".repeat(6_000)
        val book = EpubBookParser.parse(
            MapArchive(
                mapOf(
                    "META-INF/container.xml" to container("OPS/book.opf"),
                    "OPS/book.opf" to """
                        <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Dài</dc:title></metadata>
                          <manifest><item id="c1" href="../Text/ch1.xhtml" media-type="application/xhtml+xml"/></manifest>
                          <spine><itemref idref="c1"/></spine>
                        </package>
                    """.trimIndent(),
                    "Text/ch1.xhtml" to xhtml("Chương dài", longText),
                )
            )
        )
        assertTrue(book.chapters.single().text.length > 100_000)
        assertTrue(book.chapters.single().text.endsWith("mái ga."))
    }

    @Test
    fun malformedEpubFailsWithUsefulException() {
        val error = assertThrows(EpubParseException::class.java) {
            EpubBookParser.parse(MapArchive(emptyMap()))
        }
        assertTrue(error.message.orEmpty().contains("container.xml"))
    }

    private fun container(opfPath: String) = """
        <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
          <rootfiles><rootfile full-path="$opfPath" media-type="application/oebps-package+xml"/></rootfiles>
        </container>
    """.trimIndent()

    private fun xhtml(title: String, body: String) = """
        <html xmlns="http://www.w3.org/1999/xhtml">
          <head><title>$title</title></head>
          <body><h1>$title</h1><p>$body</p></body>
        </html>
    """.trimIndent()

    private class MapArchive(entries: Map<String, String>) : EpubArchive {
        private val data = entries.mapKeys { EpubPath.normalize(it.key) }
            .mapValues { it.value.encodeToByteArray() }
        override fun read(path: String): ByteArray? = data[EpubPath.normalize(path)]
    }
}
