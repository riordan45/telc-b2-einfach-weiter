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

@UnstableApi
@RunWith(AndroidJUnit4::class)
class NavigationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val chapter = "Kursbuch · 01 Freizeit"

    @Before fun showActivity() {
        compose.activityRule.scenario.onActivity {
            it.setShowWhenLocked(true)
            it.setTurnScreenOn(true)
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
        compose.onNodeWithText(chapter).performScrollTo().performClick()
        compose.onNodeWithContentDescription("Back to chapters").assertExists()
        systemBack()
        assertFalse("Back exited instead of returning to the chapter menu", compose.activity.isFinishing)
        compose.onNodeWithContentDescription("Back to chapters").assertDoesNotExist()
        compose.onNodeWithText(chapter).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Library").assertIsDisplayed()
    }

    @Test fun screenArrowAndSystemBackHaveTheSameDestination() {
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
            compose.onNodeWithText(chapter).performScrollTo().performClick()
            compose.onNodeWithText(menu).performClick()
            back()
            compose.onNodeWithContentDescription("Back to chapters").assertDoesNotExist()
            compose.onNodeWithText(chapter).performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun chapterBackWorksAfterActivityRecreation() {
        compose.onNodeWithText(chapter).performScrollTo().performClick()
        compose.activityRule.scenario.recreate()
        showActivity()
        compose.onNodeWithContentDescription("Back to chapters").assertExists()
        back()
        compose.onNodeWithContentDescription("Back to chapters").assertDoesNotExist()
        compose.onNodeWithText(chapter).performScrollTo().assertIsDisplayed()
    }

    @Test fun playerBackDismissesPlayerBeforeLeavingChapter() {
        compose.onNodeWithText(chapter).performScrollTo().performClick()
        val vm = ViewModelProvider(compose.activity)[StudyViewModel::class.java]
        compose.waitUntil(10000) { vm.playback.value.trackId != null }
        val track = vm.tracks.first { it.id == vm.playback.value.trackId }
        // Open the mini-player without selecting or playing a different recording.
        compose.onNodeWithText("Track ${track.number} · ${track.title}").performClick()
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
