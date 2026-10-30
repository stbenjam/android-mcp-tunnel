package dev.androidmcp.core

import org.junit.Assert.*
import org.junit.Test

class RequestLedgerTest {
    private val data = mutableMapOf<String, String>()
    private val store = object : LedgerStore {
        override fun read(key: String) = data[key]
        override fun write(key: String, value: String) { data[key] = value }
    }
    @Test fun replayAfterProcessRestartDoesNotRepeatSideEffect() {
        var sent = 0
        val args = obj("request_id" to "sms-request-123", "text" to "test")
        RequestLedger(store).run("sms", args) { sent++; obj("status" to "submitted") }
        val response = RequestLedger(store).run("sms", args) { sent++; obj() }
        assertEquals(1, sent)
        assertEquals("submitted", response.getString("status"))
        assertFalse(data.values.single().contains("test"))
    }
    @Test fun crashAfterReservationNeverReplays() {
        val ledger = RequestLedger(store)
        val args = obj("request_id" to "crash-request")
        runCatching { ledger.run("sms", args) { throw IllegalStateException("crash") } }
        val result = RequestLedger(store).run("sms", args) { fail("Must not repeat"); obj() }
        assertEquals("indeterminate", result.getString("status"))
    }
    @Test fun conflictingReuseIsRejectedAndArgumentOrderDoesNotMatter() {
        val ledger = RequestLedger(store)
        ledger.run("sms", obj("request_id" to "same-request", "text" to "one")) { obj("ok" to true) }
        assertTrue(ledger.run("sms", obj("text" to "one", "request_id" to "same-request")) { fail(); obj() }.getBoolean("ok"))
        assertThrows(ToolFailure::class.java) { ledger.run("sms", obj("request_id" to "same-request", "text" to "two")) { fail(); obj() } }
    }
    @Test fun callbacksUpdateStatusWithoutRepeating() {
        val ledger = RequestLedger(store)
        ledger.run("sms", obj("request_id" to "sent-request")) { obj("status" to "submitted") }
        RequestLedger(store).complete("sent-request", obj("status" to "sent"))
        assertEquals("sent", ledger.status("sent-request").getString("status"))
    }

    @Test fun concurrentDuplicateDoesNotReplayOrBlockUnrelatedOperations() {
        val ledger = RequestLedger(store)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        val executed = java.util.concurrent.atomic.AtomicInteger()
        val args = obj("request_id" to "concurrent-sms")
        try {
            val original = pool.submit<org.json.JSONObject> {
                ledger.run("sms", args) { executed.incrementAndGet(); entered.countDown(); release.await(); obj("status" to "submitted") }
            }
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            val other = pool.submit<org.json.JSONObject> {
                assertEquals("in_progress", ledger.status("concurrent-sms").getString("status"))
                assertEquals("in_progress", RequestLedger(store).run("sms", args) { fail("Duplicate executed"); obj() }.getString("status"))
                ledger.run("alarm", obj("request_id" to "independent-alarm")) { obj("status" to "requested") }
            }
            assertEquals("requested", other.get(2, java.util.concurrent.TimeUnit.SECONDS).getString("status"))
            release.countDown()
            assertEquals("submitted", original.get(2, java.util.concurrent.TimeUnit.SECONDS).getString("status"))
            assertEquals(1, executed.get())
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun callbackArrivingBeforeSubmitReturnsIsNotOverwritten() {
        val ledger = RequestLedger(store)
        val result = ledger.run("sms", obj("request_id" to "early-callback")) {
            RequestLedger(store).complete("early-callback", obj("status" to "sent"))
            obj("status" to "submitted")
        }
        assertEquals("sent", result.getString("status"))
        assertEquals("sent", ledger.status("early-callback").getString("status"))
    }
}
