package com.example.weiterb2

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** Run seed, force-stop the validation app, then run assert to reconcile persisted transfers. */
@RunWith(AndroidJUnit4::class)
class TransferRestartDeviceTest {
    @Test fun downloadManagerTransfersResumeAfterAppProcessRestart() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("transfer_phase")
        Assume.assumeTrue("Pass -e transfer_phase seed or assert", phase in listOf("seed", "assert"))
        val app = ApplicationProvider.getApplicationContext<WeiterApplication>()
        val book = app.library.book("prufungstraining-telc-deutsch-c1-hochschule-2026")!!
        if (phase == "seed") {
            app.content.deleteAudio(book.id)
            app.content.downloadAudio(book.id)
            assertTrue(app.database.content().all().any { it.id in book.packages.map { pack -> pack.id } && it.downloadId != null })
        } else {
            try {
                app.content.reconcile()
                withTimeout(180000) {
                    while (!book.tracks.all(app.content::isReady)) {
                        val failed = book.packages.firstOrNull { app.content.states.value[it.id]?.phase == ContentPhase.FAILED }
                        check(failed == null) { app.content.states.value[failed!!.id]?.error.orEmpty() }
                        delay(500)
                    }
                }
                for (track in book.tracks) ContentVerifier.verify(File(app.content.audioUri(track)!!.path!!), track.bytes, track.sha256)
            } finally { app.content.deleteAudio(book.id) }
        }
    }
}
