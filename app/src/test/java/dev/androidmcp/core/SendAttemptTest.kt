package dev.androidmcp.core

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SendAttemptTest {
    @Test fun delayedUiCallbackCannotSendAfterTimeoutReportsNothingSent() {
        val attempt = SendAttempt()
        assertTrue(attempt.cancelBeforeAttempt())
        assertFalse(attempt.begin())
        assertTrue(attempt.cancelBeforeAttempt())
    }
    @Test fun begunSendCannotBeReportedAsNotAttemptedOrSentTwice() {
        val attempt = SendAttempt()
        assertTrue(attempt.begin())
        assertFalse(attempt.cancelBeforeAttempt())
        assertFalse(attempt.begin())
    }
    @Test fun sendAndCancellationRacingAlwaysHaveOnlyOneWinner() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(100) {
                val attempt = SendAttempt()
                val start = CountDownLatch(1)
                val send = pool.submit<Boolean> { start.await(); attempt.begin() }
                val cancel = pool.submit<Boolean> { start.await(); attempt.cancelBeforeAttempt() }
                start.countDown()
                assertNotEquals(send.get(2, TimeUnit.SECONDS), cancel.get(2, TimeUnit.SECONDS))
            }
        } finally { pool.shutdownNow() }
    }
}
