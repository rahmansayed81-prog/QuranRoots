package com.quranroots.app.audio

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

@OptIn(UnstableApi::class)
class AudioPlayer(context: Context) {

    private val exo: ExoPlayer = ExoPlayer.Builder(context).build()

    private val _currentKey = MutableStateFlow<Pair<Int, Int>?>(null)
    val currentKey: StateFlow<Pair<Int, Int>?> = _currentKey.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _onCompleted = MutableSharedFlow<Pair<Int, Int>>(extraBufferCapacity = 4)
    /** Emits (surah, ayah) when playback of that ayah finishes naturally. */
    val onCompleted: SharedFlow<Pair<Int, Int>> = _onCompleted.asSharedFlow()

    init {
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    val key = _currentKey.value
                    _currentKey.value = null
                    _isPlaying.value = false
                    if (key != null) _onCompleted.tryEmit(key)
                }
            }
        })
    }

    fun playLocal(file: java.io.File, surah: Int, ayah: Int) {
        exo.setMediaItem(MediaItem.fromUri(file.toURI().toString()))
        exo.prepare()
        exo.playWhenReady = true
        _currentKey.value = surah to ayah
    }

    fun stop() {
        exo.stop()
        exo.clearMediaItems()
        _currentKey.value = null
        _isPlaying.value = false
    }

    fun release() {
        exo.release()
    }
}
