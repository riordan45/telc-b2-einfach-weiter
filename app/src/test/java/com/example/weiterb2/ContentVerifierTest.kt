package com.example.weiterb2

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class ContentVerifierTest {
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun archive(folder: File, entries: List<Pair<String, ByteArray>>): File = File(folder, "source.zip").also { file ->
        ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (path, bytes) -> zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry() } }
    }
    private fun withFolder(block: (File) -> Unit) {
        val folder = Files.createTempDirectory("content-verifier").toFile()
        try { block(folder) } finally { folder.deleteRecursively() }
    }
    @Test fun `extracts only expected original bytes into stable resource filenames`() = withFolder { folder ->
        val audio = "original audio".toByteArray()
        val zip = archive(folder, listOf("book/001.mp3" to audio, "readme.txt" to "notes".toByteArray()))
        val destination = File(folder, "prepared")
        ContentVerifier.extract(zip, destination, listOf(ExpectedContentFile("track", "book/001.mp3", audio.size.toLong(), digest(audio))))
        assertArrayEquals(audio, File(destination, "track.mp3").readBytes())
        assertEquals(listOf("track.mp3"), destination.listFiles()!!.map { it.name })
    }
    @Test fun `rejects traversal even in an unexpected archive member`() = withFolder { folder ->
        val bytes = byteArrayOf(1, 2, 3)
        val zip = archive(folder, listOf("001.mp3" to bytes, "../escape" to bytes))
        assertThrows(IllegalArgumentException::class.java) {
            ContentVerifier.extract(zip, File(folder, "prepared"), listOf(ExpectedContentFile("track", "001.mp3", 3, digest(bytes))))
        }
        assertFalse(File(folder.parentFile, "escape").exists())
    }
    @Test fun `rejects missing and corrupt members without publishing files`() = withFolder { folder ->
        val bytes = byteArrayOf(1, 2, 3)
        val zip = archive(folder, listOf("001.mp3" to bytes))
        assertThrows(IllegalArgumentException::class.java) {
            ContentVerifier.extract(zip, File(folder, "missing"), listOf(ExpectedContentFile("track", "002.mp3", 3, digest(bytes))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ContentVerifier.extract(zip, File(folder, "corrupt"), listOf(ExpectedContentFile("track", "001.mp3", 3, digest(byteArrayOf(3, 2, 1)))))
        }
    }
    @Test fun `bounds extracted size and verifies the package itself`() = withFolder { folder ->
        val bytes = byteArrayOf(1, 2, 3, 4)
        val zip = archive(folder, listOf("001.mp3" to bytes))
        assertThrows(IllegalArgumentException::class.java) {
            ContentVerifier.extract(zip, File(folder, "oversize"), listOf(ExpectedContentFile("track", "001.mp3", 3, digest(bytes))))
        }
        ContentVerifier.verify(zip, zip.length(), digest(zip.readBytes()))
        assertThrows(IllegalArgumentException::class.java) { ContentVerifier.verify(zip, zip.length(), "0".repeat(64)) }
        assertThrows(IllegalArgumentException::class.java) { ContentVerifier.verify(zip, zip.length() + 1, digest(zip.readBytes())) }
    }
}
