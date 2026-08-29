package com.otis.edgereader

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.otis.edgereader.playback.StoryPlaybackService
import com.otis.edgereader.playback.StorySessionCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class V1AndroidSmokeTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun readerActivityLaunchesWithoutCrash() {
        ActivityScenario.launch(V1ReaderActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
            }
        }
    }

    @Test
    fun mediaSessionServiceConnectsAndReturnsStateWithoutNetwork() {
        val token = SessionToken(context, ComponentName(context, StoryPlaybackService::class.java))
        val controllerFuture = MediaController.Builder(context, token).buildAsync()
        try {
            val controller = controllerFuture.get(15, TimeUnit.SECONDS)
            val result = controller.sendCustomCommand(
                StorySessionCommands.command(StorySessionCommands.GET_STATE),
                Bundle.EMPTY,
            ).get(10, TimeUnit.SECONDS)
            assertEquals(SessionResult.RESULT_SUCCESS, result.resultCode)
        } finally {
            MediaController.releaseFuture(controllerFuture)
        }
    }
}
