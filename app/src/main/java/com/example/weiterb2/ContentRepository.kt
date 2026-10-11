package com.example.weiterb2

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

enum class ContentPhase { NOT_DOWNLOADED, QUEUED, DOWNLOADING, WAITING, PREPARING, READY, FAILED }
data class ContentStatus(
    val phase: ContentPhase = ContentPhase.NOT_DOWNLOADED,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val error: String? = null,
) {
    val progress: Float? get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null
    val active: Boolean get() = phase in setOf(ContentPhase.QUEUED, ContentPhase.DOWNLOADING, ContentPhase.WAITING, ContentPhase.PREPARING)
}

/** DownloadManager owns staging only; verified, generation-scoped files belong to the app. */
class ContentRepository(context: Context, private val library: LibraryCatalog, private val dao: ContentDao, autoReconcile: Boolean = true,
    private val availableBytes: (File) -> Long = { it.usableSpace }) {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(DownloadManager::class.java)
    private val work get() = WorkManager.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()
    private val preparations = ConcurrentHashMap.newKeySet<String>()
    private val root = File(this.context.filesDir, "content")
    private val mutableStates = MutableStateFlow<Map<String, ContentStatus>>(emptyMap())
    val states = mutableStates.asStateFlow()
    @Volatile private var records: Map<String, ContentDownload> = emptyMap()
    @Volatile private var bundledDocuments: Map<String, File> = emptyMap()
    private val bundled: Map<String, String> = loadBundle()
    private val bundledTracks = library.tracks.mapNotNull { track ->
        val asset = bundled[track.id] ?: track.asset.takeIf { it.isNotBlank() && assetExists(it) }
        asset?.let { track.id to it }
    }.toMap()

    init {
        publish(emptyList())
        scope.launch { dao.observe().collect { publish(it) } }
        if (autoReconcile) scope.launch {
            gate.withLock { prepareBundledDocuments() }
            gate.withLock { root.listFiles()?.filter { it.name.startsWith(".preparing-") && it.name !in preparations }?.forEach(File::deleteRecursively) }
            while (isActive) {
                runCatching { reconcile() }
                delay(if (states.value.values.any { it.active }) 1000 else 15000)
            }
        }
    }

    fun isReady(track: Track): Boolean = bundledTracks.containsKey(track.id) || audioFile(track) != null
    fun isBundled(track: Track): Boolean = bundledTracks.containsKey(track.id)
    fun isBundled(document: BookDocument): Boolean = bundled.containsKey(document.id)
    fun isReady(document: BookDocument): Boolean = documentFile(document.id) != null
    fun audioUri(track: Track): Uri? = bundledTracks[track.id]?.let { Uri.parse("asset:///$it") } ?: audioFile(track)?.let(Uri::fromFile)
    fun documentFile(documentId: String): File? {
        val document = library.document(documentId) ?: return null
        val file = bundledDocuments[documentId] ?: records[documentId]?.takeIf { it.phase == ContentPhase.READY.name }?.let { File(installed(it.id, it.generation), "$documentId.pdf") }
        return file?.takeIf { document.bytes > 0 && it.isFile && it.length() == document.bytes }
    }
    fun documentPage(id: String): Flow<Int> = dao.documentPage(id).map { it?.coerceAtLeast(0) ?: 0 }
    suspend fun saveDocumentPage(id: String, index: Int) { if (library.document(id) != null) dao.saveDocumentPage(DocumentPosition(id, index.coerceAtLeast(0))) }

    suspend fun downloadAudio(bookId: String) {
        library.book(bookId)?.packages?.forEach { source ->
            if (library.tracks.any { it.packageId == source.id && !isReady(it) }) start(source.id)
        }
    }
    suspend fun downloadDocument(documentId: String) {
        if (bundled.containsKey(documentId)) withContext(Dispatchers.IO) { gate.withLock { prepareBundledDocuments() } }
        else if (library.document(documentId)?.let(::isReady) != true) start(documentId)
    }
    suspend fun cancelAudio(bookId: String) { library.book(bookId)?.packages?.forEach { revoke(it.id, onlyActive = true) } }
    suspend fun cancelDocument(documentId: String) { revoke(documentId, onlyActive = true) }
    suspend fun deleteAudio(bookId: String) { library.book(bookId)?.packages?.forEach { revoke(it.id) } }
    suspend fun deleteDocument(documentId: String) { if (!bundled.containsKey(documentId)) revoke(documentId) }

    private data class Spec(val id: String, val title: String, val url: String, val bytes: Long, val sha256: String, val members: List<ExpectedContentFile> = emptyList())
    private fun spec(id: String): Spec? = library.sourcePackage(id)?.let { source ->
        Spec(source.id, source.title, source.url, source.bytes, source.sha256,
            library.tracks.filter { it.packageId == id }.map { ExpectedContentFile(it.id, it.sourceMember, it.bytes, it.sha256) })
    } ?: library.document(id)?.let { Spec(it.id, it.title, it.url, it.bytes, it.sha256) }

    private suspend fun start(id: String) = withContext(Dispatchers.IO) {
        gate.withLock {
            val source = spec(id) ?: return@withLock
            val previous = dao.get(id)
            if (previous?.let { status(it).active } == true || available(source)) return@withLock
            val generation = (previous?.generation ?: 0) + 1
            val filename = "${ContentVerifier.safeId(id)}-$generation-${UUID.randomUUID()}.${if (source.members.isEmpty()) "pdf" else "zip"}"
            var row = ContentDownload(id, generation, ContentPhase.QUEUED.name, stagingName = filename, totalBytes = source.bytes)
            dao.put(row)
            try {
                val sourceUri = Uri.parse(source.url)
                require(sourceUri.scheme == "https" && !sourceUri.host.isNullOrBlank()) { "This resource needs a secure source URL." }
                // Publisher URLs contain spaces and umlauts; encode the decoded path once.
                val downloadUri = sourceUri.buildUpon().path(sourceUri.path).fragment(null).build()
                require(source.bytes > 0 && source.sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "This resource has not been verified yet." }
                val stagingFolder = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: error("Download storage is unavailable. Try again when storage is available.")
                require(availableBytes(stagingFolder) >= source.bytes) { "There isn’t enough storage for this download. Free some space and retry." }
                require(availableBytes(context.filesDir) >= source.preparedBytes) { "There isn’t enough storage for this book. Free some space and retry." }
                previous?.downloadId?.let { manager.remove(it) }
                val request = DownloadManager.Request(downloadUri)
                    .setTitle(source.title).setDescription("Download for your book")
                    .setMimeType(if (source.members.isEmpty()) "application/pdf" else "application/zip")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setAllowedOverRoaming(false)
                    .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "staging/$filename")
                row = row.copy(downloadId = manager.enqueue(request))
                dao.put(row)
            } catch (error: Exception) { dao.put(row.copy(phase = ContentPhase.FAILED.name, error = message(error))) }
            publish(dao.all())
        }
    }

    private suspend fun revoke(id: String, onlyActive: Boolean = false) = withContext(Dispatchers.IO) {
        gate.withLock {
            val row = dao.get(id) ?: return@withLock
            if (onlyActive && !status(row).active) return@withLock
            // Generation changes precede cancelling I/O, so an in-flight worker cannot commit.
            dao.put(ContentDownload(id, row.generation + 1))
            work.cancelUniqueWork(workName(id, row.generation))
            row.downloadId?.let { manager.remove(it) }
            row.stagingName?.let { staged(it)?.delete() }
            File(root, ContentVerifier.safeId(id)).deleteRecursively()
            publish(dao.all())
        }
    }

    suspend fun reconcile() = withContext(Dispatchers.IO) {
        gate.withLock {
            for (row in dao.all()) {
                val source = spec(row.id) ?: continue
                if (row.phase == ContentPhase.READY.name) {
                    if (!installedFilesPresent(source, row)) dao.put(row.copy(phase = ContentPhase.FAILED.name, error = "Downloaded files are missing. Download again."))
                    else row.downloadId?.let { manager.remove(it); dao.put(row.copy(downloadId = null, stagingName = null)) }
                    continue
                }
                if (!status(row).active) continue
                if (row.phase == ContentPhase.PREPARING.name && installedFilesPresent(source, row)) {
                    dao.put(row.copy(phase = ContentPhase.READY.name, error = null))
                    row.downloadId?.let { manager.remove(it) }
                    continue
                }
                val downloadId = row.downloadId ?: findStagedDownload(row.stagingName)
                if (downloadId == null) {
                    dao.put(row.copy(phase = ContentPhase.FAILED.name, error = "Download stopped. Tap download to retry."))
                    continue
                }
                manager.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
                    if (!cursor.moveToFirst()) {
                        dao.put(row.copy(phase = ContentPhase.FAILED.name, error = "Download stopped. Tap download to retry."))
                        return@use
                    }
                    val result = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)).coerceAtLeast(0)
                    val phase = when (result) {
                        DownloadManager.STATUS_RUNNING -> ContentPhase.DOWNLOADING
                        DownloadManager.STATUS_PAUSED -> ContentPhase.WAITING
                        DownloadManager.STATUS_SUCCESSFUL -> ContentPhase.PREPARING
                        DownloadManager.STATUS_FAILED -> ContentPhase.FAILED
                        else -> ContentPhase.QUEUED
                    }
                    val next = row.copy(downloadId = downloadId, phase = phase.name, downloadedBytes = downloaded,
                        error = if (phase == ContentPhase.FAILED) "Couldn’t download this file. Tap download to retry." else null)
                    if (next != row) dao.put(next)
                    if (phase == ContentPhase.PREPARING) enqueueFinalization(next)
                }
            }
            publish(dao.all())
        }
    }

    /** Idempotent and safe after process death, cancellation, or duplicate completion broadcasts. */
    internal suspend fun finalize(id: String, generation: Long, beforeCommit: suspend () -> Unit = {}): Boolean = withContext(Dispatchers.IO) {
        val prepared = File(root, ".preparing-${ContentVerifier.safeId(id)}-$generation-${UUID.randomUUID()}")
        val row = gate.withLock {
            dao.get(id)?.takeIf { it.generation == generation && it.phase == ContentPhase.PREPARING.name }?.also { preparations.add(prepared.name) }
        } ?: return@withContext true
        val source = spec(id)
        if (source == null) { preparations.remove(prepared.name); return@withContext true }
        try {
            val staging = row.stagingName?.let(::staged) ?: error("The downloaded file is missing. Download again.")
            require(availableBytes(context.filesDir) >= source.preparedBytes) { "There isn’t enough storage to prepare this download. Free some space and retry." }
            ContentVerifier.verify(staging, source.bytes, source.sha256)
            if (source.members.isNotEmpty()) ContentVerifier.extract(staging, prepared, source.members)
            else {
                require(prepared.mkdirs() || prepared.isDirectory)
                val pdf = File(prepared, "$id.pdf")
                staging.copyTo(pdf)
                ContentVerifier.verify(pdf, source.bytes, source.sha256)
                pdf.inputStream().use { input ->
                    val header = ByteArray(5)
                    require(input.read(header) == 5 && String(header, Charsets.US_ASCII) == "%PDF-") { "The document is not a PDF." }
                }
            }
            beforeCommit()
            gate.withLock {
                val current = dao.get(id)
                if (current?.generation != generation || current.phase != ContentPhase.PREPARING.name) return@withLock
                val target = installed(id, generation)
                if (!target.exists()) {
                    require(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
                    check(prepared.renameTo(target)) { "Couldn’t save this download. Check available storage and retry." }
                }
                check(installedFilesPresent(source, current)) { "The prepared download is incomplete." }
                dao.put(current.copy(phase = ContentPhase.READY.name, downloadedBytes = source.bytes, error = null))
                current.downloadId?.let { manager.remove(it) }
                dao.put(current.copy(phase = ContentPhase.READY.name, downloadedBytes = source.bytes, downloadId = null, stagingName = null, error = null))
                publish(dao.all())
            }
            true
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            gate.withLock {
                val current = dao.get(id)
                if (current?.generation == generation && current.phase == ContentPhase.PREPARING.name) {
                    dao.put(current.copy(phase = ContentPhase.FAILED.name, error = message(error)))
                    publish(dao.all())
                }
            }
            false
        } finally { prepared.deleteRecursively(); preparations.remove(prepared.name) }
    }

    private fun enqueueFinalization(row: ContentDownload) {
        val request = OneTimeWorkRequestBuilder<ContentFinalizeWorker>().setInputData(workDataOf("id" to row.id, "generation" to row.generation)).build()
        work.enqueueUniqueWork(workName(row.id, row.generation), ExistingWorkPolicy.KEEP, request)
    }
    private fun workName(id: String, generation: Long) = "content-$id-$generation"
    private val Spec.preparedBytes: Long get() = if (members.isEmpty()) bytes else members.sumOf { it.bytes }
    private fun installed(id: String, generation: Long) = File(root, "${ContentVerifier.safeId(id)}/$generation")
    private fun staged(name: String): File? = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { File(it, "staging/${ContentVerifier.safeId(name)}") }
    private fun audioFile(track: Track): File? = records[track.packageId]?.takeIf { it.phase == ContentPhase.READY.name }?.let { File(installed(it.id, it.generation), "${ContentVerifier.safeId(track.id)}.mp3").takeIf { file -> track.bytes > 0 && file.isFile && file.length() == track.bytes } }
    private fun installedFilesPresent(source: Spec, row: ContentDownload): Boolean {
        val folder = installed(row.id, row.generation)
        return if (source.members.isEmpty()) File(folder, "${row.id}.pdf").let { it.isFile && it.length() == source.bytes }
        else source.members.all { member -> File(folder, "${member.id}.mp3").let { it.isFile && it.length() == member.bytes } }
    }
    private fun available(source: Spec): Boolean = if (source.members.isEmpty()) library.document(source.id)?.let(::isReady) == true
        else source.members.all { library.track(it.id)?.let(::isReady) == true }
    private fun status(row: ContentDownload) = ContentStatus(runCatching { ContentPhase.valueOf(row.phase) }.getOrDefault(ContentPhase.NOT_DOWNLOADED), row.downloadedBytes, row.totalBytes, row.error)
    private fun publish(rows: List<ContentDownload>) {
        records = rows.associateBy { it.id }
        val next = rows.associate { it.id to status(it) }.toMutableMap()
        library.packages.forEach { source ->
            val tracks = library.tracks.filter { it.packageId == source.id }
            if (tracks.isNotEmpty() && tracks.all { bundledTracks.containsKey(it.id) }) next[source.id] = ContentStatus(ContentPhase.READY, source.bytes, source.bytes)
        }
        bundledDocuments.forEach { (id, file) -> if (file.isFile) next[id] = ContentStatus(ContentPhase.READY, file.length(), file.length()) }
        mutableStates.value = next
    }
    private fun findStagedDownload(name: String?): Long? {
        if (name == null) return null
        manager.query(DownloadManager.Query()).use { cursor ->
            while (cursor.moveToNext()) {
                val uri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                if (uri?.let { Uri.parse(it).lastPathSegment } == name) return cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
            }
        }
        return null
    }
    private fun loadBundle(): Map<String, String> = runCatching {
        val json = JSONObject(context.assets.open("bundled.json").bufferedReader().use { it.readText() })
        require(json.getInt("schemaVersion") == 1)
        val resources = json.getJSONObject("resources")
        resources.keys().asSequence().associateWith { key -> resources.getString(key).also { require(it.startsWith("content/") && !it.contains("..") && assetExists(it)) } }
    }.getOrDefault(emptyMap())
    private fun assetExists(path: String): Boolean = runCatching { context.assets.open(path).close(); true }.getOrDefault(false)
    private suspend fun prepareBundledDocuments() {
        val files = mutableMapOf<String, File>()
        library.documents.forEach { document -> bundled[document.id]?.let { asset ->
            runCatching {
                val folder = File(context.cacheDir, "bundled-documents").apply { mkdirs() }
                val file = File(folder, "${ContentVerifier.safeId(document.id)}-${document.sha256.take(16)}.pdf")
                val valid = file.isFile && runCatching { ContentVerifier.verify(file, document.bytes, document.sha256) }.isSuccess
                if (!valid) {
                    val temporary = File(folder, "${document.id}.part")
                    context.assets.open(asset).use { input -> temporary.outputStream().use { output -> input.copyTo(output) } }
                    ContentVerifier.verify(temporary, document.bytes, document.sha256)
                    check(temporary.renameTo(file))
                }
                files[document.id] = file
            }
        } }
        bundledDocuments = files
        publish(dao.all())
    }
    private fun message(error: Exception): String = if (error is IllegalArgumentException || error is IllegalStateException) error.message.orEmpty().ifBlank { "Couldn’t prepare this download. Tap download to retry." }
        else "Couldn’t save this download. Check your connection and available storage, then retry."
    internal fun close() { scope.cancel() }
}

class ContentFinalizeWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("id") ?: return Result.failure()
        val generation = inputData.getLong("generation", -1)
        val app = applicationContext as WeiterApplication
        return if (app.content.finalize(id, generation)) Result.success() else Result.failure()
    }
}

class DownloadCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { (context.applicationContext as WeiterApplication).content.reconcile() } finally { pending.finish() }
        }
    }
}
