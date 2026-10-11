package com.example.weiterb2

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

internal data class ExpectedContentFile(val id: String, val member: String, val bytes: Long, val sha256: String)

/** Preparation never writes into the published content directory. */
internal object ContentVerifier {
    fun verify(file: File, bytes: Long, sha256: String) {
        require(bytes > 0 && file.length() == bytes) { "The downloaded file has an unexpected size." }
        require(sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "Missing file fingerprint." }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        require(digest.digest().joinToString("") { "%02x".format(it) }.equals(sha256, ignoreCase = true)) { "The downloaded file does not match the catalog." }
    }

    fun extract(archive: File, destination: File, expected: List<ExpectedContentFile>) {
        require(expected.isNotEmpty()) { "The audio package has no tracks." }
        require(expected.map { it.member }.distinct().size == expected.size) { "Duplicate expected archive member." }
        require(expected.map { it.id }.distinct().size == expected.size) { "Duplicate resource ID." }
        expected.forEach { safeId(it.id); safeMember(it.member); require(it.bytes > 0) }
        require(destination.mkdirs() || destination.isDirectory)
        try {
            ZipFile(archive).use { zip ->
                val entries = zip.entries().asSequence().toList()
                entries.forEach { safeMember(it.name) }
                require(entries.map { it.name }.distinct().size == entries.size) { "Duplicate archive path." }
                val byName = entries.associateBy { it.name }
                expected.forEach { file ->
                    val entry = requireNotNull(byName[file.member]) { "An expected audio track is missing." }
                    require(!entry.isDirectory && (entry.size < 0 || entry.size == file.bytes)) { "Unexpected audio track size." }
                    val output = File(destination, "${file.id}.mp3")
                    zip.getInputStream(entry).buffered().use { input ->
                        output.outputStream().buffered().use { stream ->
                            val buffer = ByteArray(64 * 1024)
                            var written = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                written += count
                                require(written <= file.bytes) { "An audio track exceeds its expected size." }
                                stream.write(buffer, 0, count)
                            }
                        }
                    }
                    verify(output, file.bytes, file.sha256)
                }
            }
        } catch (error: Exception) {
            destination.deleteRecursively()
            throw error
        }
    }

    fun safeId(value: String): String {
        require(value.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]*")) && value != "." && value != "..") { "Unsafe resource ID." }
        return value
    }

    private fun safeMember(value: String) {
        require(value.isNotBlank() && !value.startsWith('/') && !value.contains('\\') && !value.contains(':') && !value.contains('\u0000')) { "Unsafe archive path." }
        require(value.trimEnd('/').split('/').none { it == ".." || it == "." || it.isEmpty() }) { "Unsafe archive path." }
    }
}
