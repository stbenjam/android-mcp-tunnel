package dev.androidmcp

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.androidmcp.core.obj

class SmsStatusReceiver : BroadcastReceiver() {
    companion object { private val worker = java.util.concurrent.Executors.newSingleThreadExecutor() }
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("request_id") ?: return
        val code = resultCode
        val pending = goAsync()
        worker.execute {
            try {
                val total = intent.getIntExtra("total", 1)
                val prefs = context.getSharedPreferences("sms_results", Context.MODE_PRIVATE)
                val part = intent.getIntExtra("part", 0)
                prefs.edit().putInt("$id.$part", code).commit()
                val codes = (0 until total).map { prefs.getInt("$id.$it", Int.MIN_VALUE) }
                val failure = codes.firstOrNull { it != Int.MIN_VALUE && it != Activity.RESULT_OK }
                val status = when { failure != null -> "failed"; codes.all { it == Activity.RESULT_OK } -> "sent"; else -> "submitted" }
                AppState(context).ledger.complete(id, obj("status" to status, "request_id" to id,
                    "parts" to total, "sent_parts" to codes.count { it == Activity.RESULT_OK },
                    "android_error_code" to failure, "message" to if (failure != null) "Android reported that the text could not be sent." else "Text sent."))
            } catch (_: Exception) {
                android.util.Log.w("PhoneLink", "Could not persist SMS send status")
            } finally { pending.finish() }
        }
    }
}
