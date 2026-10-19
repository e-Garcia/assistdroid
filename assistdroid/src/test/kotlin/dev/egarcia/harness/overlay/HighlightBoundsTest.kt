package dev.egarcia.harness.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HighlightBoundsTest {

    @Test
    fun validBoundsAreKeptAsIs() {
        assertEquals(HighlightBounds(10, 20, 110, 70), HighlightBounds.from(listOf(10, 20, 110, 70)))
    }

    @Test
    fun nullBoundsMeansNoHighlight() {
        assertNull(HighlightBounds.from(null))
    }

    @Test
    fun wrongSizedListMeansNoHighlight() {
        assertNull(HighlightBounds.from(listOf(0, 0, 10)))
        assertNull(HighlightBounds.from(listOf(0, 0, 10, 10, 10)))
        assertNull(HighlightBounds.from(emptyList()))
    }

    @Test
    fun zeroOrNegativeWidthOrHeightMeansNoHighlight() {
        assertNull("zero width", HighlightBounds.from(listOf(50, 0, 50, 100)))
        assertNull("zero height", HighlightBounds.from(listOf(0, 50, 100, 50)))
        assertNull("inverted (right before left)", HighlightBounds.from(listOf(100, 0, 50, 100)))
        assertNull("inverted (bottom before top)", HighlightBounds.from(listOf(0, 100, 100, 50)))
    }
}
