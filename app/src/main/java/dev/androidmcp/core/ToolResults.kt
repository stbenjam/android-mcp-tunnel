package dev.androidmcp.core

import org.json.JSONObject

/** Optimistic action results: an accepted Android handoff is a successful tool action. */
object ToolResults {
    fun normalize(value: JSONObject): JSONObject {
        val result = JSONObject(value.toString())
        if (result.opt("success") == false) result.put("status", "failed")
        when (result.optString("status")) {
            "requested" -> result.put("status", "completed").put("completion_basis", "android_accepted")
                .put("message", "Action completed.") // Also upgrades journal entries from older APKs.
            "submitted", "sent" -> {
                val smsStatus = result.getString("status")
                result.put("sms_status", smsStatus).put("status", "completed")
                    .put("completion_basis", if (smsStatus == "sent") "android_sent" else "android_accepted")
                    .put("message", "Text sent.")
            }
            "" -> result.put("status", "completed") // Successful read-only tool call.
        }
        val success: Any = when (result.optString("status")) {
            "completed" -> true
            "failed" -> false
            else -> JSONObject.NULL // In progress, unknown, or interrupted: never invent success.
        }
        return result.put("success", success)
    }

    fun failure(message: String) = obj("success" to false, "status" to "failed", "message" to message)
}
