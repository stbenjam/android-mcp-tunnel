package dev.androidmcp.core

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TunnelClientTest {
    private fun command(rpc: JSONObject = obj("jsonrpc" to "2.0", "id" to 71, "method" to "ping")) =
        obj("request_id" to "opaque-id", "shard_token" to "opaque-shard", "channel" to "main",
            "command_type" to "jsonrpc", "response_timeout" to "30s", "jsonrpc" to rpc)
    @Test fun exactDurationGrammarAndOverflow() {
        assertEquals(30_000_000_000, TunnelProtocol.timeoutNanos("30s"))
        assertEquals(0L, TunnelProtocol.timeoutNanos("0s"))
        assertEquals(4_500_000_000, TunnelProtocol.timeoutNanos("4500ms"))
        for (v in listOf(null, 30, "-1s", "4.5s", " 1s", "1m30s", "1e3s", "30d", "999999999999999999999999h"))
            assertNull("$v", TunnelProtocol.timeoutNanos(v))
    }
    @Test fun postsCorrelatedResponseAndRetriesDeliveryWithoutRepeatingTool() {
        MockWebServer().use { mock ->
            mock.enqueue(MockResponse().setResponseCode(503))
            mock.enqueue(MockResponse().setBody("{\"status\":\"ok\"}"))
            var executed = 0
            val server = McpServer(listOf(Tool("action", "test") { executed++; obj("status" to "done") }))
            val client = TunnelClient(mock.url("/"), "tunnel_example", "test-credential", "instance", server, {}, pause = {})
            client.process(command(obj("jsonrpc" to "2.0", "id" to 71, "method" to "tools/call", "params" to obj("name" to "action"))), System.nanoTime())
            assertEquals(1, executed)
            val first = mock.takeRequest(); val second = mock.takeRequest()
            assertEquals("/v1/tunnels/tunnel_example/response", first.path)
            assertEquals("Bearer test-credential", first.getHeader("Authorization"))
            assertEquals("opaque-shard", first.getHeader("X-Tunnel-Shard-Token"))
            val raw = first.body.readUtf8()
            assertEquals(raw, second.body.readUtf8())
            assertFalse(raw.contains("opaque-shard"))
            val response = JSONObject(raw)
            assertEquals("opaque-id", response.getString("request_id"))
            assertEquals("main", response.getString("channel"))
            assertEquals(71, response.getJSONObject("resp_json").getInt("id"))
        }
    }
    @Test fun expiredCommandsNeverExecuteOrPost() {
        MockWebServer().use { mock ->
            var executed = false
            val server = McpServer(listOf(Tool("action", "test") { executed = true; obj() }))
            val client = TunnelClient(mock.url("/"), "test", "key", "id", server, {})
            client.process(command(obj("jsonrpc" to "2.0", "id" to 1, "method" to "tools/call", "params" to obj("name" to "action")))
                .put("response_timeout", "0s"), System.nanoTime())
            assertFalse(executed); assertEquals(0, mock.requestCount)
        }
    }
    @Test fun notificationsAndTerminationHaveDistinctAcknowledgments() {
        val server = McpServer(emptyList())
        assertEquals("notify_ack", TunnelProtocol.response(command(obj("jsonrpc" to "2.0", "method" to "notifications/initialized")), server)!!.getString("resp_type"))
        val term = TunnelProtocol.response(command().put("command_type", "session_termination"), server)!!
        assertEquals("session_termination_response", term.getString("resp_type")); assertFalse(term.has("resp_json"))
        assertNull(TunnelProtocol.response(command().put("command_type", "future"), server))
    }
    @Test fun pollHandlesNoContentAndThenAuthFailureWithoutLooping() {
        MockWebServer().use { mock ->
            mock.enqueue(MockResponse().setResponseCode(204)); mock.enqueue(MockResponse().setResponseCode(401))
            val statuses = mutableListOf<String>()
            TunnelClient(mock.url("/"), "test", "key", "id", McpServer(emptyList()), statuses::add).run()
            assertEquals(2, mock.requestCount)
            assertEquals("Connected", statuses.first()); assertTrue(statuses.last().contains("401"))
            assertEquals("/v1/tunnels/test/poll?limit=25&timeout_ms=15000", mock.takeRequest().path)
        }
    }
    @Test fun stalledUploadRetriesWithinBudgetWithoutRepeatingTool() {
        MockWebServer().use { mock ->
            mock.enqueue(MockResponse().setHeadersDelay(400, java.util.concurrent.TimeUnit.MILLISECONDS).setBody("{}"))
            mock.enqueue(MockResponse().setBody("{}"))
            var executed = 0
            val server = McpServer(listOf(Tool("action", "test") { executed++; obj("ok" to true) }))
            // Pin the mock's listening address: localhost can switch to an unbound IPv6
            // route after timeout. Keep real retry backoff for this network timeout test.
            val client = TunnelClient(mock.url("/").newBuilder().host("127.0.0.1").build(), "test", "key", "id", server, {}, responseAttemptMillis = 100)
            client.process(command(obj("jsonrpc" to "2.0", "id" to 71, "method" to "tools/call", "params" to obj("name" to "action"))), System.nanoTime())
            assertEquals(1, executed)
            assertEquals(mock.takeRequest().body.readUtf8(), mock.takeRequest().body.readUtf8())
            assertEquals(1L, client.diagnostics().getLong("retries"))
            assertEquals(1L, client.diagnostics().getLong("delivered"))
        }
    }
}
