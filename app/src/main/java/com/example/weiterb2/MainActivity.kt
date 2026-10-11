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
import androidx.compose.ui.text.font.FontWeight
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
    var navigation by rememberSaveable(stateSaver = listSaver<AppNavigation, String>(
        save = { listOf(it.menu) + it.path },
        restore = { AppNavigation(it.first(), it.drop(1)) },
    )) { mutableStateOf(AppNavigation()) }
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    val current = vm.tracks.find { it.id == playback.trackId }
    fun open(track: Track, position: Long? = null) { vm.select(track, position); playerOpen = true }
    // Menus register their content here; adding one needs no new Back-button logic.
    val menus = listOf(
        TopLevelMenu(AppNavigation.HOME, "Library", Icons.Rounded.Headphones) { padding ->
            Library(vm.tracks, progress, playback.ready, Modifier.padding(padding), ::open,
                group = navigation.path.lastOrNull(),
                selectGroup = { navigation = navigation.open(it) },
                clearGroup = { navigation = navigation.copy(path = emptyList()) },
                goBack = { navigation = navigation.back() })
        },
        TopLevelMenu("saved", "Saved", Icons.Rounded.Bookmarks) { padding ->
            Saved(vm.tracks, progress, bookmarks, Modifier.padding(padding), ::open, vm::editBookmark, vm::deleteBookmark)
        },
        TopLevelMenu("settings", "Settings", Icons.Rounded.Settings) { padding ->
            Settings(settings, Modifier.padding(padding), vm::continuous)
        },
    )
    BackHandler(enabled = playerOpen || navigation.canGoBack) {
        if (playerOpen) playerOpen = false else navigation = navigation.back()
    }
    Scaffold(
        bottomBar = {
            Column {
                if (current != null) MiniPlayer(current, playback, vm::togglePlayback) { playerOpen = true }
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
private fun Heading(eyebrow: String, title: String) {
    Column(Modifier.padding(top = 20.dp, bottom = 12.dp)) {
        if (eyebrow.isNotBlank()) Text(eyebrow.uppercase(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, letterSpacing = 1.8.sp)
        Text(title, fontFamily = FontFamily.Serif, fontSize = 34.sp, lineHeight = 40.sp, modifier = Modifier.padding(top = if (eyebrow.isNotBlank()) 8.dp else 0.dp))
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Library(tracks: List<Track>, progress: List<TrackProgress>, ready: Boolean, modifier: Modifier, open: (Track, Long?) -> Unit,
    group: String?, selectGroup: (String) -> Unit, clearGroup: () -> Unit, goBack: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var chapters by rememberSaveable { mutableStateOf(true) }
    val grouped = remember(tracks) { tracks.groupBy { it.groupLabel } }
    val latest = progress.filter { it.lastPlayed > 0 }.maxByOrNull { it.lastPlayed }
    val resume = tracks.find { it.id == latest?.trackId } ?: tracks.getOrNull(1)
    val results = tracks.filter { (query.isNotBlank() || group == null || it.groupLabel == group) && it.matches(query) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Heading("", "Einfach weiter! Deutsch B2") }
        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoTag("63 tracks · offline", Icons.Rounded.OfflinePin)
            InfoTag("${progress.count { it.completed }} completed")
        } }
        if (resume != null && query.isBlank() && group == null) item {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large, onClick = { if (ready) open(resume, null) }) {
                Column(Modifier.padding(20.dp)) {
                    Text(if (latest == null) "START LISTENING" else "CONTINUE LISTENING", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(resume.title, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif)
                            Text("Track ${resume.number} · ${timeLabel(latest?.positionMs ?: 0)} / ${timeLabel(resume.durationMs)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                        }
                        Icon(Icons.Rounded.PlayCircleFilled, "Play ${resume.title}", Modifier.size(48.dp))
                    }
                }
            }
        }
        item { OutlinedTextField(value = query, onValueChange = { query = it }, placeholder = { Text("Track, chapter, page or exercise") }, label = { Text("Search audio") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear search") } }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = chapters, onClick = { chapters = true; clearGroup() }, label = { Text("Chapters") })
            FilterChip(selected = !chapters, onClick = { chapters = false; clearGroup() }, label = { Text("All tracks") })
        } }
        if (!ready) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Preparing your audio library…", style = MaterialTheme.typography.bodySmall) }
        if (query.isBlank() && chapters && group == null) {
            items(grouped.keys.toList(), key = { it }) { label ->
                val chapterTracks = grouped.getValue(label)
                Surface(onClick = { selectGroup(label) }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(label, style = MaterialTheme.typography.titleMedium)
                            Text("${chapterTracks.size} tracks · ${timeLabel(chapterTracks.sumOf { it.durationMs })} · ${chapterTracks.count { track -> progress.any { it.trackId == track.id && it.completed } }} done", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                        }
                        Icon(Icons.Rounded.ChevronRight, null)
                    }
                }
            }
        } else {
            if (group != null && query.isBlank()) item { Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = goBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to chapters") }
                Text(group.orEmpty(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            } }
            if (results.isEmpty()) item { Text("No matching audio. Try a track number or chapter name.", Modifier.padding(vertical = 24.dp)) }
            items(results, key = { it.id }) { track -> TrackRow(track, progress.find { it.trackId == track.id }, ready) { open(track, null) } }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}
@Composable
private fun InfoTag(label: String, icon: ImageVector? = null) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (icon != null) Icon(icon, null, Modifier.size(16.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
@Composable
private fun TrackRow(track: Track, progress: TrackProgress?, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, color = Color.Transparent, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.padding(vertical = 16.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(track.number.toString().padStart(2, '0'), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge, modifier = Modifier.width(44.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, style = MaterialTheme.typography.titleMedium)
                if (track.referenceLabel().isNotBlank()) Text(track.referenceLabel(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                Text(timeLabel(track.durationMs), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
            }
            Icon(if (progress?.completed == true) Icons.Rounded.CheckCircle else Icons.Rounded.PlayCircleOutline, if (progress?.completed == true) "Completed. Play track ${track.number}" else "Play track ${track.number}", tint = MaterialTheme.colorScheme.primary)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
@Composable
private fun MiniPlayer(track: Track, state: PlaybackState, toggle: () -> Unit, open: () -> Unit) {
    Surface(tonalElevation = 3.dp, onClick = open) {
        Column {
            LinearProgressIndicator(progress = { (state.positionMs.toFloat() / track.durationMs).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(2.dp))
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
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
        item { Heading("Track ${track.number.toString().padStart(2, '0')} · ${timeLabel(track.durationMs)}", track.title) }
        if (track.referenceLabel().isNotBlank()) item { Text(track.referenceLabel(), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (state.error != null) item { Text(state.error, color = MaterialTheme.colorScheme.error) }
        item {
            Slider(value = dragging ?: state.positionMs.toFloat().coerceIn(0f, track.durationMs.toFloat()), onValueChange = { dragging = it }, onValueChangeFinished = { dragging?.let { vm.seek(it.toLong()) }; dragging = null }, valueRange = 0f..track.durationMs.toFloat(), modifier = Modifier.semantics { contentDescription = "Playback position" })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(timeLabel(dragging?.toLong() ?: state.positionMs)); Text(timeLabel(track.durationMs)) }
        }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.skip(-1) }, enabled = track.number > 1) { Icon(Icons.Rounded.SkipPrevious, "Previous track") }
            IconButton(onClick = { vm.shift(-10) }) { Icon(Icons.Rounded.Replay10, "Back 10 seconds") }
            FilledIconButton(onClick = vm::togglePlayback, modifier = Modifier.size(72.dp)) { Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playing) "Pause" else "Play", Modifier.size(40.dp)) }
            IconButton(onClick = { vm.shift(10) }) { Icon(Icons.Rounded.Forward10, "Forward 10 seconds") }
            IconButton(onClick = { vm.skip(1) }, enabled = track.number < 63) { Icon(Icons.Rounded.SkipNext, "Next track") }
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
@Composable
private fun Saved(tracks: List<Track>, progress: List<TrackProgress>, bookmarks: List<Bookmark>, modifier: Modifier, open: (Track, Long?) -> Unit, edit: (Long, String) -> Unit, delete: (Long) -> Unit) {
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Bookmark?>(null) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            listOf("Bookmarks", "Notes", "Completed").forEachIndexed { i, label -> FilterChip(selected = filter == i, onClick = { filter = i }, label = { Text(label) }) }
        } }
        if (filter == 0) {
            if (bookmarks.isEmpty()) item { Text("Bookmark a moment in the player to find it here.", Modifier.padding(vertical = 24.dp)) }
            items(bookmarks, key = { it.id }) { mark -> tracks.find { it.id == mark.trackId }?.let { track ->
                Surface(onClick = { open(track, mark.positionMs) }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Track ${track.number} · ${timeLabel(mark.positionMs)}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        Text(track.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
                        if (mark.note.isNotBlank()) Text(mark.note, Modifier.padding(top = 6.dp))
                        Row { TextButton(onClick = { editing = mark }) { Text("Edit") }; TextButton(onClick = { delete(mark.id) }) { Text("Delete") } }
                    }
                }
            } }
        } else {
            val selected = tracks.filter { track -> progress.any { it.trackId == track.id && if (filter == 1) it.note.isNotBlank() else it.completed } }
            if (selected.isEmpty()) item { Text(if (filter == 1) "Add a note in the player to find it here." else "Mark exercises as completed in the player.", Modifier.padding(vertical = 24.dp)) }
            items(selected, key = { it.id }) { track ->
                TrackRow(track, progress.find { it.trackId == track.id }) { open(track, null) }
                if (filter == 1) Text(progress.first { it.trackId == track.id }.note, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    editing?.let { mark -> TextEditor("Edit bookmark", mark.note, { editing = null }) { edit(mark.id, it); editing = null } }
}
@Composable
private fun Settings(settings: PlaybackSettings, modifier: Modifier, continuous: (Boolean) -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Continuous playback", style = MaterialTheme.typography.titleMedium); Text("Play the next track automatically. Off by default for exercise practice.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
            Switch(checked = settings.continuous, onCheckedChange = continuous, modifier = Modifier.semantics { contentDescription = "Continuous playback" })
        }
        HorizontalDivider()
        Text("About the book references", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))
        Text("References come from spoken exercise announcements and official telc samples. Some chapter assignments follow the announced lesson sequence. Page numbers appear only where the full-B2 sample confirms them; your print edition may differ.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
        Text("Weiter B2 · 1.0.0", Modifier.padding(top = 32.dp, bottom = 32.dp), style = MaterialTheme.typography.labelLarge)
    }
}
@Composable
private fun TextEditor(title: String, initial: String, dismiss: () -> Unit, save: (String) -> Unit) {
    var text by remember(initial) { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Your note") }, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth()) }, confirmButton = { TextButton(onClick = { save(text) }) { Text("Save") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
