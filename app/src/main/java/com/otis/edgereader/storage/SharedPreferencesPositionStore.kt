package com.otis.edgereader.storage

import android.content.Context
import com.otis.edgereader.core.model.ReadingPosition
import com.otis.edgereader.core.position.PositionStore
import java.security.MessageDigest

class SharedPreferencesPositionStore(context: Context) : PositionStore {
    private val prefs = context.getSharedPreferences("reader_positions_v1", Context.MODE_PRIVATE)

    override fun load(bookId: String): ReadingPosition? {
        val prefix = key(bookId)
        if (!prefs.contains("${prefix}_chapter")) return null
        return ReadingPosition(
            chapterIndex = prefs.getInt("${prefix}_chapter", 0),
            charOffset = prefs.getInt("${prefix}_offset", 0),
        )
    }

    override fun save(bookId: String, position: ReadingPosition) {
        val prefix = key(bookId)
        prefs.edit()
            .putInt("${prefix}_chapter", position.chapterIndex)
            .putInt("${prefix}_offset", position.charOffset)
            .apply()
    }

    override fun clear(bookId: String) {
        val prefix = key(bookId)
        prefs.edit()
            .remove("${prefix}_chapter")
            .remove("${prefix}_offset")
            .apply()
    }

    private fun key(bookId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(bookId.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
