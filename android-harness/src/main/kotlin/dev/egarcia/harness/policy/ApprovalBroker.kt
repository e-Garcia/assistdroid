package ga.egarcia.harness.policy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** What the user is asked to approve. Deliberately small: it is shown on screen. */
data class ApprovalPrompt(
    val action: String,
    val risk: Risk,
    val pkg: String?,
    val target: String?,
)

enum class Decision { APPROVED, DENIED, TIMED_OUT }

fun interface Approver {
    suspend fun approve(prompt: ApprovalPrompt): Decision
}

/**
 * Holds at most one pending approval. The agent suspends in [request]; the UI (a dialog or a
 * notification button) answers with [respond]. No answer within [timeoutMs] counts as a denial.
 */
class ApprovalBroker(private val timeoutMs: Long = 120_000) {

    data class Pending(val id: Long, val prompt: ApprovalPrompt)

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    private val lock = Mutex()

    @Volatile
    private var waiter: CompletableDeferred<Decision>? = null
    private var nextId = 0L

    /** [onShown] runs once the request is visible in [pending], so the UI can be launched. */
    suspend fun request(prompt: ApprovalPrompt, onShown: () -> Unit = {}): Decision = lock.withLock {
        val deferred = CompletableDeferred<Decision>()
        waiter = deferred
        _pending.value = Pending(++nextId, prompt)
        try {
            onShown()
            withTimeoutOrNull(timeoutMs) { deferred.await() } ?: Decision.TIMED_OUT
        } finally {
            _pending.value = null
            waiter = null
        }
    }

    /** Ignored unless [id] is the request currently on screen. */
    fun respond(id: Long, approved: Boolean) {
        if (_pending.value?.id == id) waiter?.complete(if (approved) Decision.APPROVED else Decision.DENIED)
    }
}
