package com.otis.edgereader.core.playback

class GenerationClock {
    private var generation: Long = 0L

    @Synchronized
    fun next(): Long {
        generation += 1
        return generation
    }

    @Synchronized
    fun current(): Long = generation

    @Synchronized
    fun isCurrent(candidate: Long): Boolean = candidate == generation
}
