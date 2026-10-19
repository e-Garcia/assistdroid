package dev.egarcia.harness.overlay

/** A screen-pixel rectangle to draw a highlight around. Pure data, no Android dependency, so it's unit-tested
 * directly; [GuideOverlay] is the (untested here) WindowManager glue that renders it. */
data class HighlightBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    companion object {
        /** [bounds] is the element's [left, top, right, bottom] from a snapshot, or null if the guide step
         * wasn't about a specific element. Returns null for anything degenerate (missing, wrong size, zero or
         * negative width/height) so the caller can fall back to showing the instruction with no highlight box
         * rather than drawing a nonsensical rectangle. */
        fun from(bounds: List<Int>?): HighlightBounds? {
            if (bounds == null || bounds.size != 4) return null
            val (left, top, right, bottom) = bounds
            if (right <= left || bottom <= top) return null
            return HighlightBounds(left, top, right, bottom)
        }
    }
}
