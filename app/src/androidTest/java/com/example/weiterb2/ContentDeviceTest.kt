package com.example.weiterb2

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Environment
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContentDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun migrationPreservesEveryExistingStudyField() = runBlocking {
        val name = "content-migration-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL("CREATE TABLE progress (trackId TEXT NOT NULL PRIMARY KEY, positionMs INTEGER NOT NULL, completed INTEGER NOT NULL, note TEXT NOT NULL, lastPlayed INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE bookmarks (id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, trackId TEXT NOT NULL, positionMs INTEGER NOT NULL, note TEXT NOT NULL, createdAt INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX index_bookmarks_trackId ON bookmarks (trackId)")
            db.execSQL("INSERT INTO progress VALUES ('b2-002', 42000, 1, 'Keep my note', 1234)")
            db.execSQL("INSERT INTO bookmarks VALUES (7, 'b2-002', 9000, 'Keep my bookmark', 4567)")
            db.version = 1
        }
        val database = Room.databaseBuilder(context, StudyDatabase::class.java, name).addMigrations(StudyDatabase.MIGRATION_1_2).build()
        try {
            assertEquals(TrackProgress("b2-002", 42000, true, "Keep my note", 1234), database.study().getProgress("b2-002"))
            assertEquals(Bookmark(7, "b2-002", 9000, "Keep my bookmark", 4567), database.study().bookmarks().first().single())
            database.content().saveDocumentPage(DocumentPosition("document", 3))
            assertEquals(3, database.content().documentPage("document").first())
            assertTrue(database.content().all().isEmpty())
        } finally { database.close(); context.deleteDatabase(name) }
    }

    private suspend fun withDocument(availableBytes: (File) -> Long = { it.usableSpace }, block: suspend (ContentRepository, ContentDao, String, File) -> Unit) {
        val id = "test-document-${UUID.randomUUID()}"
        val bytes = "%PDF-1.4\nOriginal document bytes\n".toByteArray()
        val document = BookDocument(id, "test-book", "Document", "de", "vocabulary", "https://www.telc.net/test.pdf", bytes.size.toLong(), digest(bytes), 1)
        val library = LibraryCatalog(listOf(BookVolume("test-book", "test-family", "Test", "Test", documents = listOf(document))))
        val database = Room.inMemoryDatabaseBuilder(context, StudyDatabase::class.java).build()
        val repository = ContentRepository(context, library, database.content(), autoReconcile = false, availableBytes = availableBytes)
        val stagingName = "$id.pdf"
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "staging/$stagingName")
        file.parentFile!!.mkdirs()
        file.writeBytes(bytes)
        database.content().put(ContentDownload(id, 1, ContentPhase.PREPARING.name, stagingName = stagingName, totalBytes = bytes.size.toLong()))
        try { block(repository, database.content(), id, file) }
        finally { repository.close(); database.close(); file.delete(); File(context.filesDir, "content/$id").deleteRecursively() }
    }

    @Test fun completedPreparationPublishesTheOriginalFileOnce() = runBlocking {
        withDocument { repository, dao, id, original ->
            val bytes = original.readBytes()
            assertTrue(repository.finalize(id, 1))
            assertEquals(ContentPhase.READY.name, dao.get(id)!!.phase)
            val installed = File(context.filesDir, "content/$id/1/$id.pdf")
            assertArrayEquals(bytes, installed.readBytes())
            assertTrue(repository.finalize(id, 1))
            assertArrayEquals(bytes, installed.readBytes())
        }
    }

    @Test fun cancellationDuringPreparationCannotPublishReadyContent() = runBlocking {
        withDocument { repository, dao, id, _ ->
            val prepared = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val job = async { repository.finalize(id, 1) { prepared.complete(Unit); release.await() } }
            withTimeout(10000) { prepared.await() }
            repository.cancelDocument(id)
            release.complete(Unit)
            assertTrue(job.await())
            assertEquals(ContentPhase.NOT_DOWNLOADED.name, dao.get(id)!!.phase)
            assertEquals(2L, dao.get(id)!!.generation)
            assertFalse(File(context.filesDir, "content/$id/1/$id.pdf").exists())
            assertNull(repository.documentFile(id))
        }
    }

    @Test fun aCorruptDownloadCannotBecomeReady() = runBlocking {
        withDocument { repository, dao, id, file ->
            file.appendText("corrupt")
            assertFalse(repository.finalize(id, 1))
            assertEquals(ContentPhase.FAILED.name, dao.get(id)!!.phase)
            assertFalse(File(context.filesDir, "content/$id/1/$id.pdf").exists())
        }
    }

    @Test fun insufficientStorageLeavesDownloadRetryableAndUnpublished() = runBlocking {
        withDocument(availableBytes = { 0 }) { repository, dao, id, original ->
            assertFalse(repository.finalize(id, 1))
            assertEquals(ContentPhase.FAILED.name, dao.get(id)!!.phase)
            assertTrue(dao.get(id)!!.error!!.contains("storage"))
            assertTrue(original.exists())
            assertNull(repository.documentFile(id))
        }
    }

    @Test fun processDeathAfterAtomicCommitRecoversReadyState() = runBlocking {
        withDocument { repository, dao, id, original ->
            val installed = File(context.filesDir, "content/$id/1/$id.pdf")
            installed.parentFile!!.mkdirs()
            original.copyTo(installed)
            original.delete()
            repository.reconcile()
            assertEquals(ContentPhase.READY.name, dao.get(id)!!.phase)
            assertArrayEquals("%PDF-1.4\nOriginal document bytes\n".toByteArray(), installed.readBytes())
        }
    }

    @Test fun missingStagingFileOffersRetryWithoutPublishing() = runBlocking {
        withDocument { repository, dao, id, original ->
            original.delete()
            assertFalse(repository.finalize(id, 1))
            assertEquals(ContentPhase.FAILED.name, dao.get(id)!!.phase)
            assertNotNull(dao.get(id)!!.error)
            assertNull(repository.documentFile(id))
        }
    }
}
