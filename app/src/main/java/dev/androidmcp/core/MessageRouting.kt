package dev.androidmcp.core

import org.json.JSONObject

object MessageRouting {
    fun send(locked: Boolean, screenOn: Boolean, sms: () -> JSONObject, messages: () -> JSONObject): JSONObject =
        if (locked || !screenOn) sms() else messages()
    // An error after starting the Messages route never triggers a second send through SMS.
}
