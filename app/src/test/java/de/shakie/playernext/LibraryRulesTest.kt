package de.shakie.playernext

import de.shakie.playernext.data.FileRatings
import de.shakie.playernext.playback.Chapter
import de.shakie.playernext.playback.ChapterNav
import de.shakie.playernext.ui.settings.FolderTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryRulesTest {
    @Test
    fun fileRatingsNeverOverrideTheAppsOwn() {
        // First read of a file: only fills songs without a rating.
        assertEquals(8, FileRatings.decide(file = 8, last = null, app = 0))
        assertNull(FileRatings.decide(file = 8, last = null, app = 6))
        // Changed in another player: follows, as long as the app still has the old file value.
        assertEquals(10, FileRatings.decide(file = 10, last = 8, app = 8))
        assertNull(FileRatings.decide(file = 10, last = 8, app = 4))
        // A file that lost its rating (tag rewritten) never clears the app's.
        assertNull(FileRatings.decide(file = 0, last = 8, app = 8))
        assertNull(FileRatings.decide(file = 6, last = 6, app = 6))
    }

    @Test
    fun folderTreeCountsAndSelection() {
        val tree = FolderTree.build(
            listOf(
                "/storage/emulated/0/Music/Rock/a.mp3", "/storage/emulated/0/Music/Rock/Live/b.mp3",
                "/storage/emulated/0/Music/Jazz/c.mp3", "/storage/emulated/0/Download/d.mp3",
                "/storage/1A2B-3C4D/Hörbücher/e.m4b",
            )
        ) { root -> if (root.startsWith("/storage/emulated/")) "Intern" else "SD" }
        assertEquals(listOf("Intern", "SD"), tree.roots.map { it.name })
        val internal = tree.roots[0]
        assertEquals(4, internal.songs)
        assertEquals(listOf("Download", "Music"), internal.children.map { it.name })
        val music = internal.children[1]
        assertEquals(3, music.songs)
        assertEquals(listOf("Jazz", "Rock"), music.children.map { it.name })
        assertEquals(2, music.children[1].songs)

        val sel = FolderTree.Selection(setOf("/storage/emulated/0/Music/Rock/Live"))
        assertTrue(sel.partly("/storage/emulated/0/Music"))
        // Ticking a parent replaces the folders below it; those then count as covered.
        sel.toggle("/storage/emulated/0/Music")
        assertEquals(setOf("/storage/emulated/0/Music"), sel.paths)
        assertTrue(sel.covered("/storage/emulated/0/Music/Rock/Live"))
        assertTrue(sel.coveredByParent("/storage/emulated/0/Music/Jazz"))
        sel.toggle("/storage/emulated/0/Music/Jazz")
        assertEquals(setOf("/storage/emulated/0/Music"), sel.paths)
        assertFalse(sel.covered("/storage/emulated/0/MusicVideos"))
        sel.toggle("/storage/emulated/0/Music")
        assertTrue(sel.paths.isEmpty())
    }

    @Test
    fun chapterJumps() {
        val ch = listOf(Chapter(0, "A"), Chapter(60_000, "B"), Chapter(120_000, "C"))
        assertEquals(60_000L, ChapterNav.jumpTarget(ch, 10_000, forward = true))
        assertEquals(120_000L, ChapterNav.jumpTarget(ch, 60_000, forward = true))
        assertNull(ChapterNav.jumpTarget(ch, 130_000, forward = true))
        // Back: to the start of the chapter, within its first 3 s to the one before.
        assertEquals(60_000L, ChapterNav.jumpTarget(ch, 75_000, forward = false))
        assertEquals(0L, ChapterNav.jumpTarget(ch, 61_000, forward = false))
        assertNull(ChapterNav.jumpTarget(ch, 1_000, forward = false))
        assertNull(ChapterNav.jumpTarget(listOf(Chapter(0, "A")), 1_000, forward = true))
        assertEquals(2, ChapterNav.indexAt(ch, 500_000))
    }
}
