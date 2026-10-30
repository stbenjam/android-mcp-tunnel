package dev.androidmcp

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.androidmcp.core.LedgerStore
import dev.androidmcp.core.RequestLedger
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AppState(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val ledger = RequestLedger(object : LedgerStore {
        private val records = context.getSharedPreferences("operations", Context.MODE_PRIVATE)
        override fun read(key: String) = records.getString(key, null)
        override fun write(key: String, value: String) {
            check(records.edit().putString(key, value).commit()) { "Cannot record operation" }
        }
    })
    var tunnelId: String
        get() = prefs.getString("tunnel_id", "")!!
        private set(value) { prefs.edit().putString("tunnel_id", value).apply() }
    val instanceId: String get() = prefs.getString("instance_id", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("instance_id", it).commit()
    }
    fun enabled(capability: String) = prefs.getBoolean("enable_$capability", capability == "clock")
    fun enable(capability: String, value: Boolean) { prefs.edit().putBoolean("enable_$capability", value).apply() }
    fun hasKey() = prefs.contains("key")

    @Synchronized private fun encryptionKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("tunnel-runtime-key", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("tunnel-runtime-key", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun save(id: String, newKey: String) {
        require(id.matches(Regex("tunnel_(?:[a-z0-9]{4}_)?[a-z0-9]{32}"))) { "Enter the complete tunnel ID from Platform settings." }
        val edit = prefs.edit().putString("tunnel_id", id)
        if (newKey.isNotBlank()) {
            require(newKey.all { it.code in 33..126 }) { "The runtime key contains invalid characters." }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, encryptionKey()) }
            edit.putString("key", Base64.encodeToString(cipher.doFinal(newKey.toByteArray()), Base64.NO_WRAP))
                .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
        }
        check(edit.commit()) { "Could not save connection" }
    }
    fun key(): String {
        val encrypted = prefs.getString("key", null) ?: return ""
        val iv = prefs.getString("iv", null) ?: return ""
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
        }
    }
    fun forget() { prefs.edit().remove("key").remove("iv").remove("tunnel_id").commit() }
}
