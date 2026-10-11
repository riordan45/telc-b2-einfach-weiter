package com.example.weiterb2

import java.text.Normalizer

data class BookReference(val section: String, val chapter: Int? = null, val chapterTitle: String? = null, val page: Int? = null, val exercise: String? = null, val evidence: String = "", val status: String = "verified", val timestampMs: Long? = null) {
    fun label(): String = listOfNotNull(section.takeIf { it.isNotBlank() }, chapter?.let { "Chapter $it" }, page?.let { "p. $it" }, exercise?.let { "Exercise $it" }).joinToString(" · ")
}
data class Track(val id: String, val number: Int, val title: String, val asset: String = "", val durationMs: Long, val references: List<BookReference> = emptyList(),
    val bookId: String = "", val seriesId: String = "", val packageId: String = "", val sourceMember: String = "", val sha256: String = "", val bytes: Long = 0) {
    val groupLabel: String get() = references.firstOrNull()?.let { ref ->
        if (ref.chapter != null) "${ref.section} · ${ref.chapter.toString().padStart(2, '0')} ${ref.chapterTitle.orEmpty()}".trim()
        else ref.section
    } ?: "Other tracks"
    fun referenceLabel(): String = references.map { it.label() }.distinct().joinToString("\n")
    fun matches(query: String): Boolean {
        val q = normalized(query.trim())
        if (q.isEmpty()) return true
        Regex("^(?:track\\s*)?0*(\\d+)$").matchEntire(q)?.let { return it.groupValues[1].toIntOrNull() == number }
        Regex("^(?:page|p[.]?|seite)\\s*(\\d+)$").matchEntire(q)?.let { match ->
            val page = match.groupValues[1].toIntOrNull() ?: return false
            return references.any { it.page == page }
        }
        Regex("^(?:chapter|lektion)\\s*(\\d+)$").matchEntire(q)?.let { match ->
            val chapter = match.groupValues[1].toIntOrNull() ?: return false
            return references.any { it.chapter == chapter }
        }
        Regex("^(?:exercise|aufgabe)\\s*(\\d+[a-z]?)$").matchEntire(q)?.let { match ->
            return references.any { match.groupValues[1] in exerciseIdentifiers(it.exercise.orEmpty()) }
        }
        val haystack = normalized("$title $groupLabel ${referenceLabel()}")
        return q.split(Regex("\\s+")).all { it in haystack }
    }
}
data class AudioSeries(val id: String, val title: String, val position: Int = 0)
data class SourcePackage(val id: String, val bookId: String, val title: String, val url: String, val bytes: Long, val sha256: String, val position: Int = 0)
data class BookDocument(val id: String, val bookId: String, val title: String, val language: String, val kind: String,
    val url: String, val bytes: Long, val sha256: String, val pageCount: Int, val position: Int = 0,
    val references: List<BookReference> = emptyList())
data class BookVolume(val id: String, val familyId: String, val familyTitle: String, val title: String, val position: Int = 0,
    val series: List<AudioSeries> = emptyList(), val packages: List<SourcePackage> = emptyList(),
    val tracks: List<Track> = emptyList(), val documents: List<BookDocument> = emptyList(),
    val level: String = "", val edition: String = "", val isbn: String = "") {
    val displayTitle: String get() = if (title == familyTitle) title else "$familyTitle · $title"
}

class LibraryCatalog(val books: List<BookVolume>) {
    val tracks = books.flatMap { it.tracks }
    val documents = books.flatMap { it.documents }
    val packages = books.flatMap { it.packages }
    private val booksById = books.associateBy { it.id }
    private val tracksById = tracks.associateBy { it.id }
    private val documentsById = documents.associateBy { it.id }
    private val packagesById = packages.associateBy { it.id }
    init {
        require(booksById.size == books.size) { "Duplicate book identity" }
        val resourceIds = tracks.map { it.id } + documents.map { it.id }
        require(resourceIds.distinct().size == resourceIds.size) { "Duplicate resource identity" }
        require(packagesById.size == packages.size) { "Duplicate package identity" }
        books.forEach { book ->
            require(book.tracks.all { it.bookId == book.id }) { "Track assigned to wrong book" }
            require(book.documents.all { it.bookId == book.id }) { "Document assigned to wrong book" }
            require(book.packages.all { it.bookId == book.id }) { "Package assigned to wrong book" }
        }
    }
    fun book(id: String): BookVolume? = booksById[id]
    fun track(id: String): Track? = tracksById[id]
    fun document(id: String): BookDocument? = documentsById[id]
    fun sourcePackage(id: String): SourcePackage? = packagesById[id]
    fun bookFor(track: Track): BookVolume? = book(track.bookId)
    fun queueFor(track: Track): List<Track> = book(track.bookId)?.tracks.orEmpty().filter { it.seriesId == track.seriesId }.sortedBy { it.number }
}
data class LoopRange(val a: Long, val b: Long) {
    fun shouldRepeat(position: Long): Boolean = position >= b
    companion object { fun create(a: Long, b: Long, duration: Long): LoopRange? = if (a >= 0 && b > a && b <= duration && b - a >= 500) LoopRange(a, b) else null }
}
fun timeLabel(ms: Long): String { val total = ms.coerceAtLeast(0) / 1000; return "%d:%02d".format(total / 60, total % 60) }
internal fun normalized(value: String): String = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")

/** Expand explicit joint labels such as 5a und b, without inventing range members. */
private fun exerciseIdentifiers(value: String): Set<String> {
    val text = normalized(value).substringBefore('(')
    if (text.contains("horen") || text.contains("horverstehen")) return emptySet()
    var number = ""
    return buildSet {
        Regex("\\b(\\d+)\\s*([a-z])?\\b|\\b([a-z])\\b").findAll(text).forEach { token ->
            if (token.groupValues[1].isNotEmpty()) {
                number = token.groupValues[1]
                add(number + token.groupValues[2])
            } else if (number.isNotEmpty()) add(number + token.groupValues[3])
        }
    }
}
