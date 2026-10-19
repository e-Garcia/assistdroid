package dev.egarcia.harness.policy

/**
 * Apps the agent may never see or operate (ADR 0002): this app itself, password managers,
 * authenticators, wallets and banking. The owner can add more entries in Settings.
 *
 * An entry containing a dot is a package (matches itself and sub-packages); anything else is a
 * keyword matched anywhere in the package name or app label.
 */
class AppDenylist(customEntries: List<String> = emptyList()) {

    private val entries: List<String> =
        (BUILT_IN + customEntries).map { it.trim().lowercase() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    fun isDenied(pkg: String?): Boolean {
        val p = pkg?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
        return entries.any { matches(it, p) }
    }

    /** For a launch request, where [name] may be a package id or an app label such as "PayPal". */
    fun isDeniedName(name: String): Boolean = isDenied(name)

    private fun matches(entry: String, target: String): Boolean =
        if ('.' in entry) target == entry || target.startsWith("$entry.") else target.contains(entry)

    companion object {
        const val OWN_PACKAGE = "dev.egarcia.harness"

        val BUILT_IN_PACKAGES = listOf(
            OWN_PACKAGE,
            // Password managers
            "com.x8bit.bitwarden", "com.bitwarden", "com.lastpass", "com.agilebits.onepassword", "com.onepassword",
            "com.dashlane", "com.nordpass", "proton.android.pass", "com.enpass", "com.roboform",
            // Authenticators and security keys
            "com.google.android.apps.authenticator2", "com.azure.authenticator", "com.authy", "com.duosecurity",
            "org.fedorahosted.freeotp", "com.yubico", "com.twofasapp",
            // Wallets and payments
            "com.google.android.apps.walletnfcrel", "com.samsung.android.spay", "com.paypal", "com.venmo",
            "com.squareup.cash", "com.coinbase",
        )

        /** Matched anywhere in a package name or label, so banking apps are covered without listing each one. */
        val BUILT_IN_KEYWORDS = listOf("bank", "paypal", "venmo", "coinbase", "binance", "revolut", "robinhood", "keepass", "assistdroid")

        val BUILT_IN: List<String> = BUILT_IN_PACKAGES + BUILT_IN_KEYWORDS
    }
}
