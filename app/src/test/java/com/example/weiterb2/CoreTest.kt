package com.example.weiterb2

import org.junit.Assert.*
import org.junit.Test

class CoreTest {
    @Test fun `page search includes each reference and hides missing fields`() {
        val track = Track("b2-002", 2, "Freizeit", "audio/002.mp3", 10000, listOf(BookReference("Kursbuch", 1, "Freizeit", 9, "2b", "sample", "verified")))
        assertTrue(track.matches("page 9"))
        assertTrue(track.matches("2b"))
        assertTrue(track.matches("freizeit"))
        assertFalse(track.matches("page 90"))
        assertFalse(track.referenceLabel().contains("null"))
    }
    @Test fun `track number query supports zero padding`() {
        val track = Track("b2-007", 7, "Track 7", "audio/007.mp3", 10000)
        assertTrue(track.matches("007"))
        assertTrue(track.matches("track 7"))
        assertFalse(track.matches("70"))
        assertEquals("Other tracks", track.groupLabel)
        assertEquals("", track.referenceLabel())
    }
    @Test fun `chapter search does not confuse chapter one and ten`() {
        val one = Track("one", 2, "Freizeit", "audio/002.mp3", 10000, listOf(BookReference("Kursbuch", 1, "Freizeit")))
        val ten = Track("ten", 27, "Wissen", "audio/027.mp3", 10000, listOf(BookReference("Kursbuch", 10, "Wissen")))
        assertTrue(one.matches("chapter1"))
        assertTrue(one.matches("chapter 1"))
        assertFalse(ten.matches("chapter 1"))
        assertFalse(one.matches("page 999999999999999999"))
    }
    @Test fun `loop needs a useful range and stays inside audio`() {
        assertNull(LoopRange.create(4000, 4200, 10000))
        assertNull(LoopRange.create(-1, 4000, 10000))
        assertNull(LoopRange.create(Long.MAX_VALUE, Long.MIN_VALUE + 1000, 10000))
        assertNull(LoopRange.create(4000, 12000, 10000))
        assertNull(LoopRange.create(4000, 3000, 10000))
        val loop = LoopRange.create(1000, 4000, 10000)!!
        assertFalse(loop.shouldRepeat(3999))
        assertTrue(loop.shouldRepeat(4000))
    }
}
