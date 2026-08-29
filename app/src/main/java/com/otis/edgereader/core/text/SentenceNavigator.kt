package com.otis.edgereader.core.text

object SentenceNavigator {
    private fun isBoundary(c: Char): Boolean =
        c == '.' || c == '!' || c == '?' || c == '…' || c == '\n'

    fun startAtOrBefore(text: String, offset: Int): Int {
        if (text.isEmpty()) return 0
        var i = offset.coerceIn(0, text.length)
        if (i == text.length) i--
        while (i > 0 && !isBoundary(text[i - 1])) i--
        while (i < text.length && text[i].isWhitespace()) i++
        return i.coerceIn(0, text.length)
    }

    fun nextStart(text: String, offset: Int): Int {
        if (text.isEmpty()) return 0
        var i = startAtOrBefore(text, offset)
        while (i < text.length) {
            val c = text[i++]
            if (isBoundary(c)) {
                while (i < text.length && text[i].isWhitespace()) i++
                return i.coerceAtMost(text.length)
            }
        }
        return text.length
    }

    fun previousStart(text: String, offset: Int): Int {
        if (text.isEmpty()) return 0
        val current = startAtOrBefore(text, offset)
        if (current <= 0) return 0
        var probe = current - 1
        while (probe > 0 && text[probe].isWhitespace()) probe--
        return startAtOrBefore(text, probe)
    }
}
