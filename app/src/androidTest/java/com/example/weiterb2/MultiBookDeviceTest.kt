package com.example.weiterb2

import android.content.ComponentName
import android.os.Build
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Run with B2 and A1.1 bundled to exercise different books and normal/slow queues. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class MultiBookDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val vm get() = ViewModelProvider(compose.activity)[StudyViewModel::class.java]
    private val app get() = compose.activity.application as WeiterApplication
    private val a1 get() = vm.library.book("einfach-gut-a1-1")!!
    private fun show() = compose.activityRule.scenario.onActivity {
        if (Build.VERSION.SDK_INT >= 27) { it.setShowWhenLocked(true); it.setTurnScreenOn(true) }
        it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    @Before fun ready() {
        show()
        compose.waitUntil(10000) { vm.playback.value.ready }
    }
    private fun openA1() {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(a1.familyTitle))
        compose.onNodeWithText(a1.familyTitle).performScrollTo().performClick()
        compose.onNodeWithText(a1.title).performScrollTo().performClick()
    }
    private fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

    @Test fun browsingKeepsPlaybackAndSelectingAudioConfinesEachQueue() {
        val b2 = vm.library.book("einfach-weiter-b2")!!
        Assume.assumeTrue(b2.tracks.all(vm.content::isReady) && a1.tracks.all(vm.content::isReady))
        val controller = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync().get(10, TimeUnit.SECONDS)
        try {
            main { vm.select(b2.tracks.first(), 0, play = false) }
            compose.waitUntil(10000) { vm.playback.value.trackId == "b2-001" }
            openA1()
            main { assertEquals("b2-001", controller.currentMediaItem!!.mediaId); assertEquals(63, controller.mediaItemCount) }
            for (series in a1.series) {
                val track = a1.tracks.first { it.seriesId == series.id }
                val expected = vm.library.queueFor(track).map { it.id }
                main { vm.select(track, 0, play = false) }
                compose.waitUntil(10000) { vm.playback.value.trackId == track.id }
                main {
                    assertEquals(expected, (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId })
                    assertFalse(vm.canSkip(-1))
                    controller.seekTo(controller.mediaItemCount - 1, 0)
                }
                compose.waitUntil(10000) { vm.playback.value.trackId == expected.last() }
                main { assertFalse(vm.canSkip(1)) }
            }
        } finally { main { controller.pause(); controller.release() } }
    }

    @Test fun originalPdfRestoresSavedPageAfterNavigationAndRecreation() {
        val document = a1.documents.first()
        compose.waitUntil(10000) { vm.content.isReady(document) }
        runBlocking { vm.content.saveDocumentPage(document.id, 3) }
        openA1()
        compose.onNodeWithText("Documents").performScrollTo().performClick()
        compose.onAllNodesWithText("Open PDF").onFirst().performScrollTo().performClick()
        fun rendered(page: Int) {
            val label = "Page $page of ${document.pageCount} · ${document.title}"
            compose.waitUntil(10000) { compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription(label).assertIsDisplayed()
        }
        rendered(4)
        compose.onNodeWithContentDescription("Zoom in").performClick()
        compose.onNodeWithContentDescription("Zoom out").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("Next page").performClick()
        rendered(5)
        compose.activityRule.scenario.recreate()
        show()
        rendered(5)
        compose.onNodeWithContentDescription("Back to documents").performClick()
        compose.onAllNodesWithText("Open PDF").onFirst().performScrollTo().performClick()
        rendered(5)
        assertEquals(4, runBlocking { vm.content.documentPage(document.id).first() })
    }
}
