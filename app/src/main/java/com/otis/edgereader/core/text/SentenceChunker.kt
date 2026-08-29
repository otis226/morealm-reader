package com.otis.edgereader.core.text

data class TextChunk(
    val start: Int,
    val endExclusive: Int,
    val text: String,
)

object SentenceChunker {
    /**
     * Returns one synthesis chunk beginning at (or just before) a sentence boundary.
     * Prefer ending on sentence punctuation once targetChars is reached, but enforce
     * maxChars even for pathological text with no punctuation.
     */
    fun nextChunk(
        text: String,
        requestedOffset: Int,
        targetChars: Int = 650,
        maxChars: Int = 1_100,
    ): TextChunk? {
        require(targetChars > 0)
        require(maxChars >= targetChars)
        if (text.isBlank()) return null

        var start = requestedOffset.coerceIn(0, text.length)
        while (start < text.length && text[start].isWhitespace()) start++
        if (start >= text.length) return null

        val hardEnd = (start + maxChars).coerceAtMost(text.length)
        val preferredEnd = (start + targetChars).coerceAtMost(hardEnd)
        var end = preferredEnd

        if (preferredEnd < text.length) {
            var probe = preferredEnd
            while (probe < hardEnd) {
                val c = text[probe]
                probe++
                if (isBoundary(c)) {
                    end = probe
                    break
                }
                end = probe
            }
        } else {
            end = text.length
        }

        if (end <= start) end = hardEnd.coerceAtLeast(start + 1)
        while (end < text.length && text[end].isWhitespace()) end++
        return TextChunk(start, end, text.substring(start, end))
    }

    fun firstChunkAfterSeek(text: String, offset: Int): TextChunk? =
        nextChunk(
            text = text,
            requestedOffset = SentenceNavigator.startAtOrBefore(text, offset),
            targetChars = 240,
            maxChars = 520,
        )

    private fun isBoundary(c: Char): Boolean =
        c == '.' || c == '!' || c == '?' || c == '…' || c == '\n' || c == ';'
}
