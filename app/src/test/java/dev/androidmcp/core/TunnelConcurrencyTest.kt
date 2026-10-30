package dev.androidmcp.core

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class TunnelConcurrencyTest {
    private fun command(id: Int, tool: String = "ping", timeout: String = "30s") = obj(
        "request_id" to "request-$id", "shard_token" to "secret-shard-$id", "channel" to "main",
        "command_type" to "jsonrpc", "response_timeout" to timeout,
        "jsonrpc" to if (tool == "ping") obj("jsonrpc" to "2.0", "id" to id, "method" to "ping")
            else obj("jsonrpc" to "2.0", "id" to id, "method" to "tools/call", "params" to obj("name" to tool)))

    private class Harness(tools: List<Tool>, workers: Int = 8, capacity: Int = 32,
                          val response: (RecordedRequest) -> MockResponse = { MockResponse().setBody("{}") }) : AutoCloseable {
        val mock = MockWebServer()
        val polls = LinkedBlockingQueue<MockResponse>()
        val delivered = LinkedBlockingQueue<JSONObject>()
        val logs = Collections.synchronizedList(mutableListOf<String>())
        val client: TunnelClient
        val runner: Thread
        init {
            mock.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = if (request.path!!.contains("/poll?")) {
                    polls.poll(250, TimeUnit.MILLISECONDS) ?: MockResponse().setResponseCode(204)
                } else {
                    val body = JSONObject(request.body.clone().readUtf8())
                    assertEquals("secret-shard-${body.getString("request_id").substringAfterLast('-')}", request.getHeader("X-Tunnel-Shard-Token"))
                    val result = response(request)
                    delivered.add(body)
                    result
                }
            }
            client = TunnelClient(mock.url("/"), "test", "secret-runtime", "id", McpServer(tools), {},
                diagnostic = logs::add, workerCount = workers, maxInFlight = capacity)
            runner = thread(name = "test-poller") { client.run() }
        }
        fun send(vararg commands: JSONObject) { polls.add(MockResponse().setBody(obj("commands" to arr(*commands)).toString())) }
        fun next(): JSONObject = delivered.poll(3, TimeUnit.SECONDS) ?: throw AssertionError("No reply; ${client.diagnostics()}")
        override fun close() { client.stop(); runner.join(2000); mock.close(); assertFalse("Poller did not stop", runner.isAlive) }
    }

    @Test fun eightCallsExecuteConcurrently() {
        val entered = CountDownLatch(8)
        val release = CountDownLatch(1)
        Harness(listOf(Tool("work", "test") { entered.countDown(); release.await(5, TimeUnit.SECONDS); obj() })).use { h ->
            try {
                h.send(*(1..8).map { command(it, "work") }.toTypedArray())
                assertTrue("Eight independent requests must all start before any finishes", entered.await(3, TimeUnit.SECONDS))
            } finally { release.countDown() }
            val ids = (1..8).map { h.next().getString("request_id") }.toSet()
            assertEquals((1..8).map { "request-$it" }.toSet(), ids)
        }
    }

    @Test fun stalledReplyDoesNotBlockPollingExecutionOrOtherReplies() {
        val uploading = CountDownLatch(1)
        val release = CountDownLatch(1)
        Harness(emptyList(), response = { request ->
            if (JSONObject(request.body.clone().readUtf8()).getString("request_id") == "request-1") {
                uploading.countDown(); release.await(5, TimeUnit.SECONDS)
            }
            MockResponse().setBody("{}")
        }).use { h ->
            try {
                h.send(command(1))
                assertTrue(uploading.await(3, TimeUnit.SECONDS))
                h.send(command(2)) // Requires a new poll while the first upload is stalled.
                assertEquals("request-2", h.next().getString("request_id"))
            } finally { release.countDown() }
            assertEquals("request-1", h.next().getString("request_id"))
        }
    }

    @Test fun serialActionsStayOrderedWithoutBlockingIndependentTools() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val order = Collections.synchronizedList(mutableListOf<Int>())
        val first = Tool("first", "test", serial = true) { order.add(1); entered.countDown(); release.await(5, TimeUnit.SECONDS); obj() }
        val second = Tool("second", "test", serial = true) { order.add(2); obj() }
        Harness(listOf(first, second), workers = 1).use { h ->
            try {
                h.send(command(1, "first"), command(2, "second"), command(3))
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                assertEquals("request-3", h.next().getString("request_id"))
                assertEquals(listOf(1), order.toList())
            } finally { release.countDown() }
            h.next(); h.next()
            assertEquals(listOf(1, 2), order.toList())
        }
    }

    @Test fun oversizedBatchHonorsCapacityAndKeepsEveryCommand() {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val count = AtomicInteger()
        val tool = Tool("work", "test") {
            val current = active.incrementAndGet(); maximum.updateAndGet { maxOf(it, current) }
            count.incrementAndGet(); Thread.sleep(10); active.decrementAndGet(); obj()
        }
        Harness(listOf(tool), workers = 2, capacity = 3).use { h ->
            h.send(*(1..20).map { command(it, "work") }.toTypedArray())
            assertEquals(20, (1..20).map { h.next().getString("request_id") }.toSet().size)
            assertEquals(20, count.get()); assertTrue(maximum.get() <= 2)
        }
    }

    @Test fun queuedExpiredActionNeverRuns() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val count = AtomicInteger()
        val first = Tool("slow", "test", serial = true) { entered.countDown(); release.await(5, TimeUnit.SECONDS); obj() }
        val second = Tool("expired", "test", serial = true) { count.incrementAndGet(); obj() }
        Harness(listOf(first, second)).use { h ->
            try {
                h.send(command(1, "slow"), command(2, "expired", "50ms"))
                assertTrue(entered.await(3, TimeUnit.SECONDS)); Thread.sleep(100)
            } finally { release.countDown() }
            h.next()
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (h.client.diagnostics().getLong("expired") == 0L && System.nanoTime() < until) Thread.sleep(10)
            assertEquals(0, count.get()); assertEquals(1, h.client.diagnostics().getLong("expired"))
        }
    }

    @Test fun stopCancelsQueuedActionsAndNetworkWaits() {
        val entered = CountDownLatch(1)
        val count = AtomicInteger()
        val first = Tool("slow", "test", serial = true) { entered.countDown(); CountDownLatch(1).await(); obj() }
        val second = Tool("queued", "test", serial = true) { count.incrementAndGet(); obj() }
        Harness(listOf(first, second)).use { h ->
            h.send(command(1, "slow"), command(2, "queued"))
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            h.client.stop(); h.runner.join(2000)
            assertFalse(h.runner.isAlive); assertEquals(0, count.get())
        }
    }

    @Test fun timingDiagnosticsNeverLogCredentialsOrPayloads() {
        Harness(listOf(Tool("sensitive-tool", "test") { obj("text" to "private-message") })).use { h ->
            h.send(command(1, "sensitive-tool")); h.next()
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (h.client.diagnostics().getLong("delivered") == 0L && System.nanoTime() < until) Thread.sleep(10)
            val output = h.logs.joinToString() + h.client.diagnostics().toString()
            for (secret in listOf("secret-runtime", "secret-shard", "request-1", "private-message", "sensitive-tool"))
                assertFalse(secret, output.contains(secret))
            assertEquals(1L, h.client.diagnostics().getLong("delivered"))
        }
    }
}
