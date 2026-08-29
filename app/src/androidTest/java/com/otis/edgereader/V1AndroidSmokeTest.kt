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
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import com.otis.edgereader.playback.StoryPlaybackService
import com.otis.edgereader.playback.StorySessionCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
            // Await connection from the instrumentation worker thread. MediaController itself is
            // main-looper-bound, so commands must be issued on that application looper.
            val controller = controllerFuture.get(15, TimeUnit.SECONDS)
            val commandFuture = AtomicReference<ListenableFuture<SessionResult>>()
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                commandFuture.set(
                    controller.sendCustomCommand(
                        StorySessionCommands.command(StorySessionCommands.GET_STATE),
                        Bundle.EMPTY,
                    )
                )
            }
            val result = commandFuture.get().get(10, TimeUnit.SECONDS)
            assertEquals(SessionResult.RESULT_SUCCESS, result.resultCode)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                MediaController.releaseFuture(controllerFuture)
            }
        }
    }
}
