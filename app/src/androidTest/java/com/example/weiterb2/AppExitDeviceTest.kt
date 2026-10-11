package com.example.weiterb2

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@UnstableApi
@RunWith(AndroidJUnit4::class)
class AppExitDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<WeiterApplication>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var controller: MediaController
    private var activity: ActivityScenario<MainActivity>? = null

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10000
        while (System.currentTimeMillis() < deadline) {
            var result = false
            main { result = condition() }
            if (result) return
            Thread.sleep(50)
        }
        fail(message)
    }

    @Before fun startPlayback() {
        activity = ActivityScenario.launch(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        activity!!.onActivity {
            if (android.os.Build.VERSION.SDK_INT >= 27) {
                it.setShowWhenLocked(true)
                it.setTurnScreenOn(true)
            }
            it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        controller = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java)))
            .buildAsync().get(10, TimeUnit.SECONDS)
        waitFor("Audio queue did not load") { controller.mediaItemCount == 63 }
        runBlocking {
            app.database.study().savePosition("b2-002", 0)
            app.settings.setRepeat(true)
            app.settings.setContinuous(true)
        }
        waitFor("Repeat setting did not apply") { controller.repeatMode == androidx.media3.common.Player.REPEAT_MODE_ONE }
        main { controller.volume = 0f; controller.seekTo(1, 5000); controller.prepare(); controller.play() }
        waitFor("Audio did not start") { controller.isPlaying && controller.currentPosition > 5000 }
    }

    @After fun cleanup() {
        if (::controller.isInitialized) main { controller.pause(); controller.release() }
        activity?.close()
        runBlocking { app.settings.setRepeat(false); app.settings.setContinuous(false) }
    }

    private fun assertStoppedAndSaved() {
        waitFor("Audio continued after closing the app") { !controller.isPlaying && !controller.playWhenReady }
        val deadline = System.currentTimeMillis() + 10000
        while (System.currentTimeMillis() < deadline) {
            val position = runBlocking { app.database.study().getProgress("b2-002")?.positionMs ?: 0 }
            if (position >= 5000) return
            Thread.sleep(50)
        }
        fail("Closing the app lost the playback position")
    }

    @Test fun removingAppFromRecentsStopsAudioAndSavesPosition() {
        main {
            val task = app.getSystemService(ActivityManager::class.java).appTasks.single {
                it.taskInfo.baseIntent.component?.className == MainActivity::class.java.name
            }
            task.finishAndRemoveTask()
        }
        assertStoppedAndSaved()
    }

    @Test fun backFromLibraryRootStopsAudioAndSavesPosition() {
        activity!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        assertStoppedAndSaved()
    }

    @Test fun screenRecreationKeepsAudioPlaying() {
        activity!!.recreate()
        main { assertTrue("Screen recreation interrupted playback", controller.isPlaying) }
    }
}
