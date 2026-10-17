package ga.egarcia.harness.policy

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalBrokerTest {
    private val prompt = ApprovalPrompt("Tap \"Send\"", Risk.SEND, "com.app", "Send")

    @Test
    fun approvalIsReturnedAndThePendingRequestClears() = runTest {
        val broker = ApprovalBroker()
        val decision = async { broker.request(prompt) }
        runCurrent()
        val pending = broker.pending.value
        assertNotNull(pending)
        assertEquals(prompt, pending!!.prompt)
        broker.respond(pending.id, approved = true)
        assertEquals(Decision.APPROVED, decision.await())
        assertNull(broker.pending.value)
    }

    @Test
    fun denialIsReturned() = runTest {
        val broker = ApprovalBroker()
        val decision = async { broker.request(prompt) }
        runCurrent()
        broker.respond(broker.pending.value!!.id, approved = false)
        assertEquals(Decision.DENIED, decision.await())
    }

    @Test
    fun noAnswerTimesOutAsADenial() = runTest {
        val broker = ApprovalBroker(timeoutMs = 60_000)
        val decision = async { broker.request(prompt) }
        runCurrent()
        advanceTimeBy(61_000)
        assertEquals(Decision.TIMED_OUT, decision.await())
        assertNull(broker.pending.value)
    }

    @Test
    fun aStaleOrWrongIdIsIgnored() = runTest {
        val broker = ApprovalBroker(timeoutMs = 10_000)
        val decision = async { broker.request(prompt) }
        runCurrent()
        broker.respond(broker.pending.value!!.id + 99, approved = true)
        advanceTimeBy(11_000)
        assertEquals(Decision.TIMED_OUT, decision.await())
    }

    @Test
    fun onShownRunsOnceTheRequestIsVisible() = runTest {
        val broker = ApprovalBroker()
        var visibleWhenShown = false
        val decision = async { broker.request(prompt) { visibleWhenShown = broker.pending.value != null } }
        runCurrent()
        broker.respond(broker.pending.value!!.id, true)
        decision.await()
        assertEquals(true, visibleWhenShown)
    }

    @Test
    fun requestsAreHandledOneAtATime() = runTest {
        val broker = ApprovalBroker()
        val first = async { broker.request(prompt.copy(action = "first")) }
        val second = async { broker.request(prompt.copy(action = "second")) }
        runCurrent()
        assertEquals("first", broker.pending.value!!.prompt.action)
        broker.respond(broker.pending.value!!.id, true)
        runCurrent()
        assertEquals("second", broker.pending.value!!.prompt.action)
        broker.respond(broker.pending.value!!.id, false)
        assertEquals(Decision.APPROVED, first.await())
        assertEquals(Decision.DENIED, second.await())
    }
}
