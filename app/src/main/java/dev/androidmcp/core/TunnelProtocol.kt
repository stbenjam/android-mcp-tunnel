package dev.androidmcp.core

import org.json.JSONObject
import java.math.BigInteger

object TunnelProtocol {
    fun timeoutNanos(value: Any?): Long? {
        if (value !is String) return null
        val match = Regex("([0-9]+)(ns|us|ms|s|m|h)").matchEntire(value) ?: return null
        val factor = when (match.groupValues[2]) {
            "ns" -> 1L; "us" -> 1000L; "ms" -> 1_000_000L
            "s" -> 1_000_000_000L; "m" -> 60_000_000_000L; else -> 3_600_000_000_000L
        }
        val n = match.groupValues[1].toBigInteger().multiply(BigInteger.valueOf(factor))
        return if (n > BigInteger.valueOf(Long.MAX_VALUE)) null else n.toLong()
    }

    fun response(command: JSONObject, server: McpServer): JSONObject? {
        val envelope = obj("request_id" to command.getString("request_id"),
            "channel" to command.optString("channel", "main"))
        when (command.getString("command_type")) {
            "session_termination" -> envelope.put("resp_type", "session_termination_response").put("resp_code", 204)
            "jsonrpc" -> {
                val response = server.handle(command.getJSONObject("jsonrpc"))
                if (response == null) envelope.put("resp_type", "notify_ack").put("resp_code", 202)
                else envelope.put("resp_type", "jsonrpc_response").put("resp_code", 200)
                    .put("resp_json", response).put("resp_headers", obj("Content-Type" to arr("application/json")))
            }
            else -> return null
        }
        return envelope
    }
}
