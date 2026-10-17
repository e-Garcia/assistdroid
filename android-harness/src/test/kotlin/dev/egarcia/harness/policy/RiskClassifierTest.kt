package ga.egarcia.harness.policy

import ga.egarcia.harness.Element
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RiskClassifierTest {
    private fun el(text: String? = null, desc: String? = null, id: String? = null) =
        Element("e1", "button", text, desc, id, listOf(0, 0, 1, 1), clickable = true)

    @Test
    fun englishActionLabels() {
        assertEquals(Risk.SEND, RiskClassifier.classify(el("Send")))
        assertEquals(Risk.SEND, RiskClassifier.classify(el("Send message")))
        assertEquals(Risk.POST, RiskClassifier.classify(el("Post")))
        assertEquals(Risk.POST, RiskClassifier.classify(el("Publish now")))
        assertEquals(Risk.DELETE, RiskClassifier.classify(el("Delete conversation")))
        assertEquals(Risk.DELETE, RiskClassifier.classify(el("Remove")))
        assertEquals(Risk.PURCHASE, RiskClassifier.classify(el("Buy now")))
        assertEquals(Risk.PURCHASE, RiskClassifier.classify(el("Pay \$5.00")))
        assertEquals(Risk.PURCHASE, RiskClassifier.classify(el("Place your order")))
        assertEquals(Risk.CALL, RiskClassifier.classify(el("Call Mom")))
    }

    @Test
    fun spanishActionLabels() {
        assertEquals(Risk.SEND, RiskClassifier.classify(el("Enviar")))
        assertEquals(Risk.POST, RiskClassifier.classify(el("Publicar")))
        assertEquals(Risk.DELETE, RiskClassifier.classify(el("Eliminar chat")))
        assertEquals(Risk.DELETE, RiskClassifier.classify(el("Borrar")))
        assertEquals(Risk.PURCHASE, RiskClassifier.classify(el("Comprar ahora")))
        assertEquals(Risk.CALL, RiskClassifier.classify(el("Llamar")))
    }

    @Test
    fun iconButtonsAreCaughtByDescriptionOrResourceId() {
        assertEquals(Risk.SEND, RiskClassifier.classify(el(desc = "Send")))
        assertEquals(Risk.SEND, RiskClassifier.classify(el(id = "com.app:id/send_button")))
        assertEquals(Risk.DELETE, RiskClassifier.classify(el(id = "btnDeleteAll")))
    }

    @Test
    fun harmlessLabelsAreNotFlagged() {
        for (label in listOf("Search Settings", "Reply", "Compose", "Save draft", "Settings", "Wi-Fi", "Sent", "Calls", "Cancel", "OK")) {
            assertNull(label, RiskClassifier.classify(el(label)))
        }
        assertNull(RiskClassifier.classify(el()))
    }

    @Test
    fun longContentIsNotTreatedAsAButtonLabel() {
        assertNull(RiskClassifier.classify(el("Ann Lee · Please send me the report before Friday afternoon")))
    }

    @Test
    fun permissionAndInstallerButtonsAreRiskyOnlyInsideThoseSystemScreens() {
        val pc = "com.google.android.permissioncontroller"
        for (label in listOf("While using the app", "Only this time", "Allow", "Permitir", "Solo esta vez", "Install", "Instalar", "Update")) {
            assertEquals(label, Risk.PERMISSION, RiskClassifier.classify(el(label), pc))
        }
        assertEquals(Risk.PERMISSION, RiskClassifier.classify(el("Install"), "com.android.packageinstaller"))
        assertNull("the same word in an ordinary app is not gated", RiskClassifier.classify(el("Allow"), "com.slack"))
        assertNull(RiskClassifier.classify(el("Allow")))
    }

    @Test
    fun refusingAPermissionIsNeverGated() {
        val pc = "com.google.android.permissioncontroller"
        for (label in listOf("Don't allow", "Deny", "No permitir", "Cancel", "Cancelar")) {
            assertNull(label, RiskClassifier.classify(el(label), pc))
        }
    }

    @Test
    fun mostSevereRiskWins() {
        assertEquals(Risk.PURCHASE, RiskClassifier.classify(el("Pay and send")))
    }
}
