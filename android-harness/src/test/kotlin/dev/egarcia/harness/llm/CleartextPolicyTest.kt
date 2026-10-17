package dev.egarcia.harness.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleartextPolicyTest {
    @Test
    fun httpsIsAlwaysAllowed() = assertTrue(CleartextPolicy.isAllowed("https://api.openai.com/v1"))

    @Test
    fun httpToTailnetAndLanNamesIsAllowed() {
        assertTrue(CleartextPolicy.isAllowed("http://my-server:8000/v1"))
        assertTrue(CleartextPolicy.isAllowed("http://my-server.tail1234.ts.net:8000/v1"))
        assertTrue(CleartextPolicy.isAllowed("http://printer.local/v1"))
        assertTrue(CleartextPolicy.isAllowed("http://localhost:8000/v1"))
    }

    @Test
    fun httpToPrivateAndTailscaleAddressesIsAllowed() {
        assertTrue(CleartextPolicy.isAllowed("http://100.101.102.103:8000/v1"))
        assertTrue(CleartextPolicy.isAllowed("http://100.64.0.1/v1"))
        assertTrue(CleartextPolicy.isAllowed("http://100.127.255.254/v1"))
        assertTrue(CleartextPolicy.isAllowed("http://192.168.1.20:1234/v1"))
        assertTrue(CleartextPolicy.isAllowed("http://10.0.2.2:8000/v1"))
        assertTrue(CleartextPolicy.isAllowed("http://172.16.5.5/v1"))
        assertTrue(CleartextPolicy.isAllowed("http://127.0.0.1:8427/v1"))
    }

    @Test
    fun httpToPublicHostsIsBlocked() {
        assertFalse(CleartextPolicy.isAllowed("http://api.openai.com/v1"))
        assertFalse(CleartextPolicy.isAllowed("http://example.com/v1"))
        assertFalse(CleartextPolicy.isAllowed("http://8.8.8.8/v1"))
        assertFalse(CleartextPolicy.isAllowed("http://100.128.0.1/v1")) // just outside 100.64.0.0/10
        assertFalse(CleartextPolicy.isAllowed("http://172.32.0.1/v1"))
        assertFalse(CleartextPolicy.isAllowed("http://192.169.0.1/v1"))
    }

    @Test
    fun otherSchemesAndGarbageAreBlocked() {
        assertFalse(CleartextPolicy.isAllowed("ftp://my-server/v1"))
        assertFalse(CleartextPolicy.isAllowed("not a url"))
        assertFalse(CleartextPolicy.isAllowed(""))
    }
}
