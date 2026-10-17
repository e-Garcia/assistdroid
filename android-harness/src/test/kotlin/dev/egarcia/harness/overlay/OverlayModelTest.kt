package ga.egarcia.harness.overlay

import ga.egarcia.harness.overlay.OverlayMode.BUBBLE
import ga.egarcia.harness.overlay.OverlayMode.PROMPT
import ga.egarcia.harness.overlay.OverlayMode.RESULT
import ga.egarcia.harness.overlay.OverlayMode.RUNNING
import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayModelTest {
    @Test
    fun aRunningTaskAlwaysShowsThePill() {
        for (mode in OverlayMode.entries) assertEquals(mode.name, RUNNING, OverlayModel.next(true, mode))
    }

    @Test
    fun whenARunEndsTheOutcomeCardAppears() = assertEquals(RESULT, OverlayModel.next(false, RUNNING))

    @Test
    fun otherwiseTheUsersChoiceStands() {
        assertEquals(BUBBLE, OverlayModel.next(false, BUBBLE))
        assertEquals("typing a prompt is not interrupted", PROMPT, OverlayModel.next(false, PROMPT))
        assertEquals(RESULT, OverlayModel.next(false, RESULT))
    }
}
