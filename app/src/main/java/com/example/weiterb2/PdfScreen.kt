package com.example.weiterb2

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Displays original documents without changing or rebuilding their content. */
@Composable
fun PdfScreen(document: BookDocument, file: File, initialPage: Int, onPageChanged: (Int) -> Unit, onBack: () -> Unit) {
    var page by rememberSaveable(document.id) { mutableIntStateOf(initialPage.coerceAtLeast(0)) }
    var scale by remember(document.id, page) { mutableFloatStateOf(1f) }
    var panX by remember(document.id, page) { mutableFloatStateOf(0f) }
    var panY by remember(document.id, page) { mutableFloatStateOf(0f) }
    var jumpOpen by remember { mutableStateOf(false) }
    var jump by remember { mutableStateOf("") }
    val rendered by produceState<PdfPage?>(null, file.absolutePath, page) {
        value = null
        value = withContext(Dispatchers.IO) {
            try {
                PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
                    require(renderer.pageCount > 0) { "This document has no pages." }
                    val actualPage = page.coerceIn(0, renderer.pageCount - 1)
                    renderer.openPage(actualPage).use { pdf ->
                        val width = minOf(1600, pdf.width * 2).coerceAtLeast(1)
                        val height = (width.toLong() * pdf.height / pdf.width.coerceAtLeast(1)).coerceIn(1, 4000).toInt()
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        pdf.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        PdfPage(bitmap, actualPage, renderer.pageCount)
                    }
                }
            } catch (_: Exception) { PdfPage(error = "Couldn’t open this document. Download it again and retry.") }
        }
    }
    LaunchedEffect(rendered?.index) { rendered?.takeIf { it.bitmap != null }?.let { if (page != it.index) page = it.index; onPageChanged(it.index) } }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to documents") }
            Column(Modifier.weight(1f)) {
                Text(document.title, style = MaterialTheme.typography.titleMedium)
                if (document.language.isNotBlank()) Text(document.language, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(MaterialTheme.colorScheme.surfaceContainerLow), contentAlignment = Alignment.Center) {
            val image = rendered?.bitmap
            when {
                rendered?.error != null -> Text(rendered!!.error!!, Modifier.padding(24.dp))
                image == null -> CircularProgressIndicator()
                else -> Image(
                    image.asImageBitmap(), "Page ${rendered!!.index + 1} of ${rendered!!.count} · ${document.title}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = panX, translationY = panY)
                        .pointerInput(page) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 4f)
                                panX = (panX + pan.x).coerceIn(-size.width * (scale - 1) / 2, size.width * (scale - 1) / 2)
                                panY = (panY + pan.y).coerceIn(-size.height * (scale - 1) / 2, size.height * (scale - 1) / 2)
                            }
                        },
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { page-- }, enabled = rendered?.bitmap != null && page > 0) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Previous page") }
            TextButton(onClick = { jump = (page + 1).toString(); jumpOpen = true }, enabled = rendered?.bitmap != null) { Text("${page + 1} / ${rendered?.count ?: document.pageCount}") }
            IconButton(onClick = { scale = (scale - 0.5f).coerceAtLeast(1f); panX = 0f; panY = 0f }, enabled = scale > 1f) { Icon(Icons.Rounded.ZoomOut, "Zoom out") }
            IconButton(onClick = { scale = (scale + 0.5f).coerceAtMost(4f) }, enabled = rendered?.bitmap != null && scale < 4f) { Icon(Icons.Rounded.ZoomIn, "Zoom in") }
            IconButton(onClick = { page++ }, enabled = rendered?.bitmap != null && page < rendered!!.count - 1) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Next page") }
        }
    }
    if (jumpOpen) AlertDialog(
        onDismissRequest = { jumpOpen = false }, title = { Text("Go to page") },
        text = { OutlinedTextField(jump, { value -> jump = value.filter(Char::isDigit).take(6) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), label = { Text("Page number") }) },
        confirmButton = { TextButton(onClick = { jump.toIntOrNull()?.let { page = (it - 1).coerceIn(0, (rendered?.count ?: 1) - 1) }; jumpOpen = false }, enabled = jump.toIntOrNull()?.let { it in 1..(rendered?.count ?: 0) } == true) { Text("Open") } },
        dismissButton = { TextButton(onClick = { jumpOpen = false }) { Text("Cancel") } },
    )
}

private data class PdfPage(val bitmap: Bitmap? = null, val index: Int = 0, val count: Int = 0, val error: String? = null)
