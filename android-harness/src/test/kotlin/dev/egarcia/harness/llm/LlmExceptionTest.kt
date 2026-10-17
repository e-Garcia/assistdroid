package dev.egarcia.harness.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmExceptionTest {
    @Test
    fun transientFailuresAreRetryable() {
        for (code in listOf("network", "http_429", "http_408", "http_500", "http_502", "http_503")) {
            assertTrue(code, LlmException(code, "x").retryable)
        }
    }

    @Test
    fun permanentFailuresAreNot() {
        for (code in listOf("http_400", "http_401", "http_403", "http_404", "cleartext_blocked", "bad_response")) {
            assertFalse(code, LlmException(code, "x").retryable)
        }
    }
}
