package com.otis.edgereader.core.epub

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipBytesEpubArchiveTest {
    @Test
    fun readsNormalizedZipEntries() {
        val bytes = ByteArrayOutputStream().use { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("OEBPS/Text/ch1.xhtml"))
                zip.write("hello".encodeToByteArray())
                zip.closeEntry()
            }
            out.toByteArray()
        }

        val archive = ZipBytesEpubArchive(bytes)
        assertEquals("hello", archive.read("OEBPS/./Text/ch1.xhtml")!!.decodeToString())
    }
}
