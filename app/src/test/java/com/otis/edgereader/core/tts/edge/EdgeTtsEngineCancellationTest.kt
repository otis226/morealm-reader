package com.otis.edgereader.core.tts.edge

import com.otis.edgereader.core.tts.TtsCancelledException
import com.otis.edgereader.core.tts.TtsRequest
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeTtsEngineCancellationTest {
    @Test
    fun cancelledOlderGenerationFailsBeforeOpeningNetworkConnection() {
        val engine = EdgeTtsEngine()
        try {
            engine.cancelGeneration(5L)
            val cancelled = runCatching {
                engine.synthesize(
                    TtsRequest(
                        text = "Không được gửi request này",
                        voice = "vi-VN-NamMinhNeural",
                        speed = 1f,
                        pitchHz = -60,
                        generation = 4L,
                    )
                )
            }.exceptionOrNull()
            assertTrue(cancelled is TtsCancelledException)
        } finally {
            engine.close()
        }
    }
}
