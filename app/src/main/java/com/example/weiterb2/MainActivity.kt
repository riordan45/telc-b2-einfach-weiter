package com.example.weiterb2

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi

@UnstableApi
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { WeiterTheme { StudyApp() } }
    }
}
@Composable
fun WeiterTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFFFFB4AA), secondary = Color(0xFFE7BDB7), primaryContainer = Color(0xFF653330), onPrimaryContainer = Color(0xFFFFDAD4), secondaryContainer = Color(0xFF453430), onSecondaryContainer = Color(0xFFFFDAD4), surfaceContainer = Color(0xFF241C1A), surfaceContainerLow = Color(0xFF201817), surfaceContainerHigh = Color(0xFF302725), surface = Color(0xFF171312), background = Color(0xFF171312))
        else lightColorScheme(primary = Color(0xFFA53332), onPrimary = Color.White, primaryContainer = Color(0xFFFFDAD4), secondary = Color(0xFF715C56), secondaryContainer = Color(0xFFF1DFD9), onSecondaryContainer = Color(0xFF291B18), surface = Color(0xFFFFF8F5), background = Color(0xFFFFF8F5))
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}
@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun StudyApp(vm: StudyViewModel = viewModel()) {
    val playback by vm.playback.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val contentStates by vm.content.states.collectAsStateWithLifecycle()
    var navigation by rememberSaveable(stateSaver = listSaver<AppNavigation, String>(
        save = { listOf(it.menu) + it.path },
        restore = { AppNavigation(it.first(), it.drop(1)) },
    )) { mutableStateOf(AppNavigation()) }
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    var documentId by rememberSaveable { mutableStateOf<String?>(null) }
    val current = vm.tracks.find { it.id == playback.trackId }
    fun open(track: Track, position: Long? = null) {
        if (vm.content.isReady(track)) { vm.select(track, position); playerOpen = true }
    }
    // Menus register their content here; adding one needs no new Back-button logic.
    val menus = listOf(
        TopLevelMenu(AppNavigation.HOME, "Library", Icons.Rounded.Headphones) { padding ->
            Library(vm, progress, contentStates, Modifier.padding(padding), ::open,
                path = navigation.path,
                navigate = { navigation = navigation.open(it) },
                goBack = { navigation = navigation.back() },
                openDocument = { documentId = it.id })
        },
        TopLevelMenu("saved", "Saved", Icons.Rounded.Bookmarks) { padding ->
            Saved(vm, progress, bookmarks, contentStates, Modifier.padding(padding), ::open, vm::editBookmark, vm::deleteBookmark)
        },
        TopLevelMenu("settings", "Settings", Icons.Rounded.Settings) { padding ->
            Settings(settings, Modifier.padding(padding), vm::continuous)
        },
    )
    BackHandler(enabled = documentId != null || playerOpen || navigation.canGoBack) {
        if (documentId != null) documentId = null
        else if (playerOpen) playerOpen = false
        else navigation = navigation.back()
    }
    val document = documentId?.let(vm.library::document)
    val documentFile = document?.let { vm.content.documentFile(it.id) }
    if (document != null && documentFile != null) {
        val position = remember(document.id) { vm.documentPage(document.id) }
        val page by position.collectAsStateWithLifecycle(initialValue = null)
        if (page != null) {
            PdfScreen(document, documentFile, page!!, { vm.saveDocumentPage(document.id, it) }, { documentId = null })
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        return
    }
    Scaffold(
        bottomBar = {
            Column {
                if (current != null) MiniPlayer(current, bookLabel(vm.library.book(current.bookId)), playback, vm::togglePlayback) { playerOpen = true }
                NavigationBar {
                    menus.forEach { menu ->
                        NavigationBarItem(selected = navigation.menu == menu.id, onClick = { navigation = navigation.selectMenu(menu.id) }, icon = { Icon(menu.icon, null) }, label = { Text(menu.label) })
                    }
                }
            }
        }
    ) { padding ->
        (menus.firstOrNull { it.id == navigation.menu } ?: menus.first()).content(padding)
    }
    if (playerOpen && current != null) {
        ModalBottomSheet(onDismissRequest = { playerOpen = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            PlayerPanel(vm, current, playback, settings, progress.find { it.trackId == current.id })
        }
    }
}
private data class TopLevelMenu(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val content: @Composable (PaddingValues) -> Unit,
)
@Composable
private fun Heading(eyebrow: String, title: String, subtitle: String? = null) {
    Column(Modifier.padding(top = 20.dp, bottom = 12.dp)) {
        Text(eyebrow.uppercase(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, letterSpacing = 1.8.sp)
        Text(title, fontFamily = FontFamily.Serif, fontSize = 34.sp, lineHeight = 40.sp, modifier = Modifier.padding(top = 8.dp))
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    }
}
@UnstableApi
@Composable
private fun Library(vm: StudyViewModel, progress: List<TrackProgress>, states: Map<String, ContentStatus>, modifier: Modifier,
    open: (Track, Long?) -> Unit, path: List<String>, navigate: (String) -> Unit, goBack: () -> Unit,
    openDocument: (BookDocument) -> Unit) {
    val familyId = path.firstOrNull { it.startsWith("family:") }?.removePrefix("family:")
    val book = path.lastOrNull { it.startsWith("book:") }?.removePrefix("book:")?.let(vm.library::book)
    val media = path.firstOrNull { it == "audio" || it == "documents" }
    val seriesId = path.lastOrNull { it.startsWith("series:") }?.removePrefix("series:")
    val chapter = path.lastOrNull { it.startsWith("chapter:") }?.removePrefix("chapter:")
    var query by rememberSaveable(path.joinToString("/")) { mutableStateOf("") }
    var chapters by rememberSaveable(book?.id, seriesId) { mutableStateOf(true) }
    val downloaded = remember(states, vm.tracks) { vm.tracks.filter { vm.content.isReady(it) }.map { it.id }.toSet() }
    val documentsReady = remember(states, vm.library.documents) { vm.library.documents.filter { vm.content.isReady(it) }.map { it.id }.toSet() }
    val families = remember(vm.books) { vm.books.sortedBy { it.position }.groupBy { it.familyId } }
    val familyBooks = families[familyId].orEmpty()
    val series = book?.series?.sortedBy { it.position }.orEmpty()
    val audio = book?.tracks?.filter { seriesId == null || it.seriesId == seriesId }.orEmpty()
    val results = audio.filter { (query.isNotBlank() || chapter == null || it.groupLabel == chapter) && it.matches(query) }
    val grouped = audio.groupBy { it.groupLabel }
    val familyResults = families.values.filter { volumes ->
        query.isBlank() || normalized("${volumes.first().familyTitle} ${volumes.joinToString(" ") { "${it.title} ${it.level}" }}").contains(normalized(query.trim()))
    }
    val listState = key(path) { rememberLazyListState() }
    LazyColumn(modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (path.isNotEmpty()) item {
            BackLink(when {
                chapter != null -> "Back to chapters"
                seriesId != null -> "Back to audio"
                media != null -> "Back to book"
                book != null && familyId != null -> "Back to volumes"
                else -> "Back to books"
            }, goBack)
        }
        when {
            book == null && familyId == null -> {
                item { Heading("Your telc companion", "Listen. Learn. Weiter.", "Choose your book. Download audio and vocabulary to study offline.") }
                item { SearchField(query, { query = it }, "Search books", "Book title or level") }
                val latest = progress.filter { it.lastPlayed > 0 }.maxByOrNull { it.lastPlayed }
                val resume = vm.tracks.find { it.id == latest?.trackId && it.id in downloaded }
                if (resume != null && query.isBlank()) item {
                    ContinueListening(resume, latest?.positionMs ?: 0, bookLabel(vm.library.book(resume.bookId))) { open(resume, null) }
                }
                if (familyResults.isEmpty()) item { EmptyMessage("No matching books", "Try another title or level.") }
                items(familyResults, key = { it.first().familyId }) { volumes ->
                    val first = volumes.first()
                    LibraryEntry(first.familyTitle, listOf(
                        volumes.map { it.level }.filter { it.isNotBlank() }.distinct().joinToString(" · "),
                        if (volumes.size == 1) resourceCounts(first) else "${volumes.size} volumes",
                    ).filter { it.isNotBlank() }.joinToString("  ·  "), Icons.Rounded.MenuBook) {
                        navigate(if (volumes.size == 1) "book:${first.id}" else "family:${first.familyId}")
                    }
                }
            }
            book == null -> {
                item { Heading("Choose a volume", familyBooks.firstOrNull()?.familyTitle ?: "Books") }
                items(familyBooks, key = { it.id }) { volume ->
                    LibraryEntry(volume.title, listOf(volume.level, volume.edition, resourceCounts(volume)).filter { it.isNotBlank() }.joinToString(" · ")) { navigate("book:${volume.id}") }
                }
            }
            media == null -> {
                item { Heading(book.familyTitle, book.title, listOf(book.level, book.edition).filter { it.isNotBlank() }.joinToString(" · ").takeIf { it.isNotBlank() }) }
                if (book.tracks.isNotEmpty()) item {
                    LibraryEntry("Audio", "${book.tracks.size} tracks · ${book.tracks.count { it.id in downloaded }} downloaded", Icons.Rounded.Headphones) { navigate("audio") }
                }
                if (book.documents.isNotEmpty()) item {
                    LibraryEntry("Documents", "Vocabulary, listening texts and other book resources", Icons.Rounded.Description) { navigate("documents") }
                }
                if (book.tracks.isEmpty() && book.documents.isEmpty()) item { EmptyMessage("No companion files yet", "Resources for this volume will appear with a catalog update.") }
                if (book.isbn.isNotBlank()) item { Text("ISBN ${book.isbn}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp)) }
            }
            media == "documents" -> {
                item { Heading(bookLabel(book), "Documents", "Download a document once, then read it offline.") }
                item { SearchField(query, { query = it }, "Search documents", "Title or language") }
                val documents = book.documents.sortedBy { it.position }.filter {
                    query.isBlank() || normalized("${it.title} ${it.language} ${it.kind}").contains(normalized(query.trim()))
                }
                if (documents.isEmpty()) item { EmptyMessage("No matching documents", "Try another title or language.") }
                items(documents, key = { it.id }) { document ->
                    DocumentRow(document, states[document.id] ?: ContentStatus(), document.id in documentsReady, vm.content.isBundled(document),
                        download = { vm.downloadDocument(document.id) }, cancel = { vm.cancelDocument(document.id) },
                        delete = { vm.deleteDocument(document.id) }, open = { openDocument(document) })
                }
            }
            else -> {
                item { Heading(bookLabel(book), series.firstOrNull { it.id == seriesId }?.title ?: "Audio") }
                item { AudioDownloads(book, states, book.tracks.count { it.id in downloaded }, book.tracks.all(vm.content::isBundled),
                    download = { vm.downloadAudio(book.id) }, cancel = { vm.cancelAudio(book.id) }, delete = { vm.deleteAudio(book.id) }) }
                item { SearchField(query, { query = it }, "Search audio", "Track, chapter, page or exercise") }
                if (chapter == null && (seriesId != null || series.size <= 1)) item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = chapters, onClick = { chapters = true }, label = { Text("Chapters") })
                        FilterChip(selected = !chapters, onClick = { chapters = false }, label = { Text("All tracks") })
                    }
                }
                when {
                    seriesId == null && series.size > 1 && query.isBlank() -> {
                        items(series, key = { it.id }) { audioSeries ->
                            val tracks = book.tracks.filter { it.seriesId == audioSeries.id }
                            LibraryEntry(audioSeries.title, "${tracks.size} tracks · ${tracks.count { it.id in downloaded }} downloaded", Icons.Rounded.Headphones) { navigate("series:${audioSeries.id}") }
                        }
                    }
                    query.isBlank() && chapters && chapter == null -> {
                        items(grouped.keys.toList(), key = { it }) { label ->
                            val tracks = grouped.getValue(label)
                            LibraryEntry(label, "${tracks.size} tracks · ${timeLabel(tracks.sumOf { it.durationMs })} · ${tracks.count { track -> progress.any { it.trackId == track.id && it.completed } }} done") { navigate("chapter:$label") }
                        }
                    }
                    else -> {
                        if (chapter != null && query.isBlank()) item { Text(chapter, style = MaterialTheme.typography.titleLarge) }
                        if (results.isEmpty()) item { EmptyMessage("No matching audio", "Try a track number, chapter name, page or exercise.") }
                        items(results, key = { it.id }) { track ->
                            TrackRow(track, progress.find { it.trackId == track.id }, enabled = track.id in downloaded,
                                context = if (series.size > 1 && seriesId == null) series.firstOrNull { it.id == track.seriesId }?.title else null) { open(track, null) }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun BackLink(label: String, goBack: () -> Unit) {
    TextButton(onClick = goBack, contentPadding = PaddingValues(start = 0.dp, end = 12.dp)) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, label, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun SearchField(query: String, change: (String) -> Unit, label: String, placeholder: String) {
    OutlinedTextField(value = query, onValueChange = change, label = { Text(label) }, placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Rounded.Search, null) }, trailingIcon = {
            if (query.isNotEmpty()) IconButton(onClick = { change("") }) { Icon(Icons.Rounded.Close, "Clear search") }
        }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
}

@Composable
private fun LibraryEntry(title: String, detail: String, icon: ImageVector? = null, click: () -> Unit) {
    Surface(onClick = click, color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (icon != null) Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            }
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(20.dp))
        }
    }
}

private fun bookLabel(book: BookVolume?): String = book?.displayTitle.orEmpty()

private fun resourceCounts(book: BookVolume): String = listOf(
    book.tracks.takeIf { it.isNotEmpty() }?.let { "${it.size} audio tracks" },
    book.documents.takeIf { it.isNotEmpty() }?.let { "${it.size} documents" },
).filterNotNull().joinToString(" · ")

@Composable
private fun EmptyMessage(title: String, detail: String) {
    Column(Modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ContinueListening(track: Track, position: Long, bookTitle: String, open: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large, onClick = open) {
        Column(Modifier.padding(20.dp)) {
            Text("CONTINUE LISTENING", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp)
            Text(bookTitle, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(track.title, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif)
                    Text("Track ${track.number} · ${timeLabel(position)} / ${timeLabel(track.durationMs)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
                Icon(Icons.Rounded.PlayCircleFilled, "Play ${track.title}", Modifier.size(48.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AudioDownloads(book: BookVolume, states: Map<String, ContentStatus>, readyCount: Int, bundled: Boolean,
    download: () -> Unit, cancel: () -> Unit, delete: () -> Unit) {
    val statuses = book.packages.map { states[it.id] ?: ContentStatus() }
    val active = statuses.firstOrNull { it.active }
    val failed = statuses.firstOrNull { it.phase == ContentPhase.FAILED }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${book.tracks.size} tracks · $readyCount downloaded", style = MaterialTheme.typography.bodyMedium)
        when {
            bundled -> Text("Included with this app. Ready offline.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            active != null -> DownloadProgress(active)
            failed != null -> Text(failed.error ?: "Couldn’t download the audio pack. Try again.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            readyCount < book.tracks.size -> Text("Download the audio pack to listen offline.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            else -> Text("Your audio is ready offline.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (active != null) OutlinedButton(onClick = cancel) { Text("Cancel download") }
            else if (readyCount < book.tracks.size) Button(onClick = download) {
                Icon(Icons.Rounded.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text(if (failed != null) "Retry audio download" else "Download audio")
            }
            if (readyCount > 0 && active == null && !bundled) TextButton(onClick = delete) { Text("Remove audio download") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DocumentRow(document: BookDocument, status: ContentStatus, ready: Boolean, bundled: Boolean,
    download: () -> Unit, cancel: () -> Unit, delete: () -> Unit, open: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(document.title, style = MaterialTheme.typography.titleMedium)
        Text(listOf(document.language.takeIf { it.isNotBlank() }, document.kind.replace('-', ' ').replace('_', ' ').takeIf { it.isNotBlank() },
            document.pageCount.takeIf { it > 0 }?.let { "$it pages" },
            document.bytes.takeIf { it > 0 }?.let(::fileSize)).filterNotNull().joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when {
            status.active -> DownloadProgress(status)
            status.phase == ContentPhase.FAILED -> Text(status.error ?: "Couldn’t download this document. Try again.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            ready -> Text(if (bundled) "Included with this app" else "Available offline", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when {
                status.active -> OutlinedButton(onClick = cancel) { Text("Cancel download") }
                ready -> {
                    OutlinedButton(onClick = open) { Icon(Icons.Rounded.Description, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Open PDF") }
                    if (!bundled) TextButton(onClick = delete) { Text("Remove download") }
                }
                else -> OutlinedButton(onClick = download) {
                    Icon(Icons.Rounded.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(if (status.phase == ContentPhase.FAILED) "Retry download" else "Download PDF")
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun DownloadProgress(status: ContentStatus) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (status.progress != null) LinearProgressIndicator(progress = { status.progress ?: 0f }, modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(when (status.phase) {
            ContentPhase.QUEUED -> "Waiting to download…"
            ContentPhase.WAITING -> "Waiting for a connection…"
            ContentPhase.PREPARING -> "Checking your files…"
            else -> if (status.totalBytes > 0) "${fileSize(status.downloadedBytes)} of ${fileSize(status.totalBytes)}" else "Downloading… ${fileSize(status.downloadedBytes)}"
        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun fileSize(bytes: Long): String = if (bytes >= 1_000_000) "%.1f MB".format(bytes / 1_000_000.0) else "${(bytes / 1000).coerceAtLeast(1)} KB"

@Composable
private fun TrackRow(track: Track, progress: TrackProgress?, enabled: Boolean = true, context: String? = null, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, color = Color.Transparent, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.padding(vertical = 16.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(track.number.toString().padStart(2, '0'), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge, modifier = Modifier.width(44.dp))
            Column(Modifier.weight(1f)) {
                if (!context.isNullOrBlank()) Text(context, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 4.dp))
                Text(track.title, style = MaterialTheme.typography.titleMedium)
                if (track.referenceLabel().isNotBlank()) Text(track.referenceLabel(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                Text(listOf(timeLabel(track.durationMs), if (!enabled) "Download audio pack to listen" else null).filterNotNull().joinToString(" · "), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
            }
            Icon(if (!enabled) Icons.Rounded.CloudDownload else if (progress?.completed == true) Icons.Rounded.CheckCircle else Icons.Rounded.PlayCircleOutline,
                if (!enabled) "Track ${track.number} is not downloaded" else if (progress?.completed == true) "Completed. Play track ${track.number}" else "Play track ${track.number}", tint = MaterialTheme.colorScheme.primary)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
@Composable
private fun MiniPlayer(track: Track, bookTitle: String, state: PlaybackState, toggle: () -> Unit, open: () -> Unit) {
    Surface(tonalElevation = 3.dp, onClick = open) {
        Column {
            LinearProgressIndicator(progress = { (state.positionMs.toFloat() / track.durationMs.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(2.dp))
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(bookTitle, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    Text("Track ${track.number} · ${track.title}", style = MaterialTheme.typography.titleSmall, maxLines = 2)
                    Text("${timeLabel(state.positionMs)} / ${timeLabel(track.durationMs)}", style = MaterialTheme.typography.labelSmall)
                }
                IconButton(onClick = toggle, enabled = state.ready) { Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playing) "Pause" else "Play") }
            }
        }
    }
}
@OptIn(ExperimentalLayoutApi::class)
@UnstableApi
@Composable
private fun PlayerPanel(vm: StudyViewModel, track: Track, state: PlaybackState, settings: PlaybackSettings, progress: TrackProgress?) {
    var pendingA by rememberSaveable(track.id) { mutableStateOf<Long?>(null) }
    var dragging by remember(track.id) { mutableStateOf<Float?>(null) }
    var speedMenu by remember { mutableStateOf(false) }
    var noteDialog by remember { mutableStateOf(false) }
    var bookmarkPosition by remember { mutableStateOf<Long?>(null) }
    LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Heading(bookLabel(vm.library.book(track.bookId)), track.title, "Track ${track.number.toString().padStart(2, '0')} · ${timeLabel(track.durationMs)}") }
        if (track.referenceLabel().isNotBlank()) item { Text(track.referenceLabel(), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (state.error != null) item { Text(state.error, color = MaterialTheme.colorScheme.error) }
        item {
            Slider(value = dragging ?: state.positionMs.toFloat().coerceIn(0f, track.durationMs.toFloat()), onValueChange = { dragging = it }, onValueChangeFinished = { dragging?.let { vm.seek(it.toLong()) }; dragging = null }, valueRange = 0f..track.durationMs.toFloat(), modifier = Modifier.semantics { contentDescription = "Playback position" })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(timeLabel(dragging?.toLong() ?: state.positionMs)); Text(timeLabel(track.durationMs)) }
        }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.skip(-1) }, enabled = vm.canSkip(-1)) { Icon(Icons.Rounded.SkipPrevious, "Previous track") }
            IconButton(onClick = { vm.shift(-10) }) { Icon(Icons.Rounded.Replay10, "Back 10 seconds") }
            FilledIconButton(onClick = vm::togglePlayback, modifier = Modifier.size(72.dp)) { Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playing) "Pause" else "Play", Modifier.size(40.dp)) }
            IconButton(onClick = { vm.shift(10) }) { Icon(Icons.Rounded.Forward10, "Forward 10 seconds") }
            IconButton(onClick = { vm.skip(1) }, enabled = vm.canSkip(1)) { Icon(Icons.Rounded.SkipNext, "Next track") }
        } }
        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                OutlinedButton(onClick = { speedMenu = true }) { Icon(Icons.Rounded.Speed, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("${settings.speed}×") }
                DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                    listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f).forEach { speed -> DropdownMenuItem(text = { Text("${speed}×") }, onClick = { vm.speed(speed); speedMenu = false }) }
                }
            }
            FilterChip(selected = settings.repeatTrack, onClick = { vm.repeat(!settings.repeatTrack) }, label = { Text("Repeat track") }, leadingIcon = { Icon(Icons.Rounded.RepeatOne, null, Modifier.size(18.dp)) })
        } }
        item { Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Practice a passage", style = MaterialTheme.typography.titleSmall)
                Text(if (state.loopA >= 0) "Repeating ${timeLabel(state.loopA)}–${timeLabel(state.loopB)}" else pendingA?.let { "A at ${timeLabel(it)}. Move ahead, then set B." } ?: "Set a start and end point to repeat a difficult passage.", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.loopA >= 0) OutlinedButton(onClick = { vm.loop(-1, -1); pendingA = null }) { Text("Clear A–B repeat") }
                    else {
                        OutlinedButton(onClick = { pendingA = state.positionMs }) { Text(if (pendingA == null) "Set A" else "Reset A") }
                        OutlinedButton(enabled = pendingA != null && LoopRange.create(pendingA ?: 0, state.positionMs, track.durationMs) != null, onClick = { pendingA?.let { vm.loop(it, state.positionMs) }; pendingA = null }) { Text("Set B") }
                        if (pendingA != null) IconButton(onClick = { pendingA = null }) { Icon(Icons.Rounded.Close, "Cancel A–B selection") }
                    }
                }
            }
        } }
        item { Row(Modifier.fillMaxWidth().toggleable(value = progress?.completed == true, role = Role.Checkbox, onValueChange = { vm.completed(track.id, it) }), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = progress?.completed == true, onCheckedChange = null)
            Text("Mark as completed", modifier = Modifier.weight(1f))
        } }
        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { bookmarkPosition = state.positionMs }) { Icon(Icons.Rounded.BookmarkAdd, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Bookmark") }
            OutlinedButton(onClick = { noteDialog = true }) { Icon(Icons.AutoMirrored.Rounded.Notes, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Notes") }
        } }
        if (!progress?.note.isNullOrBlank()) item { Text(progress!!.note, style = MaterialTheme.typography.bodyMedium) }
    }
    if (noteDialog) TextEditor("Track ${track.number} notes", progress?.note.orEmpty(), { noteDialog = false }) { vm.note(track.id, it); noteDialog = false }
    bookmarkPosition?.let { position -> TextEditor("Bookmark at ${timeLabel(position)}", "", { bookmarkPosition = null }) { vm.addBookmark(track, position, it); bookmarkPosition = null } }
}
@UnstableApi
@Composable
private fun Saved(vm: StudyViewModel, progress: List<TrackProgress>, bookmarks: List<Bookmark>, states: Map<String, ContentStatus>, modifier: Modifier, open: (Track, Long?) -> Unit, edit: (Long, String) -> Unit, delete: (Long) -> Unit) {
    val tracks = vm.tracks
    val downloaded = remember(states, tracks) { tracks.filter { vm.content.isReady(it) }.map { it.id }.toSet() }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Bookmark?>(null) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Heading("Your study space", "Keep the useful bits.") }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            listOf("Bookmarks", "Notes", "Completed").forEachIndexed { i, label -> FilterChip(selected = filter == i, onClick = { filter = i }, label = { Text(label) }) }
        } }
        if (filter == 0) {
            if (bookmarks.isEmpty()) item { Text("Bookmark a moment in the player to find it here.", Modifier.padding(vertical = 24.dp)) }
            items(bookmarks, key = { it.id }) { mark -> tracks.find { it.id == mark.trackId }?.let { track ->
                Surface(onClick = { open(track, mark.positionMs) }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(16.dp)) {
                        Text(bookLabel(vm.library.book(track.bookId)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Track ${track.number} · ${timeLabel(mark.positionMs)}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        Text(track.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
                        if (track.id !in downloaded) Text("Download this book’s audio from Library to play.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                        if (mark.note.isNotBlank()) Text(mark.note, Modifier.padding(top = 6.dp))
                        Row { TextButton(onClick = { editing = mark }) { Text("Edit") }; TextButton(onClick = { delete(mark.id) }) { Text("Delete") } }
                    }
                }
            } }
        } else {
            val selected = tracks.filter { track -> progress.any { it.trackId == track.id && if (filter == 1) it.note.isNotBlank() else it.completed } }
            if (selected.isEmpty()) item { Text(if (filter == 1) "Add a note in the player to find it here." else "Mark exercises as completed in the player.", Modifier.padding(vertical = 24.dp)) }
            items(selected, key = { it.id }) { track ->
                TrackRow(track, progress.find { it.trackId == track.id }, enabled = track.id in downloaded, context = bookLabel(vm.library.book(track.bookId))) { open(track, null) }
                if (filter == 1) Text(progress.first { it.trackId == track.id }.note, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    editing?.let { mark -> TextEditor("Edit bookmark", mark.note, { editing = null }) { edit(mark.id, it); editing = null } }
}
@Composable
private fun Settings(settings: PlaybackSettings, modifier: Modifier, continuous: (Boolean) -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp)) {
        Heading("Make it yours", "A little more focus.")
        Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Continuous playback", style = MaterialTheme.typography.titleMedium); Text("Play the next track automatically. Off by default for exercise practice.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
            Switch(checked = settings.continuous, onCheckedChange = continuous, modifier = Modifier.semantics { contentDescription = "Continuous playback" })
        }
        HorizontalDivider()
        Text("Ready whenever you are", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))
        Text("Download audio packs and documents from your book’s library. Downloaded files, bookmarks and notes work offline. Remove a download from the same screen to free up space.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
        Text("About the book references", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))
        Text("References come from spoken exercise announcements and official telc materials. Some chapter assignments follow the announced lesson sequence. Pages and exercises appear where they can be confirmed; your print edition may differ.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
        Text("Weiter · 2.0.0", Modifier.padding(top = 32.dp), style = MaterialTheme.typography.labelLarge)
        Text("An independently made personal companion for telc books. Original materials © telc gGmbH. This is not an official telc app.", Modifier.padding(top = 8.dp, bottom = 32.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable
private fun TextEditor(title: String, initial: String, dismiss: () -> Unit, save: (String) -> Unit) {
    var text by remember(initial) { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Your note") }, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth()) }, confirmButton = { TextButton(onClick = { save(text) }) { Text("Save") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
