package ga.egarcia.harness

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import ga.egarcia.harness.inbox.EmailConfig
import ga.egarcia.harness.inbox.EmailException
import ga.egarcia.harness.inbox.EmailStore
import ga.egarcia.harness.inbox.ImapEmailClient
import ga.egarcia.harness.inbox.InboxStore
import ga.egarcia.harness.policy.AppDenylist
import ga.egarcia.harness.policy.PolicyStore
import ga.egarcia.harness.setup.SetupAction
import ga.egarcia.harness.setup.SetupActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The two ways the agent can read messages without operating a messaging app. Both are opt-in and read-only:
 * notifications from apps you pick, and an email digest from an IMAP account you add.
 */
class InboxSettingsActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var savedEmail: EmailConfig? = null

    private lateinit var accessStatus: TextView
    private lateinit var appList: LinearLayout
    private lateinit var captured: TextView

    private lateinit var host: EditText
    private lateinit var port: EditText
    private lateinit var user: EditText
    private lateinit var password: EditText
    private lateinit var folder: EditText
    private lateinit var senders: EditText
    private lateinit var days: EditText
    private lateinit var unreadOnly: Switch
    private lateinit var emailResult: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedEmail = EmailStore.load(this)
        val e = savedEmail

        accessStatus = TextView(this).apply { textSize = 14f }
        appList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        captured = TextView(this).apply { textSize = 13f; setTextColor(Color.parseColor("#5F6368")) }

        host = field("IMAP server, e.g. imap.gmail.com", e?.host)
        port = field("Port (993)", (e?.port ?: 993).toString()).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        user = field("Email address", e?.username).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS }
        password = field(if (e != null) "App password (leave empty to keep the saved one)" else "App password", null).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        folder = field("Folder", e?.folder ?: "INBOX")
        senders = field("Only these senders: addresses or domains, one per line (empty = everyone)", e?.senders?.joinToString("\n")).apply {
            isSingleLine = false
            minLines = 2
            gravity = Gravity.TOP
        }
        days = field("Look back (days, 1 to 30)", (e?.maxAgeDays ?: 7).toString()).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        unreadOnly = Switch(this).apply { text = "Unread messages only"; isChecked = e?.unreadOnly ?: true }
        emailResult = TextView(this).apply { textSize = 13f }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
            listOf(
                TextView(this@InboxSettingsActivity).apply {
                    text = "Notification and email digest"
                    textSize = 22f
                    setTextColor(Color.parseColor("#1F1F1F"))
                    setTypeface(typeface, Typeface.BOLD)
                },
                hint("Lets the agent check your messages without opening or operating a messaging app. Both are optional, read-only and off until you set them up. What the agent reads is sent to your AI model like everything else on screen."),
                heading("Notifications"),
                hint("The agent reads only notifications from the apps you switch on below, and only from then on (Android does not give access to older ones). Captured text is stored encrypted on this phone for up to 7 days. Apps blocked for safety (banking, password managers, this app) are never listed or captured."),
                accessStatus,
                Button(this@InboxSettingsActivity).apply {
                    text = "Notification access"
                    isAllCaps = false
                    setOnClickListener { SetupActions.perform(this@InboxSettingsActivity, SetupAction.OPEN_NOTIFICATION_ACCESS) {} }
                },
                appList,
                captured,
                Button(this@InboxSettingsActivity).apply {
                    text = "Delete captured notifications"
                    isAllCaps = false
                    setOnClickListener { InboxStore.repository(this@InboxSettingsActivity).clear(null); refresh() }
                },
                heading("Email"),
                hint("Read-only over IMAP with TLS: the folder is opened read-only, messages are not marked as read and nothing is ever sent, moved or deleted. Use an app password (Gmail: Google Account > Security > App passwords), not your main password. The agent sees sender, subject and the first lines of each message."),
                host, caption("Port (993 is standard TLS)"), port, user, password, caption("Folder"), folder, senders, caption("Look back, in days (1 to 30)"), days, unreadOnly,
                Button(this@InboxSettingsActivity).apply { text = "Save"; isAllCaps = false; setOnClickListener { saveEmail() } },
                Button(this@InboxSettingsActivity).apply { text = "Test connection"; isAllCaps = false; setOnClickListener { testEmail() } },
                Button(this@InboxSettingsActivity).apply { text = "Remove email account"; isAllCaps = false; setOnClickListener { removeEmail() } },
                emailResult,
            ).forEach(::addView)
        }
        val frame = LinearLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(ScrollView(this@InboxSettingsActivity).apply { addView(content) })
        }
        applySystemInsets(frame)
        setContentView(frame)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun refresh() {
        val on = InboxStore.listenerEnabled(this)
        accessStatus.text = if (on) "Notification access is on." else "Notification access is off. Turn it on to choose apps."
        accessStatus.setTextColor(Color.parseColor(if (on) "#1E7B34" else "#B26A00"))

        val repo = InboxStore.repository(this)
        val watched = InboxStore.watched(this)
        val denylist = AppDenylist(PolicyStore.load(this).customDenied)
        // "android" is the system itself (charging, USB, ...): never a conversation worth watching.
        val apps = repo.seenApps().filter { it.pkg != "android" && !denylist.isDenied(it.pkg) && !denylist.isDenied(it.label) }
        appList.removeAllViews()
        if (apps.isEmpty() && watched.isEmpty()) {
            appList.addView(hint(if (on) "No app has posted a notification yet. Apps appear here as soon as they do." else "Apps that post notifications will be listed here once access is on."))
        }
        // Apps already watched stay listed (so they can be switched off) even if they have not posted since.
        val listed = apps.map { it.pkg to it.label } + (watched - apps.map { it.pkg }.toSet()).map { it to it }
        listed.forEach { (pkg, label) ->
            appList.addView(Switch(this@InboxSettingsActivity).apply {
                text = if (label == pkg) pkg else "$label\n$pkg"
                isChecked = pkg in watched
                setPadding(0, dp(6), 0, dp(6))
                setOnCheckedChangeListener { _, checked -> InboxStore.setWatching(this@InboxSettingsActivity, pkg, checked); refresh() }
            })
        }
        captured.text = "${repo.count()} notification(s) stored from ${watched.size} watched app(s)."
    }

    private fun readEmail(): EmailConfig? {
        val config = EmailConfig(
            host = host.text.toString().trim(),
            port = port.text.toString().toIntOrNull() ?: 993,
            username = user.text.toString().trim(),
            password = password.text.toString().ifEmpty { savedEmail?.password.orEmpty() },
            folder = folder.text.toString().trim().ifEmpty { "INBOX" },
            senders = senders.text.toString().split('\n', ',', ' ').map { it.trim() }.filter { it.isNotEmpty() },
            unreadOnly = unreadOnly.isChecked,
            maxAgeDays = days.text.toString().toIntOrNull()?.coerceIn(1, 30) ?: 7,
        )
        config.problem()?.let { show(it, ok = false); return null }
        return config
    }

    private fun saveEmail() {
        val config = readEmail() ?: return
        EmailStore.save(this, config)
        savedEmail = config
        password.text.clear()
        show("Saved. The agent can now read an email digest.", ok = true)
    }

    private fun testEmail() {
        val config = readEmail() ?: return
        show("Connecting…", ok = null)
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { ImapEmailClient(config).test() } }
            outcome.onSuccess { show("Connected. ${config.folder} holds $it message(s). Nothing was changed.", ok = true) }
                .onFailure { show((it as? EmailException)?.message ?: "Could not connect.", ok = false) }
        }
    }

    private fun removeEmail() {
        EmailStore.clear(this)
        savedEmail = null
        listOf(host, user, password, senders).forEach { it.text.clear() }
        show("Email account removed from this phone.", ok = true)
    }

    private fun show(message: String, ok: Boolean?) {
        emailResult.text = message
        emailResult.setTextColor(Color.parseColor(when (ok) { true -> "#1E7B34"; false -> "#B3261E"; null -> "#5F6368" }))
    }

    private fun field(hint: String, value: String?) = EditText(this).apply {
        this.hint = hint
        setText(value)
        setSingleLine()
    }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(4))
    }

    private fun caption(text: String) = hint(text).apply { setPadding(0, dp(10), 0, 0) }

    private fun hint(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.parseColor("#5F6368"))
        setPadding(0, dp(2), 0, dp(6))
    }
}
