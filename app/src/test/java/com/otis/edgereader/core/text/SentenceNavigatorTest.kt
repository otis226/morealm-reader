package com.otis.edgereader.core.text

import org.junit.Assert.assertEquals
import org.junit.Test

class SentenceNavigatorTest {
    private val text = "Câu một.  Câu hai!\nCâu ba? Câu bốn…"

    @Test
    fun findsSentenceStartAtOrBefore() {
        assertEquals(0, SentenceNavigator.startAtOrBefore(text, 3))
        assertEquals(text.indexOf("Câu hai"), SentenceNavigator.startAtOrBefore(text, text.indexOf("hai")))
        assertEquals(text.indexOf("Câu ba"), SentenceNavigator.startAtOrBefore(text, text.indexOf("ba")))
    }

    @Test
    fun movesForwardAndBackwardBySentence() {
        val second = text.indexOf("Câu hai")
        val third = text.indexOf("Câu ba")
        assertEquals(second, SentenceNavigator.nextStart(text, 0))
        assertEquals(third, SentenceNavigator.nextStart(text, second))
        assertEquals(second, SentenceNavigator.previousStart(text, third))
        assertEquals(0, SentenceNavigator.previousStart(text, second))
    }
}
