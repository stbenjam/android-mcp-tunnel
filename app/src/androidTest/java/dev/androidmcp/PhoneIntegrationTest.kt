package dev.androidmcp

import android.Manifest
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.androidmcp.core.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PhoneIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private lateinit var state: AppState
    private lateinit var server: McpServer
    private var previousServices: String? = null
    private var previousEnabled: String? = null
    private fun shell(command: String): String = automation.executeShellCommand(command).use { fd ->
        java.io.FileInputStream(fd.fileDescriptor).bufferedReader().readText()
    }
    @Before fun setup() {
        previousServices = Settings.Secure.getString(context.contentResolver, "enabled_accessibility_services")
        previousEnabled = Settings.Secure.getString(context.contentResolver, "accessibility_enabled")
        shell("settings put secure enabled_accessibility_services dev.androidmcp/dev.androidmcp.PhoneAccessibilityService")
        shell("settings put secure accessibility_enabled 1")
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        await { context.getSystemService(android.os.PowerManager::class.java).isInteractive }
        await { PhoneAccessibilityService.instance != null }
        state = AppState(context)
        server = McpServer(PhoneTools(context, state).all())
        state.enable("screen", true); state.enable("clock", true)
    }
    @After fun cleanup() {
        context.stopService(Intent(context, BridgeService::class.java))
        state.forget()
        listOf("screen", "sms", "calls", "contacts").forEach { state.enable(it, false) }
        if (previousServices.isNullOrBlank()) shell("settings delete secure enabled_accessibility_services")
        else shell("settings put secure enabled_accessibility_services $previousServices")
        shell("settings put secure accessibility_enabled ${previousEnabled ?: "0"}")
    }
    private fun call(name: String, args: JSONObject = obj()): JSONObject {
        val response = server.handle(obj("jsonrpc" to "2.0", "id" to 1, "method" to "tools/call",
            "params" to obj("name" to name, "arguments" to args)))!!.getJSONObject("result")
        assertFalse(response.toString(), response.getBoolean("isError"))
        return JSONObject(response.getJSONArray("content").getJSONObject(0).getString("text"))
    }
    private fun await(diagnostic: () -> String = { "Android state" }, predicate: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 15000
        while (!predicate()) { if (SystemClock.elapsedRealtime() > until) fail("Timed out waiting for ${diagnostic()}"); Thread.sleep(150) }
    }
    private fun fixture() {
        context.startActivity(Intent().setComponent(ComponentName("dev.androidmcp.test", "dev.androidmcp.FixtureActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var last = "no screen"
        await({ "fixture: $last" }) {
            runCatching {
                val screen = call("get_screen"); last = screen.toString().take(1000)
                screen.getString("package") == "dev.androidmcp.test" && screen.toString().contains("Tap target", ignoreCase = true)
            }.onFailure { last = it.message ?: it.javaClass.simpleName }.getOrDefault(false)
        }
    }
    @Test fun deviceStatusAndCredentialEncryption() {
        val status = call("device_status")
        assertTrue(status.getBoolean("accessibility")); assertTrue(status.getInt("api") >= 30)
        val fakeKey = "test-only-credential-not-a-real-key"
        state.save("tunnel_" + "a".repeat(32), fakeKey)
        assertEquals(fakeKey, AppState(context).key())
        val preferences = context.getSharedPreferences("settings", 0)
        assertFalse(preferences.all.toString().contains(fakeKey))
        assertFalse(call("device_status").toString().contains(fakeKey))
        state.forget(); assertEquals("", AppState(context).key())
    }
    @Test fun ownScreenProtectedAndDashboardRenders() {
        state.forget()
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        try {
            assertTrue(activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0)
            val response = server.handle(obj("jsonrpc" to "2.0", "id" to 1, "method" to "tools/call",
                "params" to obj("name" to "get_screen", "arguments" to obj())))!!.getJSONObject("result")
            assertTrue(response.getBoolean("isError"))
            instrumentation.runOnMainSync {
                val root = activity.findViewById<android.widget.FrameLayout>(android.R.id.content)
                val content = root
                val bitmap = android.graphics.Bitmap.createBitmap(content.width, content.height, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                content.draw(canvas)
                val values = ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, "PhoneLink-dashboard-${System.currentTimeMillis()}.png")
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "image/png")
                    put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/PhoneLink")
                }
                val uri = context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
                context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
    @Test fun screenTapAndTextEntryActuallyControlAnotherApp() {
        fixture()
        fun node(text: String): JSONObject {
            val nodes = call("get_screen").getJSONArray("nodes")
            return (0 until nodes.length()).map { nodes.getJSONObject(it) }.firstOrNull {
                it.optString("text") == text || it.optString("description") == text
            } ?: throw AssertionError("No node '$text' in $nodes")
        }
        fun tap(n: JSONObject) {
            val b = n.getJSONArray("bounds")
            call("tap", obj("x" to (b.getInt(0) + b.getInt(2)) / 2, "y" to (b.getInt(1) + b.getInt(3)) / 2))
        }
        tap(node("Tap target"))
        await { runCatching { node("Tapped successfully"); true }.getOrDefault(false) }
        val nodes = call("get_screen").getJSONArray("nodes")
        val editable = (0 until nodes.length()).map { nodes.getJSONObject(it) }.first { it.getBoolean("editable") }
        tap(editable)
        // A gesture callback can precede the target app's focus/keyboard update.
        await({ "editable field focus" }) {
            val current = call("get_screen").getJSONArray("nodes")
            (0 until current.length()).map { current.getJSONObject(it) }.any {
                it.getBoolean("editable") && it.getBoolean("focused")
            }
        }
        call("type_text", obj("text" to "Automation worked"))
        await { runCatching { node("Automation worked"); true }.getOrDefault(false) }
        call("press_key", obj("key" to "home"))
    }
    @Test fun materialSettingsSaveCredentialsAndToggleCapabilities() {
        state.forget()
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun descendants(view: android.view.View): List<android.view.View> = listOf(view) +
            if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        try {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val views = descendants(activity.window.decorView)
                val toggle = views.filterIsInstance<com.google.android.material.materialswitch.MaterialSwitch>()
                    .first { it.contentDescription == "Timers and alarms" }
                assertTrue(toggle.isChecked); toggle.performClick(); assertFalse(state.enabled("clock"))
                val setting = views.filterIsInstance<android.widget.TextView>().first { it.text.toString() == "Tunnel settings" }
                ((setting.parent as android.view.View).parent as android.view.View).performClick()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val windows = android.view.inspector.WindowInspector.getGlobalWindowViews()
                val fields = windows.flatMap(::descendants).filterIsInstance<com.google.android.material.textfield.TextInputEditText>()
                assertEquals(2, fields.size)
                assertTrue(fields[1].transformationMethod is android.text.method.PasswordTransformationMethod)
                fields[0].setText("tunnel_" + "b".repeat(32)); fields[1].setText("test-only-settings-key")
                windows.flatMap(::descendants).filterIsInstance<android.widget.Button>().first { it.text.toString() == "Save" }.performClick()
            }
            assertEquals("tunnel_" + "b".repeat(32), state.tunnelId)
            assertEquals("test-only-settings-key", state.key())
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
    @Test fun clockRequestsUseAndroidIntentsAndCannotReplay() {
        fixture()
        val timerMonitor = instrumentation.addMonitor(IntentFilter(AlarmClock.ACTION_SET_TIMER), Instrumentation.ActivityResult(0, null), true)
        val alarmMonitor = instrumentation.addMonitor(IntentFilter(AlarmClock.ACTION_SET_ALARM), Instrumentation.ActivityResult(0, null), true)
        try {
            val args = obj("seconds" to 60, "label" to "Phone Link test", "request_id" to UUID.randomUUID().toString())
            assertEquals("requested", call("set_timer", args).getString("status"))
            call("set_timer", args)
            assertEquals(1, timerMonitor.hits)
            call("set_alarm", obj("hour" to 7, "minute" to 30, "request_id" to UUID.randomUUID().toString()))
            assertEquals(1, alarmMonitor.hits)
        } finally { instrumentation.removeMonitor(timerMonitor); instrumentation.removeMonitor(alarmMonitor) }
    }
    @Test fun contactsSearchUsesGrantedProviderPermission() {
        automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_CONTACTS, Manifest.permission.READ_CONTACTS)
        val resolver = context.contentResolver
        val raw = resolver.insert(ContactsContract.RawContacts.CONTENT_URI, ContentValues().apply {
            putNull(ContactsContract.RawContacts.ACCOUNT_NAME); putNull(ContactsContract.RawContacts.ACCOUNT_TYPE)
        })!!
        val rawId = android.content.ContentUris.parseId(raw)
        try {
            resolver.insert(ContactsContract.Data.CONTENT_URI, ContentValues().apply {
                put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, "PhoneLink Fixture")
            })
            resolver.insert(ContactsContract.Data.CONTENT_URI, ContentValues().apply {
                put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                put(ContactsContract.CommonDataKinds.Phone.NUMBER, "+12025550123")
                put(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
            })
            automation.dropShellPermissionIdentity()
            shell("pm grant dev.androidmcp android.permission.READ_CONTACTS")
            state.enable("contacts", true)
            val contacts = call("search_contacts", obj("query" to "PhoneLink Fixture")).getJSONArray("contacts")
            assertEquals(1, contacts.length()); assertEquals("+12025550123", contacts.getJSONObject(0).getString("number"))
        } finally {
            automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_CONTACTS)
            resolver.delete(raw, null, null)
            automation.dropShellPermissionIdentity()
        }
    }
    @Test fun callsAndSmsAreBlockedWhenDisabledAndNoAccessibilityCannotStart() {
        state.enable("sms", false); state.enable("calls", false)
        for (name in listOf("send_sms", "place_call")) {
            val args = obj("number" to "+12025550123", "request_id" to UUID.randomUUID().toString())
            if (name == "send_sms") args.put("text", "Never send this test")
            val result = server.handle(obj("jsonrpc" to "2.0", "id" to 1, "method" to "tools/call",
                "params" to obj("name" to name, "arguments" to args)))!!.getJSONObject("result")
            assertTrue(result.getBoolean("isError"))
        }
        shell("settings delete secure enabled_accessibility_services")
        shell("settings put secure accessibility_enabled 0")
        await { PhoneAccessibilityService.instance == null }
        assertThrows(ToolFailure::class.java) { PhoneAccessibilityService.requireService() }
        assertFalse(BridgeService.running)
    }
}
