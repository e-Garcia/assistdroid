package dev.egarcia.harness.inbox

import java.util.Properties
import javax.mail.AuthenticationFailedException
import javax.mail.Address
import javax.mail.FetchProfile
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.MessagingException
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeUtility

/**
 * Reads a digest over IMAPS. The folder is opened READ_ONLY and bodies are fetched with BODY.PEEK, so nothing is
 * marked as read, moved or deleted, and nothing is ever sent. Blocking: call from an IO thread.
 *
 * [extraProperties] exists for tests (a local server with a self-signed certificate). The app never sets it,
 * so certificate and host name checks are always on.
 */
class ImapEmailClient(
    private val config: EmailConfig,
    private val clock: () -> Long = System::currentTimeMillis,
    private val extraProperties: Properties = Properties(),
) {
    /** How many of the newest headers are examined; keeps a huge mailbox from taking minutes. */
    private val scanLimit = 150

    fun digest(limit: Int): List<EmailSummary> = withFolder { folder ->
        val total = folder.messageCount
        if (total == 0) return@withFolder emptyList()
        val window = folder.getMessages(maxOf(1, total - scanLimit + 1), total)
        folder.fetch(window, FetchProfile().apply { add(FetchProfile.Item.ENVELOPE); add(FetchProfile.Item.FLAGS) })
        val since = clock() - config.maxAgeDays.coerceIn(1, 30) * 86_400_000L
        window.asList().asReversed().asSequence()
            .filter { !config.unreadOnly || !it.isSet(Flags.Flag.SEEN) }
            .filter { received(it) >= since }
            .filter { SenderFilter.matches(config.senders, SenderFilter.addressOf(fromOf(it))) }
            .take(limit.coerceIn(1, MAX_LIMIT))
            .map { summarize(it) }
            .toList()
    }

    /** Verifies host, TLS, login and folder. Returns the message count, or throws [EmailException] with a readable reason. */
    fun test(): Int = withFolder { it.messageCount }

    private fun <T> withFolder(block: (Folder) -> T): T {
        config.problem()?.let { throw EmailException(it) }
        val store = Session.getInstance(properties()).getStore("imaps")
        try {
            store.connect(config.host, config.port, config.username, config.password)
            val folder = store.getFolder(config.folder)
            if (!folder.exists()) throw EmailException("The folder \"${config.folder}\" does not exist on this account.")
            folder.open(Folder.READ_ONLY)
            try {
                return block(folder)
            } finally {
                runCatching { folder.close(false) }
            }
        } catch (e: EmailException) {
            throw e
        } catch (e: AuthenticationFailedException) {
            throw EmailException("The server refused the login. Use an app password, and check the address.")
        } catch (e: MessagingException) {
            throw EmailException(explain(e))
        } finally {
            runCatching { store.close() }
        }
    }

    private fun properties() = Properties().apply {
        put("mail.store.protocol", "imaps")
        put("mail.imaps.ssl.enable", "true")
        put("mail.imaps.ssl.checkserveridentity", "true")
        put("mail.imaps.peek", "true")
        put("mail.imaps.connectiontimeout", "15000")
        put("mail.imaps.timeout", "20000")
        put("mail.imaps.fetchsize", "65536")
        extraProperties.forEach { (k, v) -> put(k, v) }
    }

    private fun summarize(message: Message) = EmailSummary(
        from = EmailFormatter.oneLine(displayFrom(message), 80),
        subject = EmailFormatter.oneLine(decode(message.subject), EmailFormatter.MAX_SUBJECT).ifEmpty { "(no subject)" },
        receivedAt = received(message),
        unread = !message.isSet(Flags.Flag.SEEN),
        snippet = EmailFormatter.oneLine(runCatching { bodyText(message) }.getOrDefault(""), EmailFormatter.MAX_SNIPPET),
    )

    private fun received(message: Message): Long = (message.receivedDate ?: message.sentDate)?.time ?: 0L

    private fun fromOf(message: Message): String? = message.from?.firstOrNull()?.let(::addressText)

    private fun addressText(a: Address): String = (a as? InternetAddress)?.let { "\"${it.personal.orEmpty()}\" <${it.address}>" } ?: a.toString()

    /** Shows the address (what matters for trust) with the display name for readability. */
    private fun displayFrom(message: Message): String {
        val a = message.from?.firstOrNull() as? InternetAddress ?: return "(unknown sender)"
        val name = a.personal?.takeIf { it.isNotBlank() }
        return if (name != null) "$name <${a.address}>" else a.address.orEmpty()
    }

    private fun decode(text: String?): String = runCatching { MimeUtility.decodeText(text.orEmpty()) }.getOrDefault(text.orEmpty())

    private fun bodyText(part: Part): String {
        val plain = firstPart(part, "text/plain")
        if (plain != null) return plain.content?.toString().orEmpty()
        val html = firstPart(part, "text/html")
        return html?.content?.toString()?.let(EmailFormatter::htmlToText).orEmpty()
    }

    private fun firstPart(part: Part, mime: String, depth: Int = 0): Part? {
        if (depth > 6) return null
        if (part.isMimeType(mime) && !Part.ATTACHMENT.equals(part.disposition, ignoreCase = true)) return part
        if (part.isMimeType("multipart/*")) {
            val multi = part.content as? Multipart ?: return null
            for (i in 0 until multi.count) firstPart(multi.getBodyPart(i), mime, depth + 1)?.let { return it }
        }
        return null
    }

    private fun explain(e: MessagingException): String {
        val cause = generateSequence<Throwable>(e) { it.cause }.toList()
        return when {
            cause.any { it is java.net.UnknownHostException } -> "Could not find the server ${config.host}. Check the name and the network."
            cause.any { it is java.net.SocketTimeoutException || it is java.net.ConnectException } -> "Could not connect to ${config.host}:${config.port}. Check the port and the network."
            cause.any { it is javax.net.ssl.SSLException } || e.message.orEmpty().contains("verify identity") -> "The secure connection to ${config.host} failed (certificate or TLS problem). Plain, unencrypted IMAP is not supported."
            else -> "Mail server error: ${e.message?.lineSequence()?.firstOrNull().orEmpty().take(120)}"
        }
    }

    companion object {
        const val MAX_LIMIT = 20
    }
}
