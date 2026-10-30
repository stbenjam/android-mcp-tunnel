package dev.androidmcp.core

import org.junit.Assert.*
import org.junit.Test

class McpServerTest {
    private var calls = 0
    private val server = McpServer(listOf(Tool("timer", "Set a timer", obj("seconds" to intProp("seconds", 1, 60)), listOf("seconds")) {
        calls++; obj("status" to "done")
    }))
    private fun request(method: String, params: org.json.JSONObject = obj()) = obj("jsonrpc" to "2.0", "id" to "id-1", "method" to method, "params" to params)
    @Test fun negotiatesSupportedVersionAndPreservesIds() {
        val response = server.handle(request("initialize", obj("protocolVersion" to "2026-07-28")))!!
        assertEquals("id-1", response.getString("id"))
        assertEquals("2025-11-25", response.getJSONObject("result").getString("protocolVersion"))
    }
    @Test fun toolCatalogHasStrictSchemasAndMutationHints() {
        val tool = server.handle(request("tools/list"))!!.getJSONObject("result").getJSONArray("tools").getJSONObject(0)
        assertFalse(tool.getJSONObject("inputSchema").getBoolean("additionalProperties"))
        assertFalse(tool.getJSONObject("annotations").getBoolean("readOnlyHint"))
    }
    @Test fun invalidArgumentsNeverReachThePhone() {
        for (args in listOf(obj(), obj("seconds" to -1), obj("seconds" to "5"), obj("seconds" to 1.5), obj("seconds" to 61), obj("seconds" to 5, "extra" to true))) {
            val response = server.handle(request("tools/call", obj("name" to "timer", "arguments" to args)))!!
            assertTrue(response.getJSONObject("result").getBoolean("isError"))
        }
        assertEquals(0, calls)
    }
    @Test fun validCallExecutesOnce() {
        assertFalse(server.handle(request("tools/call", obj("name" to "timer", "arguments" to obj("seconds" to 5))))!!
            .getJSONObject("result").getBoolean("isError"))
        assertEquals(1, calls)
    }
    @Test fun notificationsHaveNoResponseOrSideEffects() {
        assertNull(server.handle(obj("jsonrpc" to "2.0", "method" to "notifications/initialized")))
        assertNull(server.handle(obj("jsonrpc" to "2.0", "method" to "tools/call", "params" to obj("name" to "timer"))))
        assertEquals(0, calls)
    }
    @Test fun invalidEnvelopeAndUnknownMethodsReturnRpcErrors() {
        assertEquals(-32600, server.handle(obj("jsonrpc" to "1.0", "id" to 1, "method" to "ping"))!!.getJSONObject("error").getInt("code"))
        assertEquals(-32601, server.handle(request("nope"))!!.getJSONObject("error").getInt("code"))
        assertEquals(-32602, server.handle(request("tools/call", obj("name" to "unknown")))!!.getJSONObject("error").getInt("code"))
    }
}
