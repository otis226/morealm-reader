package com.otis.edgereader.core.playback

import com.otis.edgereader.core.model.Book
import com.otis.edgereader.core.model.Chapter
import com.otis.edgereader.core.model.ReadingPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackStateMachineTest {
    private val book = Book(
        id = "book",
        title = "Book",
        chapters = listOf(
            Chapter("c1", "One", "abc"),
            Chapter("c2", "Two", "12345"),
            Chapter("c3", "Three", "xyz"),
        ),
    )

    @Test
    fun loadNormalizesSavedPosition() {
        val machine = PlaybackStateMachine()
        val state = machine.load(book, ReadingPosition(99, 99))
        assertEquals(ReadingPosition(2, 3), state.position)
        assertEquals(PlaybackStatus.READY, state.status)
    }

    @Test
    fun seekWhilePlayingReturnsToPreparingAtNewPosition() {
        val machine = PlaybackStateMachine()
        machine.load(book)
        machine.requestPlay()
        machine.markPlaying()
        val state = machine.seek(ReadingPosition(1, 4))
        assertEquals(PlaybackStatus.PREPARING, state.status)
        assertEquals(ReadingPosition(1, 4), state.position)
        assertNull(state.errorMessage)
    }

    @Test
    fun chapterCompletionAdvancesAndFinalChapterEnds() {
        val machine = PlaybackStateMachine()
        machine.load(book)
        machine.requestPlay()
        machine.markPlaying()

        assertEquals(ReadingPosition(1, 0), machine.chapterCompleted().position)
        assertEquals(PlaybackStatus.PREPARING, machine.snapshot().status)

        machine.markPlaying()
        assertEquals(ReadingPosition(2, 0), machine.chapterCompleted().position)
        machine.markPlaying()

        val ended = machine.chapterCompleted()
        assertEquals(PlaybackStatus.ENDED, ended.status)
        assertEquals(ReadingPosition(2, 3), ended.position)
    }

    @Test
    fun navigationFromPausedStaysPaused() {
        val machine = PlaybackStateMachine()
        machine.load(book)
        machine.requestPlay()
        machine.markPlaying()
        machine.pause()
        val state = machine.nextChapter()
        assertEquals(PlaybackStatus.PAUSED, state.status)
        assertEquals(ReadingPosition(1, 0), state.position)
    }
}
