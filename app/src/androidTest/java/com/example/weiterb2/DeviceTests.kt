package com.example.weiterb2

import android.content.ComponentName
import android.os.Bundle
import android.graphics.Bitmap
import android.view.inspector.WindowInspector
import android.view.WindowManager
import android.os.Build
import android.content.Intent
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioAttributes
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import java.io.File
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@UnstableApi
@RunWith(AndroidJUnit4::class)
class PlaybackDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<WeiterApplication>()
    private val playbackTracks get() = app.library.book("einfach-weiter-b2")!!.tracks
    private lateinit var controller: MediaController
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun waitFor(message: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10000
        while (System.currentTimeMillis() < end) {
            var result = false
            main { result = condition() }
            if (result) return
            Thread.sleep(50)
        }
        fail(message)
    }
    @Before fun connect() {
        Assume.assumeTrue("Playback suite needs --bundle-book einfach-weiter-b2", playbackTracks.all { app.content.isReady(it) })
        val future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
        controller = future.get(10, TimeUnit.SECONDS)
        main {
            controller.setMediaItems(playbackTracks.map { it.mediaItem(app.content.audioUri(it)!!, "Einfach weiter! B2") }, 0, 0)
            controller.prepare()
        }
        waitFor("playlist not loaded") { controller.mediaItemCount == playbackTracks.size }
        main { controller.volume = 0f; controller.pause() }
        runBlocking { app.settings.setContinuous(false); app.settings.setRepeat(false); app.settings.setSpeed(1f) }
        Thread.sleep(300)
    }
    @After fun release() { if (::controller.isInitialized) main { controller.pause(); controller.volume = 1f; controller.release() } }
    @Test fun allBundledTracksPrepareWithoutNetwork() {
        for (track in playbackTracks) {
            main { controller.seekTo(track.number - 1, 0); controller.prepare() }
            waitFor("track ${track.number} not decodable on Android") { controller.currentMediaItem?.mediaId == track.id && controller.playbackState == Player.STATE_READY }
            main { assertEquals(track.durationMs.toDouble(), controller.duration.toDouble(), 1500.0) }
        }
    }
    @Test fun playbackControlsAndDefaultStop() {
        main { controller.seekTo(0, 0); controller.prepare(); controller.play() }
        waitFor("not playing") { controller.isPlaying && controller.currentPosition > 100 }
        main { controller.pause(); controller.seekTo(1000); controller.setPlaybackSpeed(0.75f) }
        waitFor("seek/speed not applied") { controller.currentPosition in 950..1100 && controller.playbackParameters.speed == 0.75f }
        main { controller.setPlaybackSpeed(1f); controller.seekTo(controller.duration - 500); controller.play() }
        waitFor("exercise did not stop at end") { !controller.playWhenReady }
        main { assertEquals("b2-001", controller.currentMediaItem?.mediaId) }
    }
    @Test fun repeatAndContinuousSettingsApplyInService() {
        runBlocking { app.settings.setRepeat(true) }
        main { controller.seekTo(0, 0); controller.prepare() }
        waitFor("repeat not applied") { controller.repeatMode == Player.REPEAT_MODE_ONE }
        main { controller.seekTo(controller.duration - 400); controller.play() }
        waitFor("repeat should return to start and keep playing") { controller.isPlaying && controller.currentPosition < 1000 }
        runBlocking { app.settings.setRepeat(false); app.settings.setContinuous(true) }
        Thread.sleep(300)
        main { controller.seekTo(controller.duration - 400); controller.play() }
        waitFor("continuous should play next track") { controller.currentMediaItem?.mediaId == "b2-002" && controller.isPlaying }
        runBlocking { app.settings.setContinuous(false) }
    }
    @Test fun abLoopEndingAtTrackBoundaryKeepsPlaying() {
        main { controller.seekTo(0, 0); controller.prepare() }
        waitFor("track not ready") { controller.playbackState == Player.STATE_READY }
        main {
            val end = controller.duration
            controller.sendCustomCommand(SessionCommand(PlaybackService.LOOP_COMMAND, Bundle.EMPTY), Bundle().apply { putLong("a", end - 1200); putLong("b", end) })
            controller.seekTo(end - 700)
            controller.play()
        }
        waitFor("loop not activated") { controller.sessionExtras.getLong("loopA", -1) >= 0 }
        Thread.sleep(1800)
        main { assertTrue("A–B loop stopped at file boundary", controller.isPlaying); assertEquals("b2-001", controller.currentMediaItem?.mediaId) }
    }
    @Ignore("Requires a physical headset disconnect; Android protects AUDIO_BECOMING_NOISY from test injection")
    @Test fun noisyAudioRoutePausesPlayback() {
        main { controller.seekTo(1, 0); controller.prepare(); controller.play() }
        waitFor("not playing") { controller.isPlaying }
        app.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY).setPackage(app.packageName))
        waitFor("headset disconnect signal did not pause") { !controller.isPlaying }
    }
    @Test fun abLoopRepeatsAndClearsOnTrackChange() {
        main { controller.seekTo(1, 0); controller.prepare() }
        waitFor("track not ready") { controller.playbackState == Player.STATE_READY }
        main { controller.sendCustomCommand(SessionCommand(PlaybackService.LOOP_COMMAND, Bundle.EMPTY), Bundle().apply { putLong("a", 1000); putLong("b", 1800) }); controller.play() }
        waitFor("loop not activated") { controller.sessionExtras.getLong("loopA", -1) == 1000L }
        Thread.sleep(2300)
        main { assertTrue("loop escaped range", controller.currentPosition in 900..2000); controller.seekTo(2, 0) }
        waitFor("loop not cleared") { controller.sessionExtras.getLong("loopA", -1) == -1L }
    }
    @Test fun backgroundPlaybackAndMediaButtons() {
        main { controller.seekTo(1, 0); controller.prepare(); controller.play() }
        waitFor("not playing") { controller.isPlaying }
        instrumentation.uiAutomation.executeShellCommand("input keyevent 3").close()
        Thread.sleep(1200)
        main { assertTrue(controller.isPlaying); assertTrue(controller.currentPosition > 500) }
        instrumentation.uiAutomation.executeShellCommand("input keyevent 127").close()
        waitFor("media pause key ignored") { !controller.isPlaying }
        instrumentation.uiAutomation.executeShellCommand("input keyevent 126").close()
        waitFor("media play key ignored") { controller.isPlaying }
    }
    @Test fun audioFocusInterruptionPausesPlayback() {
        val manager = app.getSystemService(AudioManager::class.java)
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            .setOnAudioFocusChangeListener {}.build()
        main { controller.seekTo(1, 0); controller.prepare(); controller.play() }
        waitFor("not playing") { controller.isPlaying }
        try {
            assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, manager.requestAudioFocus(focus))
            waitFor("audio focus loss did not pause") { !controller.isPlaying }
        } finally { manager.abandonAudioFocusRequest(focus) }
    }
    @Test fun savedDataSurvivesDatabaseReopen() = runBlocking {
        val name = "persistence-verification.db"
        app.deleteDatabase(name)
        var db = Room.databaseBuilder(app, StudyDatabase::class.java, name).build()
        try {
            db.study().setNote("track", "Remember")
            db.study().setCompleted("track", true)
            db.study().savePosition("track", 32000)
            db.study().addBookmark(Bookmark(trackId = "track", positionMs = 9000, note = "Here"))
            db.close()
            db = Room.databaseBuilder(app, StudyDatabase::class.java, name).build()
            assertEquals("Remember", db.study().getProgress("track")!!.note)
            assertTrue(db.study().getProgress("track")!!.completed)
            assertEquals(32000L, db.study().getProgress("track")!!.positionMs)
            assertEquals("Here", db.study().bookmarks().first().single().note)
        } finally { db.close(); app.deleteDatabase(name) }
    }
    @Test fun roomPositionUpdatesPreserveNotesAndCompletion() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(app, StudyDatabase::class.java).build()
        try {
            val dao = db.study()
            dao.setNote("track", "Remember this phrase")
            dao.setCompleted("track", true)
            dao.savePosition("track", 42000)
            val saved = dao.getProgress("track")!!
            assertEquals("Remember this phrase", saved.note)
            assertTrue(saved.completed)
            assertEquals(42000L, saved.positionMs)
            val markId = dao.addBookmark(Bookmark(trackId = "track", positionMs = 2400, note = "First note"))
            dao.editBookmark(markId, "Edited")
            assertEquals("Edited", dao.bookmarks().first().single().note)
            dao.deleteBookmark(markId)
            assertTrue(dao.bookmarks().first().isEmpty())
        } finally { db.close() }
    }
}
@UnstableApi
@RunWith(AndroidJUnit4::class)
class LibraryUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val vm get() = androidx.lifecycle.ViewModelProvider(compose.activity)[StudyViewModel::class.java]
    private fun openBook(book: BookVolume) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(book.familyTitle))
        compose.onNodeWithText(book.familyTitle).performScrollTo().performClick()
        if (vm.books.count { it.familyId == book.familyId } > 1) compose.onNodeWithText(book.title).performScrollTo().performClick()
    }
    private fun openB2Audio() {
        val book = vm.books.first { book -> book.tracks.any { it.id == "b2-002" } }
        openBook(book)
        compose.onNodeWithText("Audio").performScrollTo().performClick()
    }
    @Before fun showTestActivityOnLockedDevice() {
        compose.activityRule.scenario.onActivity {
            if (Build.VERSION.SDK_INT >= 27) { it.setShowWhenLocked(true); it.setTurnScreenOn(true) }
            it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        compose.waitUntil(10000) { runCatching { compose.onAllNodesWithText("Library").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
    }
    private fun capture(name: String) {
        if (name == "player") {
            // Only test-owned dialog windows inherit the test activity's lock-screen visibility.
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                if (Build.VERSION.SDK_INT >= 29) WindowInspector.getGlobalWindowViews().filter { it !== compose.activity.window.decorView }.forEach { view ->
                    val params = view.layoutParams as? WindowManager.LayoutParams
                    if (params != null) {
                        params.flags = params.flags or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        (view.context.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager).updateViewLayout(view, params)
                    }
                }
            }
            Thread.sleep(300)
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            val folder = File(compose.activity.filesDir, "screenshots").apply { mkdirs() }
            File(folder, "$name.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return
        }
        val root = if (name == "player") compose.onNode(isRoot() and hasAnyDescendant(hasText("Practice a passage"))) else compose.onNode(isRoot() and hasAnyDescendant(hasText("Library")))
        val bitmap = root.captureToImage().asAndroidBitmap()
        val folder = File(compose.activity.filesDir, "screenshots").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun accessibilityChecksOnLibraryAndSettings() {
        compose.enableAccessibilityChecks()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Library").performClick()
    }
    @Test fun largeTextNavigationRemainsScrollable() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                WeiterTheme {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) { StudyApp() }
                }
            }
        }
        compose.onNodeWithText("Search books").performScrollTo().assertIsDisplayed()
        val book = vm.books.first { it.tracks.isNotEmpty() }
        openBook(book)
        compose.onNodeWithText("Audio").performScrollTo().assertIsDisplayed()
        capture("large-text")
    }
    @Test fun playerCanSaveNoteBookmarkAndCompletion() {
        org.junit.Assume.assumeTrue("This test requires a downloaded B2 audio fixture", vm.tracks.any { it.id == "b2-002" && vm.content.isReady(it) })
        openB2Audio()
        val app = compose.activity.application as WeiterApplication
        val future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
        val controller = future.get(10, TimeUnit.SECONDS)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { controller.volume = 0f }
        try {
            compose.onNodeWithText("Search audio").performTextInput("track 2")
            compose.onNodeWithText("Freizeit · Aufgabe 2b").performClick()
            compose.waitUntil(5000) { runCatching { compose.onAllNodesWithText("Practice a passage").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { controller.pause() }
            capture("player")
            fun scrollPlayer(text: String) { compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(hasText(text)) }
            scrollPlayer("Mark as completed")
            compose.onNode(isToggleable()).performClick()
            scrollPlayer("Notes")
            compose.onNodeWithText("Notes").performClick()
            compose.onNodeWithText("Your note").performTextInput("Device test note")
            compose.onNodeWithText("Save").performClick()
            scrollPlayer("Bookmark")
            compose.onNodeWithText("Bookmark").performClick()
            compose.onNodeWithText("Your note").performTextInput("Device test bookmark")
            compose.onNodeWithText("Save").performClick()
            compose.waitUntil(5000) { runBlocking { app.database.study().getProgress("b2-002")?.note == "Device test note" } }
            compose.waitUntil(5000) { runBlocking { app.database.study().bookmarks().first().any { it.note == "Device test bookmark" } } }
            runBlocking {
                assertTrue(app.database.study().getProgress("b2-002")!!.completed)
                assertTrue(app.database.study().bookmarks().first().any { it.note == "Device test bookmark" })
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { controller.pause(); controller.volume = 1f; controller.release() }
            runBlocking {
                app.database.study().setNote("b2-002", "")
                app.database.study().setCompleted("b2-002", false)
                app.database.study().bookmarks().first().filter { it.note == "Device test bookmark" }.forEach { app.database.study().deleteBookmark(it.id) }
            }
        }
    }
    @Test fun searchFindsVerifiedPrintedPage() {
        openB2Audio()
        compose.onNodeWithText("Search audio").performTextInput("page 9")
        compose.onNodeWithText("Freizeit · Aufgabe 2b").assertExists()
        compose.onNodeWithText("Freizeit · Aufgabe 6b, 6c").assertDoesNotExist()
        capture("page-search")
    }
    @Test fun documentsOfferIndividualDownloads() {
        val book = vm.books.firstOrNull { it.documents.isNotEmpty() }
        org.junit.Assume.assumeTrue("Catalog must include a document fixture", book != null)
        openBook(book!!)
        compose.onNodeWithText("Documents").performScrollTo().performClick()
        compose.onNodeWithText("Search documents").assertExists()
        compose.onNodeWithText(book.documents.first().title).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Back to book").performScrollTo().performClick()
        compose.onNodeWithText("Documents").assertExists()
    }
    @Test fun savedAndSettingsHaveClearEmptyStates() {
        compose.onNodeWithText("Saved").performClick()
        compose.onNodeWithText("Keep the useful bits.").assertExists()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Continuous playback").assertExists()
        capture("settings")
    }
}
