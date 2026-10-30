package dev.androidmcp.core

import org.junit.Assert.*
import org.junit.Test

class MessageRoutingTest {
    @Test fun lockedOrScreenOffUsesOnlySms() {
        for ((locked, screenOn) in listOf(true to true, true to false, false to false)) {
            var sms = 0
            MessageRouting.send(locked, screenOn, { sms++; obj() }, { fail("Must not open Messages"); obj() })
            assertEquals(1, sms)
        }
    }
    @Test fun unlockedUsesMessagesAndNeverFallsBackAfterUncertainSend() {
        var messages = 0
        assertThrows(ToolFailure::class.java) {
            MessageRouting.send(false, true, { fail("Fallback could duplicate a send"); obj() }, {
                messages++; throw ToolFailure("Send outcome uncertain")
            })
        }
        assertEquals(1, messages)
    }
}
