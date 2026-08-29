package com.otis.edgereader.core.position

import com.otis.edgereader.core.model.ReadingPosition

interface PositionStore {
    fun load(bookId: String): ReadingPosition?
    fun save(bookId: String, position: ReadingPosition)
    fun clear(bookId: String)
}
