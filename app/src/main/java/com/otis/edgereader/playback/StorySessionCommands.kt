package com.otis.edgereader.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand

object StorySessionCommands {
    const val OPEN_BOOK = "story.open_book"
    const val SEEK_TEXT = "story.seek_text"
    const val NEXT_SENTENCE = "story.next_sentence"
    const val PREVIOUS_SENTENCE = "story.previous_sentence"
    const val NEXT_CHAPTER = "story.next_chapter"
    const val PREVIOUS_CHAPTER = "story.previous_chapter"
    const val SET_TTS = "story.set_tts"
    const val SET_AMBIENT = "story.set_ambient"
    const val GET_STATE = "story.get_state"
    const val STATE_CHANGED = "story.state_changed"
    const val SEGMENT_CHANGED = "story.segment_changed"
    const val SET_SLEEP_TIMER = "story.set_sleep_timer"

    const val KEY_BOOK_ID = "book_id"
    const val KEY_AUTOPLAY = "autoplay"
    const val KEY_CHAPTER = "chapter"
    const val KEY_OFFSET = "offset"
    const val KEY_VOICE = "voice"
    const val KEY_SPEED = "speed"
    const val KEY_PITCH = "pitch"
    const val KEY_VOICE_VOLUME = "voice_volume"
    const val KEY_STATUS = "status"
    const val KEY_ERROR = "error"
    const val KEY_TITLE = "title"
    const val KEY_CHAPTER_TITLE = "chapter_title"
    const val KEY_CHAPTER_COUNT = "chapter_count"
    const val KEY_CHAPTER_LENGTH = "chapter_length"
    const val KEY_SEGMENT_START = "segment_start"
    const val KEY_SEGMENT_END = "segment_end"
    const val KEY_SEGMENT_TEXT = "segment_text"
    const val KEY_WORD_TEXTS = "word_texts"
    const val KEY_WORD_OFFSETS_MS = "word_offsets_ms"
    const val KEY_WORD_DURATIONS_MS = "word_durations_ms"
    const val KEY_SLEEP_MINUTES = "sleep_minutes"
    const val KEY_AMBIENT_URI = "ambient_uri"
    const val KEY_AMBIENT_LABEL = "ambient_label"
    const val KEY_AMBIENT_VOLUME = "ambient_volume"

    val customCommands: List<SessionCommand> = listOf(
        OPEN_BOOK,
        SEEK_TEXT,
        NEXT_SENTENCE,
        PREVIOUS_SENTENCE,
        NEXT_CHAPTER,
        PREVIOUS_CHAPTER,
        SET_TTS,
        SET_AMBIENT,
        GET_STATE,
        SET_SLEEP_TIMER,
    ).map { SessionCommand(it, Bundle.EMPTY) }

    fun command(action: String): SessionCommand = SessionCommand(action, Bundle.EMPTY)
}
