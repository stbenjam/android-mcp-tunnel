package dev.androidmcp.core

import org.junit.Assert.*
import org.junit.Test

class ToolResultsTest {
    @Test fun acceptedActionsAndLegacyJournalEntriesReportSuccess() {
        val old = obj("status" to "requested", "message" to "The target app controls completion.")
        val result = ToolResults.normalize(old)
        assertTrue(result.getBoolean("success"))
        assertEquals("completed", result.getString("status"))
        assertEquals("android_accepted", result.getString("completion_basis"))
        assertEquals("Action completed.", result.getString("message"))
        assertEquals("requested", old.getString("status"))
    }

    @Test fun successfulReadAndConfirmedActionHaveExplicitSuccess() {
        val read = ToolResults.normalize(obj("battery_percent" to 50))
        assertTrue(read.getBoolean("success")); assertEquals("completed", read.getString("status"))
        assertEquals(50, read.getInt("battery_percent"))
        val action = ToolResults.normalize(obj("status" to "completed", "message" to "Timer set."))
        assertEquals("Timer set.", action.getString("message")); assertTrue(action.getBoolean("success"))
    }

    @Test fun smsSubmissionIsOptimisticAndLaterFailureIsStillAnError() {
        for (status in listOf("submitted", "sent")) {
            val result = ToolResults.normalize(obj("status" to status, "request_id" to "sms-request"))
            assertTrue(result.getBoolean("success")); assertEquals("completed", result.getString("status"))
            assertEquals(status, result.getString("sms_status")); assertEquals("Text sent.", result.getString("message"))
        }
        val failure = ToolResults.normalize(obj("status" to "failed", "android_error_code" to 1))
        assertFalse(failure.getBoolean("success")); assertEquals("failed", failure.getString("status"))
        assertEquals("failed", ToolResults.normalize(obj("success" to false)).getString("status"))
    }

    @Test fun uncertainJournalStateNeverInventsSuccessfulExecution() {
        for (status in listOf("unknown", "indeterminate", "in_progress")) {
            val result = ToolResults.normalize(obj("status" to status))
            assertTrue(result.isNull("success")); assertEquals(status, result.getString("status"))
        }
    }
}
