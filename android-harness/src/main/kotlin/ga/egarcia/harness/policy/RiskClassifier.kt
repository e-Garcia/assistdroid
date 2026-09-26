package ga.egarcia.harness.policy

import ga.egarcia.harness.Element

/** What an irreversible or outward-facing tap would do. Listed from most to least severe. */
enum class Risk(val description: String) {
    PURCHASE("buy or pay for something"),
    DELETE("delete something"),
    CALL("place a call"),
    POST("post publicly"),
    SEND("send a message"),
}

/**
 * Decides from an element's label whether tapping it needs the user's approval (FR-48). It errs on the
 * side of asking: a false positive costs one tap, a miss could send or delete something.
 * English and Spanish labels are covered.
 */
object RiskClassifier {

    private val WORDS: List<Pair<Risk, Set<String>>> = listOf(
        Risk.PURCHASE to setOf("buy", "purchase", "pay", "checkout", "comprar", "pagar"),
        Risk.DELETE to setOf("delete", "erase", "remove", "eliminar", "borrar"),
        Risk.CALL to setOf("call", "dial", "llamar"),
        Risk.POST to setOf("post", "publish", "tweet", "publicar"),
        Risk.SEND to setOf("send", "enviar"),
    )

    private val PHRASES: List<Pair<Risk, List<String>>> = listOf(
        Risk.PURCHASE to listOf("place order", "place your order", "confirm order", "confirm purchase", "confirm payment", "hacer pedido"),
    )

    /** Longer text is content (an email preview), not a button label. */
    private const val MAX_LABEL_WORDS = 6

    fun classify(element: Element): Risk? {
        val labels = listOfNotNull(element.text, element.desc, element.id?.let(::splitResourceId))
        return labels.firstNotNullOfOrNull(::classifyLabel)
    }

    internal fun classifyLabel(label: String): Risk? {
        val tokens = label.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty() || tokens.size > MAX_LABEL_WORDS) return null
        val joined = tokens.joinToString(" ")
        PHRASES.forEach { (risk, phrases) -> if (phrases.any { it in joined }) return risk }
        return WORDS.firstOrNull { (_, words) -> tokens.any { it in words } }?.first
    }

    /** `com.app:id/send_button` and `btnDeleteAll` become "send button" and "btn Delete All". */
    private fun splitResourceId(id: String) =
        id.substringAfter(":id/").replace(Regex("([a-z])([A-Z])"), "$1 $2").replace('_', ' ')
}
