package com.example.weiterb2

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.util.UnstableApi
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import org.junit.Assume.assumeTrue

@UnstableApi
@RunWith(AndroidJUnit4::class)
class NavigationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val vm get() = ViewModelProvider(compose.activity)[StudyViewModel::class.java]
    private val book get(): BookVolume {
        val model = vm
        return model.books.firstOrNull { it.tracks.any(model.content::isReady) }
            ?: model.books.first { it.tracks.isNotEmpty() }
    }
    private val audioSeries get() = book.series.firstOrNull { series -> book.tracks.any { it.seriesId == series.id } }
    private val chapter get() = book.tracks.first { audioSeries == null || it.seriesId == audioSeries?.id }.groupLabel

    private fun openAudio() {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(book.familyTitle))
        compose.onNodeWithText(book.familyTitle).performScrollTo().performClick()
        if (vm.books.count { it.familyId == book.familyId } > 1) compose.onNodeWithText(book.title).performScrollTo().performClick()
        compose.onNodeWithText("Audio").performScrollTo().performClick()
        if (book.series.size > 1) compose.onNodeWithText(audioSeries!!.title).performScrollTo().performClick()
    }

    @Before fun showActivity() {
        compose.activityRule.scenario.onActivity {
            if (android.os.Build.VERSION.SDK_INT >= 27) {
                it.setShowWhenLocked(true)
                it.setTurnScreenOn(true)
            }
            it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        compose.waitUntil(10000) {
            runCatching { compose.onAllNodesWithText("Library").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
        }
    }

    private fun back() {
        compose.waitForIdle()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun systemBack() {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent 4").use {
            FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes() }
        }
        compose.waitForIdle()
    }

    @Test fun systemBackFromChapterReturnsToChapterMenu() {
        openAudio()
        compose.onNodeWithText(chapter).performScrollTo().performClick()
        compose.onNodeWithContentDescription("Back to chapters").assertExists()
        systemBack()
        assertFalse("Back exited instead of returning to the chapter menu", compose.activity.isFinishing)
        compose.onNodeWithContentDescription("Back to chapters").assertDoesNotExist()
        compose.onNodeWithText(chapter).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Library").assertIsDisplayed()
    }

    @Test fun screenArrowAndSystemBackHaveTheSameDestination() {
        openAudio()
        compose.onNodeWithText(chapter).performScrollTo().performClick()
        compose.onNodeWithContentDescription("Back to chapters").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Back to chapters").assertDoesNotExist()
        compose.onNodeWithText(chapter).performScrollTo().performClick()
        back()
        compose.onNodeWithContentDescription("Back to chapters").assertDoesNotExist()
        compose.onNodeWithText(chapter).performScrollTo().assertIsDisplayed()
    }

    @Test fun backFromOtherMenusReturnsToLibraryRoot() {
        for (menu in listOf("Saved", "Settings")) {
            openAudio()
            compose.onNodeWithText(chapter).performScrollTo().performClick()
            compose.onNodeWithText(menu).performClick()
            back()
            compose.onNodeWithContentDescription("Back to chapters").assertDoesNotExist()
            compose.onNodeWithText("Search books").assertExists()
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(book.familyTitle))
            compose.onNodeWithText(book.familyTitle).performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun chapterBackWorksAfterActivityRecreation() {
        openAudio()
        compose.onNodeWithText(chapter).performScrollTo().performClick()
        compose.activityRule.scenario.recreate()
        showActivity()
        compose.onNodeWithContentDescription("Back to chapters").assertExists()
        back()
        compose.onNodeWithContentDescription("Back to chapters").assertDoesNotExist()
        compose.onNodeWithText(chapter).performScrollTo().assertIsDisplayed()
    }

    @Test fun playerBackDismissesPlayerBeforeLeavingChapter() {
        val track = book.tracks.firstOrNull { it.groupLabel == chapter && vm.content.isReady(it) }
        assumeTrue("This test requires a downloaded audio fixture", track != null)
        openAudio()
        compose.onNodeWithText(chapter).performScrollTo().performClick()
        compose.onNodeWithText(track!!.title).performScrollTo().performClick()
        compose.waitUntil(10000) { vm.playback.value.trackId == track.id }
        compose.onNodeWithText("Practice a passage").assertExists()
        back()
        compose.onNodeWithText("Practice a passage").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back to chapters").assertExists()
        back()
        compose.onNodeWithContentDescription("Back to chapters").assertDoesNotExist()
        compose.onNodeWithText(chapter).performScrollTo().assertIsDisplayed()
    }

    @Test fun backAtLibraryRootCanExitTheActivity() {
        val activity = compose.activity
        back()
        compose.waitUntil(5000) {
            activity.isFinishing || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
    }
}
