package com.example.weiterb2

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.*
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*

@UnstableApi
class PlaybackService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private val app get() = application as WeiterApplication
    private var loop: LoopRange? = null
    private var playbackSettings = PlaybackSettings()
    private var snapshotId: String? = null
    private var snapshotPosition = 0L
    private var touched = false

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this).setSeekBackIncrementMs(10000).setSeekForwardIncrementMs(10000).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            setHandleAudioBecomingNoisy(true)
            pauseAtEndOfMediaItems = true
        }
        session = MediaSession.Builder(this, player)
            .setSessionActivity(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    if (controller.packageName != packageName && !controller.isTrusted) return MediaSession.ConnectionResult.reject()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(SessionCommand(LOOP_COMMAND, Bundle.EMPTY)).build()).build()
                }
                override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, command: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                    if (command.customAction != LOOP_COMMAND || controller.packageName != packageName) return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                    val a = args.getLong("a", -1)
                    val b = args.getLong("b", -1)
                    if (a < 0 && b < 0) { setLoop(null); return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS)) }
                    val range = LoopRange.create(a, b, player.duration)
                        ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    setLoop(range)
                    if (player.currentPosition !in range.a until range.b) player.seekTo(range.a)
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            }).build()
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                persistSnapshot()
                snapshotId = mediaItem?.mediaId
                snapshotPosition = player.currentPosition
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) setLoop(null)
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                    loop?.let { player.seekTo(it.a); player.play() }
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) touched = true
                snapshotPosition = player.currentPosition
                if (!isPlaying) persistSnapshot()
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                val id = oldPosition.mediaItem?.mediaId
                if (touched && id != null) scope.launch { app.database.study().savePosition(id, oldPosition.positionMs) }
                snapshotId = newPosition.mediaItem?.mediaId
                snapshotPosition = newPosition.positionMs
                if (oldPosition.mediaItemIndex != newPosition.mediaItemIndex) setLoop(null)
            }
        })
        scope.launch {
            val latest = app.database.study().latest()
            val index = app.catalog.indexOfFirst { it.id == latest?.trackId }.coerceAtLeast(0)
            val track = app.catalog[index]
            val position = (latest?.positionMs ?: 0).coerceIn(0, track.durationMs)
            player.setMediaItems(app.catalog.map { it.mediaItem() }, index, position)
            player.prepare()
        }
        scope.launch {
            app.settings.values.collect {
                player.setPlaybackSpeed(it.speed)
                playbackSettings = it
                updateEndBehavior()
                player.repeatMode = if (it.repeatTrack) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            }
        }
        scope.launch {
            var ticks = 0
            while (isActive) {
                if (player.isPlaying) {
                    loop?.let { if (it.shouldRepeat(player.currentPosition)) player.seekTo(it.a) }
                    snapshotId = player.currentMediaItem?.mediaId
                    snapshotPosition = player.currentPosition
                    if (++ticks % 50 == 0) persistSnapshot()
                }
                delay(100)
            }
        }
    }
    private fun setLoop(value: LoopRange?) {
        loop = value
        updateEndBehavior()
        session.setSessionExtras(Bundle().apply { putLong("loopA", value?.a ?: -1); putLong("loopB", value?.b ?: -1) })
    }
    private fun updateEndBehavior() {
        player.pauseAtEndOfMediaItems = loop != null || (!playbackSettings.continuous && !playbackSettings.repeatTrack)
    }
    private fun persistSnapshot() {
        if (!touched) return
        val id = snapshotId ?: return
        val position = snapshotPosition
        scope.launch { app.database.study().savePosition(id, position) }
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session
    override fun onTaskRemoved(rootIntent: Intent?) {
        pauseAllPlayersAndStopSelf()
    }
    override fun onDestroy() {
        // Capture before release; complete the final tiny database transaction before cancelling.
        val id = snapshotId
        val position = if (::player.isInitialized) player.currentPosition else snapshotPosition
        if (touched && id != null) runBlocking(Dispatchers.IO) { app.database.study().savePosition(id, position) }
        session.release()
        player.release()
        scope.cancel()
        super.onDestroy()
    }
    companion object { const val LOOP_COMMAND = "com.example.weiterb2.LOOP" }
}

fun Track.mediaItem(): MediaItem = MediaItem.Builder().setMediaId(id).setUri("asset:///$asset")
    .setMediaMetadata(MediaMetadata.Builder().setTitle("Track $number · $title").setArtist(referenceLabel().ifBlank { "Einfach weiter! B2" }).setAlbumTitle("Einfach weiter! B2").build()).build()
