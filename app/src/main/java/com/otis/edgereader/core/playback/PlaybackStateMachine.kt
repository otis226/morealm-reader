package com.otis.edgereader.core.playback

import com.otis.edgereader.core.model.Book
import com.otis.edgereader.core.model.ReadingPosition

enum class PlaybackStatus {
    EMPTY,
    READY,
    PREPARING,
    PLAYING,
    PAUSED,
    ENDED,
    ERROR,
}

data class PlaybackSnapshot(
    val book: Book? = null,
    val position: ReadingPosition = ReadingPosition.START,
    val status: PlaybackStatus = PlaybackStatus.EMPTY,
    val errorMessage: String? = null,
)

class PlaybackStateMachine {
    private var state = PlaybackSnapshot()

    fun snapshot(): PlaybackSnapshot = state

    fun load(book: Book, position: ReadingPosition = ReadingPosition.START): PlaybackSnapshot {
        state = PlaybackSnapshot(
            book = book,
            position = position.normalized(book),
            status = PlaybackStatus.READY,
        )
        return state
    }

    fun requestPlay(): PlaybackSnapshot {
        val book = state.book ?: return state
        val position = if (state.status == PlaybackStatus.ENDED) {
            ReadingPosition(book.lastChapterIndex, 0)
        } else {
            state.position.normalized(book)
        }
        state = state.copy(position = position, status = PlaybackStatus.PREPARING, errorMessage = null)
        return state
    }

    fun markPlaying(): PlaybackSnapshot {
        if (state.book != null && state.status != PlaybackStatus.ENDED) {
            state = state.copy(status = PlaybackStatus.PLAYING, errorMessage = null)
        }
        return state
    }

    fun pause(): PlaybackSnapshot {
        if (state.status == PlaybackStatus.PLAYING || state.status == PlaybackStatus.PREPARING) {
            state = state.copy(status = PlaybackStatus.PAUSED)
        }
        return state
    }

    fun seek(position: ReadingPosition): PlaybackSnapshot {
        val book = state.book ?: return state
        val normalized = position.normalized(book)
        val nextStatus = when (state.status) {
            PlaybackStatus.PLAYING, PlaybackStatus.PREPARING -> PlaybackStatus.PREPARING
            PlaybackStatus.ENDED, PlaybackStatus.ERROR -> PlaybackStatus.PAUSED
            else -> state.status
        }
        state = state.copy(position = normalized, status = nextStatus, errorMessage = null)
        return state
    }

    fun updateOffset(charOffset: Int): PlaybackSnapshot {
        val book = state.book ?: return state
        val chapter = book.chapter(state.position.chapterIndex)
        state = state.copy(
            position = state.position.copy(charOffset = charOffset.coerceIn(0, chapter.text.length))
        )
        return state
    }

    fun nextChapter(): PlaybackSnapshot {
        val book = state.book ?: return state
        val current = state.position.chapterIndex
        if (current >= book.lastChapterIndex) {
            val last = book.chapter(book.lastChapterIndex)
            state = state.copy(
                position = ReadingPosition(book.lastChapterIndex, last.text.length),
                status = PlaybackStatus.ENDED,
            )
            return state
        }
        state = state.copy(
            position = ReadingPosition(current + 1, 0),
            status = transitionStatusForNavigation(),
            errorMessage = null,
        )
        return state
    }

    fun previousChapter(): PlaybackSnapshot {
        val book = state.book ?: return state
        val target = (state.position.chapterIndex - 1).coerceAtLeast(0)
        state = state.copy(
            position = ReadingPosition(target, 0),
            status = transitionStatusForNavigation(),
            errorMessage = null,
        )
        return state
    }

    fun chapterCompleted(): PlaybackSnapshot = nextChapter()

    fun fail(message: String): PlaybackSnapshot {
        state = state.copy(status = PlaybackStatus.ERROR, errorMessage = message)
        return state
    }

    private fun transitionStatusForNavigation(): PlaybackStatus = when (state.status) {
        PlaybackStatus.PLAYING, PlaybackStatus.PREPARING -> PlaybackStatus.PREPARING
        PlaybackStatus.EMPTY -> PlaybackStatus.EMPTY
        else -> PlaybackStatus.PAUSED
    }
}
