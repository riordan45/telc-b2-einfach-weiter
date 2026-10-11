package com.example.weiterb2

import org.junit.Assert.*
import org.junit.Test

class LibraryCatalogTest {
    private fun track(id: String, book: String, series: String, number: Int) =
        Track(id, number, id, "", 1000, bookId = book, seriesId = series)

    @Test fun `queues stay within a volume and recording series`() {
        val regular = track("regular", "a1", "normal", 1)
        val slow = track("slow", "a1", "slow", 1)
        val other = track("other", "a2", "normal", 1)
        val catalog = LibraryCatalog(listOf(
            BookVolume("a1", "family", "Family", "A1", tracks = listOf(regular, slow)),
            BookVolume("a2", "family", "Family", "A2", tracks = listOf(other)),
        ))
        assertEquals(listOf(regular), catalog.queueFor(regular))
        assertEquals(listOf(slow), catalog.queueFor(slow))
        assertEquals(listOf(other), catalog.queueFor(other))
        assertEquals("A1", catalog.bookFor(regular)?.title)
    }

    @Test fun `separate series can reuse numbers but cannot reuse identities`() {
        val normal = track("normal-1", "a1", "normal", 1)
        val slow = track("slow-1", "a1", "slow", 1)
        val catalog = LibraryCatalog(listOf(BookVolume("a1", "family", "Family", "A1", tracks = listOf(normal, slow))))
        assertEquals(2, catalog.tracks.size)
        assertEquals(slow, catalog.track("slow-1"))
        assertThrows(IllegalArgumentException::class.java) {
            LibraryCatalog(listOf(BookVolume("a1", "family", "Family", "A1", tracks = listOf(normal, normal))))
        }
    }

    @Test fun `queues order numeric tracks across source packages`() {
        val later = track("later", "a1", "normal", 24)
        val earlier = track("earlier", "a1", "normal", 6)
        val catalog = LibraryCatalog(listOf(BookVolume("a1", "family", "Family", "A1", tracks = listOf(later, earlier))))
        assertEquals(listOf(earlier, later), catalog.queueFor(later))
    }
}
