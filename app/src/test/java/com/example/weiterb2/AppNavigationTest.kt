package com.example.weiterb2

import org.junit.Assert.*
import org.junit.Test

class AppNavigationTest {
    @Test fun `chapter back returns to home and home leaves Back to Android`() {
        val home = AppNavigation()
        assertFalse(home.canGoBack)
        val chapter = home.open("chapter-1")
        assertTrue(chapter.canGoBack)
        assertEquals(home, chapter.back())
        assertEquals(home, home.back())
    }

    @Test fun `future menus and deeper screens unwind one level at a time`() {
        val menu = AppNavigation().selectMenu("future-vocabulary-menu")
        val list = menu.open("word-list")
        val word = list.open("word")
        assertEquals(list, word.back())
        assertEquals(menu, word.back().back())
        assertEquals(AppNavigation(), word.back().back().back())
        assertTrue(menu.canGoBack)
        assertFalse(word.back().back().back().canGoBack)
    }

    @Test fun `switching or reselecting a menu starts at that menu root`() {
        val chapter = AppNavigation().open("chapter-1")
        assertEquals(AppNavigation("saved"), chapter.selectMenu("saved"))
        assertEquals(AppNavigation(), chapter.selectMenu(AppNavigation.HOME))
        assertEquals(AppNavigation(), chapter.selectMenu("settings").back())
    }
}
