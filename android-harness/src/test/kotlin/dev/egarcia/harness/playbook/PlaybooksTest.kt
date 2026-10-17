package dev.egarcia.harness.playbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybooksTest {
    @Test
    fun parsesEntriesWithContinuationLinesAndComments() {
        val book = Playbooks.parse(
            """
            # my notes
            com.example.messagingapp: Messaging is the bottom tab.
              Unread threads show a dot.

            # another comment
            dev.egarcia.sampleapp: Reply with COUNT=<n>.
            """.trimIndent(),
        )
        assertEquals(2, book.size)
        assertEquals("Messaging is the bottom tab.\nUnread threads show a dot.", book.hintFor("com.example.messagingapp"))
        assertEquals("Reply with COUNT=<n>.", book.hintFor("dev.egarcia.sampleapp"))
    }

    @Test
    fun noteTextThatLooksLikeALabelDoesNotStartANewEntry() {
        val book = Playbooks.parse("com.app.one: First line.\nNote: this stays part of the hint.\nTip: so does this.")
        assertEquals(1, book.size)
        assertTrue(book.hintFor("com.app.one")!!.contains("Note: this stays part of the hint."))
    }

    @Test
    fun matchesByExactPackageOrPackagePrefix_mostSpecificWins() {
        val book = Playbooks.parse("com.google.android: broad.\ncom.google.android.apps.messaging: specific.")
        assertEquals("specific.", book.hintFor("com.google.android.apps.messaging"))
        assertEquals("broad.", book.hintFor("com.google.android.gm"))
        assertNull(book.hintFor("com.googleplex.other"))
        assertNull(book.hintFor("com.slack"))
        assertNull(book.hintFor(null))
    }

    @Test
    fun matchingIsCaseInsensitiveAndEmptyEntriesAreDropped() {
        val book = Playbooks.parse("Com.Example.App: Hello.\ncom.empty.entry:   \ncom.other.app: Hi.")
        assertEquals(2, book.size)
        assertEquals("Hello.", book.hintFor("com.example.app"))
        assertNull(book.hintFor("com.empty.entry"))
    }

    @Test
    fun aLongHintIsCappedSoItCannotFloodThePrompt() {
        val book = Playbooks.parse("com.big.app: " + "x".repeat(10_000))
        assertEquals(Playbooks.MAX_HINT, book.hintFor("com.big.app")!!.length)
    }

    @Test
    fun emptyTextGivesAnEmptyBook() {
        assertEquals(0, Playbooks.parse("").size)
        assertEquals(0, Playbooks.parse("just some words\nwithout an entry").size)
    }
}
