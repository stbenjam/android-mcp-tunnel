package dev.androidmcp.core

import org.json.JSONObject
import java.security.MessageDigest

interface LedgerStore {
    fun read(key: String): String?
    fun write(key: String, value: String)
}

/** Reserve before doing work. A crash leaves an indeterminate entry, never a replay. */
class RequestLedger(private val store: LedgerStore) {
    companion object {
        private val lock = Any()
        private val inProgress = mutableSetOf<String>()
    }
    fun run(name: String, args: JSONObject, action: () -> JSONObject): JSONObject {
        val id = args.getString("request_id")
        if (!id.matches(Regex("[A-Za-z0-9_-]{8,128}"))) throw ToolFailure("request_id must contain 8–128 letters, digits, hyphens, or underscores")
        val canonical = args.keys().asSequence().sorted().joinToString("\n") { "$it:${obj("value" to args.get(it))}" }
        val digest = MessageDigest.getInstance("SHA-256").digest("$name\n$canonical".toByteArray())
            .joinToString("") { "%02x".format(it) }
        synchronized(lock) {
            store.read(id)?.let {
                val old = JSONObject(it)
                if (old.getString("digest") != digest) throw ToolFailure("request_id was already used for different arguments")
                return old.optJSONObject("result") ?: pending(id)
            }
            store.write(id, obj("digest" to digest).toString())
            inProgress.add(id)
        }
        // Never hold the journal lock across Android I/O or a wait on the UI thread.
        try {
            val result = try { action() } catch (e: ActionNotPerformed) {
                ToolResults.failure(e.message ?: "The action was not performed.")
                    .put("action_performed", false).put("request_id", id)
            }
            return synchronized(lock) {
                val record = JSONObject(store.read(id)!!)
                // An SMS callback may have already recorded a newer outcome.
                val latest = record.optJSONObject("result") ?: result
                store.write(id, record.put("result", latest).toString())
                latest
            }
        } finally {
            // All outcomes retain their reservation, including definite failures.
            synchronized(lock) { inProgress.remove(id) }
        }
    }

    fun complete(id: String, result: JSONObject) = synchronized(lock) {
        val old = store.read(id)?.let { JSONObject(it) } ?: return@synchronized
        old.put("result", result)
        store.write(id, old.toString())
    }

    fun status(id: String): JSONObject = synchronized(lock) { store.read(id)?.let {
        JSONObject(it).optJSONObject("result") ?: pending(id)
    } ?: obj("status" to "unknown") }

    private fun pending(id: String) = if (id in inProgress) obj("status" to "in_progress", "request_id" to id)
        else obj("status" to "indeterminate", "request_id" to id,
            "message" to "This action may have run before a restart. Check the phone; it will not be repeated automatically.")
}
