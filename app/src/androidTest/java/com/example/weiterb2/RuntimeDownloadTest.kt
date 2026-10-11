package com.example.weiterb2

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in integration checks against the pinned original telc files. */
@RunWith(AndroidJUnit4::class)
class RuntimeDownloadTest {
    private val app = ApplicationProvider.getApplicationContext<WeiterApplication>()
    private fun requireNetworkChecks() = Assume.assumeTrue("Pass -e runtime_network true", InstrumentationRegistry.getArguments().getString("runtime_network") == "true")
    private suspend fun awaitReady(id: String) {
        withTimeout(180000) {
            while (app.content.states.value[id]?.phase != ContentPhase.READY) {
                val state = app.content.states.value[id]
                check(state?.phase != ContentPhase.FAILED) { state?.error.orEmpty() }
                delay(500)
            }
        }
    }
    @Test fun originalPdfDownloadsOpensRemembersPageAndRedownloads() = runBlocking {
        requireNetworkChecks()
        val document = app.library.documents.minBy { it.bytes }
        try {
            app.content.deleteDocument(document.id)
            app.content.downloadDocument(document.id)
            awaitReady(document.id)
            val file = app.content.documentFile(document.id)!!
            ContentVerifier.verify(file, document.bytes, document.sha256)
            PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
                assertEquals(document.pageCount, renderer.pageCount)
                assertTrue(renderer.pageCount > 0)
            }
            app.content.saveDocumentPage(document.id, 1)
            app.content.deleteDocument(document.id)
            assertFalse(app.content.isReady(document))
            assertEquals(1, app.content.documentPage(document.id).first())
            app.content.downloadDocument(document.id)
            awaitReady(document.id)
            assertTrue(app.content.isReady(document))
            assertEquals(1, app.content.documentPage(document.id).first())
        } finally { app.content.deleteDocument(document.id) }
    }
    @Test fun audioPackCanCancelRetryAndPlayVerifiedOfflineFiles() = runBlocking {
        requireNetworkChecks()
        val book = app.library.books.filter { it.tracks.isNotEmpty() }.minBy { it.packages.sumOf { pack -> pack.bytes } }
        try {
            app.content.deleteAudio(book.id)
            app.content.downloadAudio(book.id)
            app.content.cancelAudio(book.id)
            assertFalse(book.tracks.any { app.content.isReady(it) })
            app.content.downloadAudio(book.id)
            for (pack in book.packages) awaitReady(pack.id)
            assertTrue(book.tracks.all { app.content.isReady(it) })
            for (track in book.tracks) {
                val uri = app.content.audioUri(track)!!
                assertEquals("file", uri.scheme)
                ContentVerifier.verify(java.io.File(uri.path!!), track.bytes, track.sha256)
            }
            app.database.study().setNote(book.tracks.first().id, "Keep after deleting audio")
            app.content.deleteAudio(book.id)
            assertFalse(book.tracks.any { app.content.isReady(it) })
            assertEquals("Keep after deleting audio", app.database.study().getProgress(book.tracks.first().id)!!.note)
        } finally {
            app.content.deleteAudio(book.id)
            app.database.study().setNote(book.tracks.first().id, "")
        }
    }
    @Test fun unicodeAndSpacedOriginalUrlsDownloadWithoutNormalization() = runBlocking {
        requireNetworkChecks()
        val document = app.library.document("einfach-gut-alpha-doc-1")!!
        val book = app.library.book("prufungstraining-telc-deutsch-c1-hochschule-2026")!!
        try {
            app.content.downloadDocument(document.id)
            awaitReady(document.id)
            ContentVerifier.verify(app.content.documentFile(document.id)!!, document.bytes, document.sha256)
            app.content.downloadAudio(book.id)
            for (pack in book.packages) awaitReady(pack.id)
            assertTrue(book.tracks.all { app.content.isReady(it) })
            assertEquals((1..24).toList(), app.library.queueFor(book.tracks.first()).map { it.number })
        } finally { app.content.deleteDocument(document.id); app.content.deleteAudio(book.id) }
    }
}
