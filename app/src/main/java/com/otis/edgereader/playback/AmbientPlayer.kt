package com.otis.edgereader.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * One high-quality background loop channel. The app intentionally does not synthesize
 * fake rain/music: users can assign real audio files to named ambience presets.
 */
class AmbientPlayer(
    context: Context,
) : AutoCloseable {
    private val prefs = context.getSharedPreferences("ambient_v1", Context.MODE_PRIVATE)
    private val player = ExoPlayer.Builder(context).build().apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            false,
        )
        repeatMode = Player.REPEAT_MODE_ONE
        setHandleAudioBecomingNoisy(true)
    }

    var uri: String? = prefs.getString("active_uri", null)
        private set
    var label: String = prefs.getString("active_label", "Tắt") ?: "Tắt"
        private set
    var volume: Float = prefs.getFloat("volume", 0.18f).coerceIn(0f, 1f)
        private set

    init {
        player.volume = volume
        uri?.takeIf { it.isNotBlank() }?.let(::prepareUri)
    }

    fun configure(uri: String?, label: String, volume: Float) {
        this.uri = uri?.takeIf { it.isNotBlank() }
        this.label = label.ifBlank { "Âm nền" }
        this.volume = volume.coerceIn(0f, 1f)
        player.volume = this.volume
        prefs.edit()
            .putString("active_uri", this.uri)
            .putString("active_label", this.label)
            .putFloat("volume", this.volume)
            .apply()

        player.stop()
        player.clearMediaItems()
        this.uri?.let(::prepareUri)
    }

    fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
        player.volume = this.volume
        prefs.edit().putFloat("volume", this.volume).apply()
    }

    fun syncWithNarration(isNarrationPlaying: Boolean) {
        if (isNarrationPlaying && player.mediaItemCount > 0) {
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        } else {
            player.pause()
        }
    }

    private fun prepareUri(value: String) {
        player.setMediaItem(MediaItem.fromUri(Uri.parse(value)))
        player.prepare()
    }

    override fun close() {
        player.release()
    }
}
