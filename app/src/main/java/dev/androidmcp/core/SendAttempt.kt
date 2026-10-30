package dev.androidmcp.core

import java.util.concurrent.atomic.AtomicInteger

/** A timeout can race a UI callback. Cancellation must close the gate before reporting not sent. */
class SendAttempt {
    private val state = AtomicInteger(0) // ready, attempted, cancelled
    fun begin(): Boolean = state.compareAndSet(0, 1)
    fun cancelBeforeAttempt(): Boolean = state.compareAndSet(0, 2) || state.get() == 2
}
