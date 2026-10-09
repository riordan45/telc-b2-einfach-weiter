package com.example.weiterb2

import android.app.Application
import android.content.ComponentName
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@UnstableApi
class StudyViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as WeiterApplication
    val tracks = app.catalog
    val progress = app.database.study().progress().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val bookmarks = app.database.study().bookmarks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val settings = app.settings.values.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlaybackSettings())
    private val mutablePlayback = MutableStateFlow(PlaybackState())
    val playback = mutablePlayback.asStateFlow()
    private val future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
    private var controller: MediaController? = null
    private var error: String? = null
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { refresh() }
        override fun onPlayerError(error: PlaybackException) { this@StudyViewModel.error = "Couldn’t play this track. Tap play to retry."; refresh() }
    }
    init {
        future.addListener({
            try {
                controller = future.get().also { it.addListener(listener) }
                refresh()
            } catch (_: Exception) {
                error = "Couldn’t connect to the audio player. Reopen the app to retry."
                refresh()
            }
        }, ContextCompat.getMainExecutor(app))
        viewModelScope.launch { while (isActive) { refresh(); delay(200) } }
    }
    private fun refresh() {
        val c = controller
        mutablePlayback.value = PlaybackState(
            trackId = c?.currentMediaItem?.mediaId,
            positionMs = c?.currentPosition?.coerceAtLeast(0) ?: 0,
            playing = c?.isPlaying ?: false,
            ready = c != null && (c.mediaItemCount > 0),
            loopA = c?.sessionExtras?.getLong("loopA", -1) ?: -1,
            loopB = c?.sessionExtras?.getLong("loopB", -1) ?: -1,
            error = error,
        )
    }
    fun select(track: Track, position: Long? = null, play: Boolean = true) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return
        error = null
        val resume = position ?: progress.value.find { it.trackId == track.id }?.positionMs ?: 0
        c.seekTo(tracks.indexOf(track), resume.coerceIn(0, track.durationMs).let { if (it >= track.durationMs - 300) 0 else it })
        c.prepare()
        if (play) c.play()
        refresh()
    }
    fun togglePlayback() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else {
            error = null
            val track = tracks.find { it.id == c.currentMediaItem?.mediaId }
            if (track != null && c.currentPosition >= track.durationMs - 300) c.seekTo(0)
            if (c.playbackState == Player.STATE_IDLE || c.playbackState == Player.STATE_ENDED) c.prepare()
            c.play()
        }
        refresh()
    }
    fun seek(position: Long) { controller?.seekTo(position.coerceAtLeast(0)); refresh() }
    fun skip(delta: Int) {
        val c = controller ?: return
        val index = (c.currentMediaItemIndex + delta).coerceIn(0, tracks.lastIndex)
        select(tracks[index], 0)
    }
    fun shift(seconds: Int) { val c = controller ?: return; seek((c.currentPosition + seconds * 1000L).coerceIn(0, tracks.find { it.id == c.currentMediaItem?.mediaId }?.durationMs ?: 0)) }
    fun loop(a: Long, b: Long) {
        controller?.sendCustomCommand(SessionCommand(PlaybackService.LOOP_COMMAND, Bundle.EMPTY), Bundle().apply { putLong("a", a); putLong("b", b) })
    }
    fun speed(value: Float) { viewModelScope.launch { app.settings.setSpeed(value) } }
    fun continuous(value: Boolean) { viewModelScope.launch { app.settings.setContinuous(value) } }
    fun repeat(value: Boolean) { viewModelScope.launch { app.settings.setRepeat(value) } }
    fun completed(id: String, value: Boolean) { viewModelScope.launch { app.database.study().setCompleted(id, value) } }
    fun note(id: String, value: String) { viewModelScope.launch { app.database.study().setNote(id, value) } }
    fun addBookmark(track: Track, position: Long, note: String) { viewModelScope.launch { app.database.study().addBookmark(Bookmark(trackId = track.id, positionMs = position, note = note)) } }
    fun editBookmark(id: Long, note: String) { viewModelScope.launch { app.database.study().editBookmark(id, note) } }
    fun deleteBookmark(id: Long) { viewModelScope.launch { app.database.study().deleteBookmark(id) } }
    override fun onCleared() { controller?.removeListener(listener); MediaController.releaseFuture(future); super.onCleared() }
}
data class PlaybackState(val trackId: String? = null, val positionMs: Long = 0, val playing: Boolean = false, val ready: Boolean = false, val loopA: Long = -1, val loopB: Long = -1, val error: String? = null)
