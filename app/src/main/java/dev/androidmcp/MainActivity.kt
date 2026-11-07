package dev.androidmcp

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.divider.MaterialDivider
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView

class MainActivity : AppCompatActivity() {
    private lateinit var state: AppState
    private lateinit var statusLabel: TextView
    private lateinit var statusDetail: TextView
    private lateinit var accessDetail: TextView
    private lateinit var configDetail: TextView
    private lateinit var connect: MaterialButton
    private val switches = linkedMapOf<String, Pair<MaterialSwitch, String?>>()
    private val handler = Handler(Looper.getMainLooper())
    private var refreshing = false
    private var pendingDndEnable = false
    private val ticker = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 1000) }
    }
    private val onSurface get() = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, 0)
    private val secondary get() = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, 0)

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        state = AppState(this)
        pendingDndEnable = savedInstanceState?.getBoolean("pending_dnd") ?: false
        FindPhoneSound.recoverVolume(this)
        val root = column().apply {
            setBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface))
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
            }
        }
        val toolbar = MaterialToolbar(this).apply {
            title = "Phone Link"
            menu.add("Setup guide").setOnMenuItemClickListener { openGuide(); true }
            menu.add("App info").setOnMenuItemClickListener {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))); true
            }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(64)))
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = column().apply { setPadding(0, 0, 0, dp(24)) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        val connection = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        val status = column()
        statusLabel = label("Disconnected", com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
        statusDetail = label("", com.google.android.material.R.style.TextAppearance_Material3_BodyMedium, true)
        status.addView(statusLabel); status.addView(statusDetail)
        connection.addView(status, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(16) })
        connect = MaterialButton(this).apply {
            text = "Connect"; isAllCaps = false
            setOnClickListener {
                if (BridgeService.running) stopService(Intent(this@MainActivity, BridgeService::class.java))
                else startConnection()
            }
        }
        connection.addView(connect)
        content.addView(connection)
        configDetail = row(content, "Tunnel settings", "") { connectionSettings() }
        accessDetail = row(content, "Phone control", "Accessibility access required") { accessibilitySettings() }
        divider(content)

        section(content, "Tools")
        toggle(content, "clock", "Timers and alarms", "Use the phone’s clock app", null)
        toggle(content, "contacts", "Contacts", "Find names and phone numbers", Manifest.permission.READ_CONTACTS)
        toggle(content, "sms", "Messaging", "Send SMS using the default SIM", Manifest.permission.SEND_SMS)
        toggle(content, "calls", "Phone calls", "Call a phone number", Manifest.permission.CALL_PHONE)
        toggle(content, "screen", "Screen control", "Read, tap, swipe and type in apps", null)
        toggle(content, "finder", "Find phone", "Play a loud, timed alarm sound", null)
        toggle(content, "location", "Location", "Share this phone’s current location", Manifest.permission.ACCESS_COARSE_LOCATION)
        toggle(content, "dnd", "Do Not Disturb", "Control the Phone Link mode", null)
        content.addView(label("Screen control can also use messaging and phone apps, even when their dedicated tools are off.",
            com.google.android.material.R.style.TextAppearance_Material3_BodySmall, true).apply {
            setPadding(dp(20), dp(8), dp(20), dp(16))
        })
        divider(content)
        row(content, "Connection diagnostics", "Recent activity, timing and retries") { diagnostics() }
        row(content, "Connect in ChatGPT", "Add a custom MCP server using your tunnel") {
            MaterialAlertDialogBuilder(this).setTitle("Connect in ChatGPT")
                .setMessage("In ChatGPT, open Plugins → Add custom MCP server. Choose Tunnel and select your tunnel.\n\nThe tunnel must be associated with your ChatGPT workspace. Keep Phone Link connected, then ask ChatGPT to check the phone’s status.")
                .setPositiveButton("Done", null).setNeutralButton("Setup guide") { _, _ -> openGuide() }.show()
        }
        content.addView(label("Version ${BuildConfig.VERSION_NAME}", com.google.android.material.R.style.TextAppearance_Material3_BodySmall, true)
            .apply { setPadding(dp(20), dp(16), dp(20), 0) })
    }

    override fun onResume() {
        super.onResume()
        if (pendingDndEnable) {
            if (getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted) state.enable("dnd", true)
            pendingDndEnable = false
        }
        handler.post(ticker)
    }
    override fun onPause() { handler.removeCallbacks(ticker); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("pending_dnd", pendingDndEnable); super.onSaveInstanceState(outState) }

    private fun startConnection() {
        if (PhoneAccessibilityService.instance == null) { accessibilitySettings(); return }
        if (state.tunnelId.isBlank() || !state.hasKey()) { connectionSettings(); return }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 50); return
        }
        startForegroundService(Intent(this, BridgeService::class.java))
    }

    private fun refresh() {
        val ready = PhoneAccessibilityService.instance != null
        accessDetail.text = if (ready) "Accessibility enabled" else "Accessibility access required"
        configDetail.text = if (state.hasKey() && state.tunnelId.isNotBlank()) "Tunnel configured" else "Add a tunnel ID and runtime key"
        statusLabel.text = BridgeService.status
        val data = BridgeService.diagnostics()
        statusDetail.text = when {
            !BridgeService.running -> "Connect to enable phone tools"
            data.isNull("last_delivery_age_ms") -> "Waiting for the first reply"
            else -> "Last reply ${age(data.optLong("last_delivery_age_ms"))}"
        }
        connect.text = if (BridgeService.running) "Disconnect" else "Connect"
        refreshing = true
        try {
            switches.forEach { (capability, pair) ->
                val granted = pair.second == null || checkSelfPermission(pair.second!!) == PackageManager.PERMISSION_GRANTED
                if (!granted && state.enabled(capability)) state.enable(capability, false)
                pair.first.isChecked = state.enabled(capability) && granted
            }
        } finally { refreshing = false }
    }

    private fun connectionSettings() {
        val form = column().apply { setPadding(dp(24), dp(12), dp(24), 0) }
        if (BridgeService.running) form.addView(label("Disconnect before changing tunnel settings.",
            com.google.android.material.R.style.TextAppearance_Material3_BodyMedium, true))
        fun input(title: String, value: String, secret: Boolean): TextInputEditText {
            val layout = TextInputLayout(this).apply {
                hint = title; boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
                if (secret) {
                    helperText = if (state.hasKey()) "Key saved. Leave blank to keep it." else "Encrypted on this phone using Android Keystore."
                    endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
                }
            }
            val field = TextInputEditText(layout.context).apply {
                setSingleLine(); setText(value); textSize = 14f
                inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                isSaveEnabled = false
            }
            layout.addView(field); layout.isEnabled = !BridgeService.running; form.addView(layout)
            return field
        }
        val tunnelId = input("Tunnel ID", state.tunnelId.ifBlank { BuildConfig.DEFAULT_TUNNEL_ID }, false)
        val credential = input("Runtime key", "", true)
        val dialog = MaterialAlertDialogBuilder(this).setTitle("Tunnel settings").setView(form)
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null)
            .setNeutralButton("Forget") { _, _ -> forgetConnection() }.create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).apply {
                isEnabled = !BridgeService.running
                setOnClickListener {
                    try {
                        state.save(tunnelId.text.toString().trim(), credential.text.toString().trim())
                        credential.setText(""); dialog.dismiss(); refresh(); toast("Connection saved")
                    } catch (e: IllegalArgumentException) {
                        toast(e.message ?: "Check connection details")
                    } catch (_: Exception) { toast("Could not save the credential. Try again.") }
                }
            }
        }
        dialog.show()
    }

    private fun forgetConnection() {
        MaterialAlertDialogBuilder(this).setTitle("Forget connection?")
            .setMessage("Disconnect and remove the saved credential from this phone. The tunnel in OpenAI Platform will remain.")
            .setPositiveButton("Forget") { _, _ ->
                stopService(Intent(this, BridgeService::class.java)); state.forget(); refresh()
            }.setNegativeButton("Cancel", null).show()
    }
    private fun accessibilitySettings() {
        MaterialAlertDialogBuilder(this).setTitle("Phone control")
            .setMessage("Enable Phone Link control in Accessibility settings. This allows screen reading and interaction with apps. The connection stops if access is disabled.\n\nIf Android blocks access, open App info → menu → Allow restricted settings first.")
            .setPositiveButton("Open settings") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .setNegativeButton("Cancel", null).show()
    }
    private fun toggle(parent: LinearLayout, name: String, title: String, detail: String, permission: String?) {
        val control = MaterialSwitch(this).apply { contentDescription = title }
        switches[name] = control to permission
        val row = settingRow(title, detail, control)
        row.setOnClickListener { control.toggle() }
        control.setOnCheckedChangeListener { _, checked ->
            if (!refreshing) {
                if (checked && name == "dnd" && !getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted) {
                    state.enable(name, false); control.isChecked = false; pendingDndEnable = true
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                } else if (checked && permission != null && checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                    state.enable(name, false); control.isChecked = false
                    val requested = if (permission == Manifest.permission.ACCESS_COARSE_LOCATION)
                        arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION) else arrayOf(permission)
                    requestPermissions(requested, 100 + switches.keys.indexOf(name))
                } else {
                    state.enable(name, checked)
                    if (name == "location" && checked) enableLocationForeground()
                    if (name == "finder" && !checked) FindPhoneSound.stop(this)
                }
            }
        }
        parent.addView(row)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            toast("Permission not granted. You can change it in app settings."); return
        }
        if (requestCode == 50) startConnection()
        else switches.keys.elementAtOrNull(requestCode - 100)?.let {
            state.enable(it, true)
            if (it == "location") enableLocationForeground()
            refresh()
        }
    }
    private fun enableLocationForeground() {
        if (BridgeService.running) startService(Intent(this, BridgeService::class.java).setAction("ENABLE_LOCATION"))
    }

    private fun diagnostics() {
        val d = BridgeService.diagnostics()
        fun recent(key: String) = if (d.isNull(key)) "None yet" else age(d.optLong(key))
        MaterialAlertDialogBuilder(this).setTitle("Connection diagnostics")
            .setMessage("${BridgeService.status}\n\n" +
                "Last poll: ${recent("last_poll_age_ms")}\nLast reply: ${recent("last_delivery_age_ms")}\n" +
                "Active tools: ${d.optInt("active_tools") + d.optInt("active_screen")}\nQueued tools: ${d.optInt("queued_tools")}\n" +
                "Uploading: ${d.optInt("active_uploads")}\nQueued replies: ${d.optInt("queued_uploads")}\n" +
                "Received / delivered: ${d.optLong("received")} / ${d.optLong("delivered")}\n" +
                "Retries: ${d.optLong("retries")}\nExpired: ${d.optLong("expired")}\nErrors: ${d.optLong("errors")}\n\n" +
                "Last queue wait: ${d.optLong("last_queue_ms")} ms\nLast execution: ${d.optLong("last_execution_ms")} ms\n" +
                "Last upload: ${d.optLong("last_delivery_ms")} ms\n\n" +
                "Counters reset when you reconnect. A successful poll alone does not verify the complete connection.")
            .setPositiveButton("Done", null).show()
    }
    private fun age(ms: Long) = when {
        ms < 1000 -> "just now"
        ms < 60_000 -> "${ms / 1000}s ago"
        else -> "${ms / 60_000}m ago"
    }
    private fun settingRow(title: String, detail: String, trailing: View? = null): LinearLayout {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(76)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            val selectable = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, selectable, true)
            setBackgroundResource(selectable.resourceId)
        }
        val labels = column()
        labels.addView(label(title, com.google.android.material.R.style.TextAppearance_Material3_BodyLarge))
        labels.addView(label(detail, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium, true))
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        trailing?.let { row.addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(16) }) }
        return row
    }
    private fun row(parent: LinearLayout, title: String, detail: String, action: () -> Unit): TextView {
        val row = settingRow(title, detail)
        row.setOnClickListener { action() }; parent.addView(row)
        return (row.getChildAt(0) as LinearLayout).getChildAt(1) as TextView
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun section(parent: LinearLayout, title: String) {
        parent.addView(label(title, com.google.android.material.R.style.TextAppearance_Material3_TitleSmall).apply {
            setTextColor(MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary))
            setPadding(dp(20), dp(20), dp(20), dp(4))
        })
    }
    private fun divider(parent: LinearLayout) { parent.addView(MaterialDivider(this)) }
    private fun label(value: String, appearance: Int, muted: Boolean = false) = MaterialTextView(this).apply {
        text = value; setTextAppearance(appearance); setTextColor(if (muted) secondary else onSurface)
    }
    private fun openGuide() { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://developers.openai.com/api/docs/guides/secure-mcp-tunnels"))) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
}
