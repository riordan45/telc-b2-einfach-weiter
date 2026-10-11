package com.example.weiterb2

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Seed a bundled build, install the default over it, then assert without clearing data. */
@RunWith(AndroidJUnit4::class)
class UpgradeDeviceTest {
    @Test fun bundledToDefaultPreservesStudyRecordsAndSettings() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("upgrade_phase")
        Assume.assumeTrue("Pass -e upgrade_phase seed or assert", phase in listOf("seed", "assert"))
        val app = ApplicationProvider.getApplicationContext<WeiterApplication>()
        val dao = app.database.study()
        if (phase == "seed") {
            assertTrue(app.content.isReady(app.library.track("b2-003")!!))
            dao.setNote("b2-003", "Upgrade verification")
            dao.setCompleted("b2-003", true)
            dao.savePosition("b2-003", 12345)
            dao.addBookmark(Bookmark(trackId = "b2-003", positionMs = 2345, note = "Upgrade bookmark"))
            app.settings.setSpeed(1.25f)
            app.settings.setRepeat(true)
            app.settings.setContinuous(true)
        } else {
            val saved = dao.getProgress("b2-003")!!
            assertEquals("Upgrade verification", saved.note)
            assertTrue(saved.completed)
            assertEquals(12345L, saved.positionMs)
            assertTrue(dao.bookmarks().first().any { it.trackId == "b2-003" && it.positionMs == 2345L && it.note == "Upgrade bookmark" })
            assertEquals(PlaybackSettings(1.25f, true, true), app.settings.values.first())
            assertFalse("Default update should require B2 audio download", app.content.isReady(app.library.track("b2-003")!!))
            dao.setNote("b2-003", "")
            dao.setCompleted("b2-003", false)
            dao.bookmarks().first().filter { it.note == "Upgrade bookmark" }.forEach { dao.deleteBookmark(it.id) }
            app.settings.setSpeed(1f)
            app.settings.setRepeat(false)
            app.settings.setContinuous(false)
        }
    }
}
