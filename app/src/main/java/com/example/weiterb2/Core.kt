package com.example.weiterb2

import java.text.Normalizer

data class BookReference(val section: String, val chapter: Int? = null, val chapterTitle: String? = null, val page: Int? = null, val exercise: String? = null, val evidence: String = "", val status: String = "verified", val timestampMs: Long? = null) {
    fun label(): String = listOfNotNull(section.takeIf { it.isNotBlank() }, chapter?.let { "Chapter $it" }, page?.let { "p. $it" }, exercise?.let { "Exercise $it" }).joinToString(" · ")
}
data class Track(val id: String, val number: Int, val title: String, val asset: String, val durationMs: Long, val references: List<BookReference> = emptyList()) {
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
            return references.any { normalized(it.exercise.orEmpty()) == match.groupValues[1] }
        }
        val haystack = normalized("$title $groupLabel ${referenceLabel()}")
        return q.split(Regex("\\s+")).all { it in haystack }
    }
}
data class LoopRange(val a: Long, val b: Long) {
    fun shouldRepeat(position: Long): Boolean = position >= b
    companion object { fun create(a: Long, b: Long, duration: Long): LoopRange? = if (a >= 0 && b > a && b <= duration && b - a >= 500) LoopRange(a, b) else null }
}
fun timeLabel(ms: Long): String { val total = ms.coerceAtLeast(0) / 1000; return "%d:%02d".format(total / 60, total % 60) }
internal fun normalized(value: String): String = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
