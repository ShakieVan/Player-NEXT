package de.shakie.playernext.update

import de.shakie.playernext.update.ReleaseNotes.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesTest {
    // The notes of release 1.1.0 as published (wrapped at 80 columns).
    private val v110 = """
        ## Player NEXT 1.1.0

        Erste öffentliche Version.

        ### Neu

        - **Widget „Plus 4×2“** im Aufbau von PlayerPro: Position in der
          Warteschlange, Titel, Interpret, Album und große Tasten ⏪ ▶ ⏩.
        - **Zufallswiedergabe ohne Wiederholungen** – auch bei zehntausenden Titeln
          kommt jeder Titel genau einmal.

        ### Hinweis

        Diese Version ist mit dem neuen Release-Schlüssel signiert. Wer eine
        frühere Testversion installiert hat, muss sie einmal deinstallieren.
    """.trimIndent()

    @Test
    fun headingsItemsAndParagraphsWithoutHardBreaks() {
        val blocks = ReleaseNotes.parse(v110)
        assertEquals(
            listOf(Kind.HEADING, Kind.PARAGRAPH, Kind.HEADING, Kind.BULLET, Kind.BULLET, Kind.HEADING, Kind.PARAGRAPH),
            blocks.map { it.kind },
        )
        assertEquals("Player NEXT 1.1.0", blocks[0].text)
        assertEquals("Neu", blocks[2].text)
        assertEquals(
            "Widget „Plus 4×2“ im Aufbau von PlayerPro: Position in der Warteschlange, Titel, Interpret, Album und große Tasten ⏪ ▶ ⏩.",
            blocks[3].text,
        )
        assertEquals(
            "Diese Version ist mit dem neuen Release-Schlüssel signiert. Wer eine frühere Testversion installiert hat, muss sie einmal deinstallieren.",
            blocks[6].text,
        )
        assertTrue(blocks.none { '\n' in it.text || "**" in it.text || it.text.startsWith("#") })
    }

    @Test
    fun boldRangesPointAtTheBoldWords() {
        val item = ReleaseNotes.parse(v110)[4]
        assertEquals(1, item.bold.size)
        assertEquals("Zufallswiedergabe ohne Wiederholungen", item.text.substring(item.bold[0].first, item.bold[0].last + 1))
    }

    @Test
    fun linksCodeAndPlainNotes() {
        val blocks = ReleaseNotes.parse(
            "Testversionen aus [Player-NEXT-Beta](https://github.com/ShakieVan/Player-NEXT-Beta), Datei `Player-NEXT-1.1.2.apk`.\n" +
                "1. erst laden\n2) dann installieren\n\n* Stern-Punkt\n+ Plus-Punkt"
        )
        assertEquals("Testversionen aus Player-NEXT-Beta, Datei Player-NEXT-1.1.2.apk.", blocks[0].text)
        assertEquals("1. erst laden", blocks[1].text)
        assertEquals("2) dann installieren", blocks[2].text)
        assertEquals(listOf(Kind.BULLET, Kind.BULLET), blocks.drop(3).map { it.kind })
        // Notes written without Markdown stay as they are; a single "**" or file_names are left alone.
        val plain = ReleaseNotes.parse("Neu:\n\n- Ordner shuffle_bag bleibt, 2 ** 3 auch.")
        assertEquals("Neu:", plain[0].text)
        assertEquals("Ordner shuffle_bag bleibt, 2 ** 3 auch.", plain[1].text)
        assertTrue(plain[1].bold.isEmpty())
        assertTrue(ReleaseNotes.parse("").isEmpty())
        assertTrue(ReleaseNotes.parse("\r\n\r\n").isEmpty())
    }
}
