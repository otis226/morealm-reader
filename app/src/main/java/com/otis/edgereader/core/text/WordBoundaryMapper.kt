package com.otis.edgereader.core.text

import com.otis.edgereader.core.tts.WordBoundary

data class MappedWordBoundary(
    val text: String,
    val offsetMs: Long,
    val durationMs: Long,
    val charOffset: Int,
)

object WordBoundaryMapper {
    /**
     * Maps Edge word timestamps back to offsets in the exact chunk text.
     * If one metadata token cannot be found, keep the search cursor unchanged so a
     * malformed/normalized token cannot poison the offsets of all following words.
     */
    fun map(chunkText: String, boundaries: List<WordBoundary>): List<MappedWordBoundary> {
        var cursor = 0
        return boundaries.map { boundary ->
            var found = chunkText.indexOf(boundary.text, startIndex = cursor, ignoreCase = false)
            if (found < 0) {
                found = chunkText.indexOf(boundary.text, startIndex = cursor, ignoreCase = true)
            }
            val index = if (found >= 0) found else cursor.coerceAtMost(chunkText.length)
            if (found >= 0) {
                cursor = (found + boundary.text.length).coerceAtMost(chunkText.length)
            }
            MappedWordBoundary(
                text = boundary.text,
                offsetMs = boundary.offsetMs,
                durationMs = boundary.durationMs,
                charOffset = index,
            )
        }
    }

    fun charOffsetAtPlayback(
        mapped: List<MappedWordBoundary>,
        positionMs: Long,
        fallback: Int = 0,
    ): Int = mapped.lastOrNull { it.offsetMs <= positionMs }?.charOffset ?: fallback
}
