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
        listOf("screen", "sms", "calls", "contacts", "finder", "location", "dnd").forEach { state.enable(it, false) }
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
            val timer = call("set_timer", args)
            assertEquals("completed", timer.getString("status"))
            assertTrue(timer.getBoolean("success")); assertEquals("Timer set.", timer.getString("message"))
            assertEquals("android_accepted", timer.getString("completion_basis"))
            call("set_timer", args)
            assertEquals(1, timerMonitor.hits)
            val alarm = call("set_alarm", obj("hour" to 7, "minute" to 30, "request_id" to UUID.randomUUID().toString()))
            assertTrue(alarm.getBoolean("success")); assertEquals("Alarm set.", alarm.getString("message"))
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
            val service = PhoneAccessibilityService.requireService()
            service.launch(Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:+12025550123")).setPackage(GoogleMessagesSender.PACKAGE))
            await({ "named contact in Messages" }) {
                runCatching { call("get_screen").toString().contains("PhoneLink Fixture") }.getOrDefault(false)
            }
            var sends = 0
            val body = "Phone Link named contact test"
            try {
                val result = GoogleMessagesSender(context, service) { sends++; true }.send("+12025550123", body)
                assertTrue(result.getBoolean("success")); assertEquals(1, sends)
            } finally {
                service.onMain {
                    val root = service.rootInActiveWindow
                    val fields = root?.findAccessibilityNodeInfosByViewId("${GoogleMessagesSender.PACKAGE}:id/compose_message_text").orEmpty()
                    try {
                        fields.filter { it.text?.toString() == body }.forEach {
                            it.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,
                                android.os.Bundle().apply { putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "") })
                        }
                    } finally {
                        @Suppress("DEPRECATION") fields.forEach { it.recycle() }
                        @Suppress("DEPRECATION") root?.recycle()
                    }
                }
            }
        } finally {
            automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_CONTACTS)
            resolver.delete(raw, null, null)
            automation.dropShellPermissionIdentity()
        }
    }
    @Test fun googleMessagesVerifiesDraftAndRecipientBeforeSingleSendClick() {
        val service = PhoneAccessibilityService.requireService()
        val number = "+12025550123"
        val body = "Phone Link test draft"
        // The real Google Messages UI is exercised, but the final send click is intercepted.
        var sends = 0
        val sender = GoogleMessagesSender(context, service) { sends++; true }
        val result = service.withControlIndicator { sender.send(number, body) }
        assertEquals(1, sends); assertTrue(result.getBoolean("success"))
        assertEquals("google_messages", result.getString("route"))
        assertEquals("Text sent.", result.getString("message"))
        // The intercepted click leaves our draft intact. A different request must not overwrite it.
        assertThrows(ToolFailure::class.java) { sender.send(number, "A different draft") }
        assertEquals(1, sends)
        service.onMain {
            val root = service.rootInActiveWindow!!
            val fields = root.findAccessibilityNodeInfosByViewId("${GoogleMessagesSender.PACKAGE}:id/compose_message_text")
            try {
                val field = fields.single()
                assertEquals(body, field.text.toString())
                assertTrue(field.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,
                    android.os.Bundle().apply { putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "") }))
            } finally {
                @Suppress("DEPRECATION") fields.forEach { it.recycle() }
                @Suppress("DEPRECATION") root.recycle()
            }
        }
    }

    @Test fun controlBannerDoesNotBlockUiAndDisappearsAfterWork() {
        fixture()
        val service = PhoneAccessibilityService.requireService()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        fun overlay(): android.view.View? = android.view.inspector.WindowInspector.getGlobalWindowViews().firstOrNull {
            (it.layoutParams as? android.view.WindowManager.LayoutParams)?.type == android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        }
        try {
            val job = pool.submit { service.withControlIndicator { entered.countDown(); release.await() } }
            assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                val banner = overlay() ?: throw AssertionError("Control banner is missing")
                val flags = (banner.layoutParams as android.view.WindowManager.LayoutParams).flags
                assertTrue(flags and android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
                assertTrue(flags and android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
            }
            val screen = call("get_screen")
            assertEquals("dev.androidmcp.test", screen.getString("package"))
            val nodes = screen.getJSONArray("nodes")
            val target = (0 until nodes.length()).map { nodes.getJSONObject(it) }.first { it.optString("text") == "Tap target" }
            val bounds = target.getJSONArray("bounds")
            call("tap", obj("x" to (bounds.getInt(0) + bounds.getInt(2)) / 2, "y" to (bounds.getInt(1) + bounds.getInt(3)) / 2))
            await { call("get_screen").toString().contains("Tapped successfully") }
            automation.takeScreenshot()?.let { bitmap ->
                val values = ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, "PhoneLink-control-banner-${System.currentTimeMillis()}.png")
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "image/png")
                    put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/PhoneLink")
                }
                val uri = context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
                context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            release.countDown(); job.get(3, java.util.concurrent.TimeUnit.SECONDS)
            await({ "banner removal after last action" }) {
                var absent = false
                instrumentation.runOnMainSync { absent = overlay() == null }
                absent
            }
        } finally { release.countDown(); pool.shutdownNow() }
    }
    @Test fun callsAndSmsAreBlockedWhenDisabledAndNoAccessibilityCannotStart() {
        state.enable("sms", false); state.enable("calls", false)
        for (name in listOf("send_sms", "send_message", "place_call")) {
            val args = obj("number" to "+12025550123", "request_id" to UUID.randomUUID().toString())
            if (name == "send_sms" || name == "send_message") args.put("text", "Never send this test")
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

    @Test fun utilityCapabilitiesRejectDisabledAccess() {
        for ((name, capability) in listOf("find_phone" to "finder", "get_location" to "location", "set_do_not_disturb" to "dnd")) {
            state.enable(capability, false)
            val args = when (name) {
                "get_location" -> obj()
                "set_do_not_disturb" -> obj("mode" to "priority", "request_id" to UUID.randomUUID().toString())
                else -> obj("request_id" to UUID.randomUUID().toString())
            }
            val result = server.handle(obj("jsonrpc" to "2.0", "id" to 1, "method" to "tools/call",
                "params" to obj("name" to name, "arguments" to args)))!!.getJSONObject("result")
            assertTrue(result.getBoolean("isError"))
            assertFalse(JSONObject(result.getJSONArray("content").getJSONObject(0).getString("text")).getBoolean("success"))
        }
    }

    @Test fun locationUsesRecentMockFixAndIncludesAccuracyAndAge() {
        fixture()
        shell("pm grant dev.androidmcp android.permission.ACCESS_COARSE_LOCATION")
        shell("pm grant dev.androidmcp android.permission.ACCESS_FINE_LOCATION")
        shell("appops set dev.androidmcp android:mock_location allow")
        val manager = context.getSystemService(android.location.LocationManager::class.java)
        val provider = "phone_link_test"
        try {
            @Suppress("DEPRECATION")
            manager.addTestProvider(provider, false, false, false, false, true, true, true,
                android.location.Criteria.POWER_LOW, android.location.Criteria.ACCURACY_FINE)
            manager.setTestProviderEnabled(provider, true)
            manager.setTestProviderLocation(provider, android.location.Location(provider).apply {
                latitude = 37.42; longitude = -122.08; accuracy = 4f
                time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            })
            await { manager.getLastKnownLocation(provider) != null }
            val fix = PhoneLocation(context).current(1)
            assertEquals(37.42, fix.getDouble("latitude"), .000001)
            assertEquals(-122.08, fix.getDouble("longitude"), .000001)
            assertEquals(4.0, fix.getDouble("accuracy_m"), .001)
            assertTrue(fix.getDouble("age_seconds") < 30)
            assertEquals("recent_fix", fix.getString("source"))
        } finally {
            runCatching { manager.removeTestProvider(provider) }
            shell("appops set dev.androidmcp android:mock_location default")
        }
    }

    @Test fun dndModeUsesGrantedPolicyAccessAndCanBeTurnedOff() {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        val alreadyGranted = manager.isNotificationPolicyAccessGranted
        shell("cmd notification allow_dnd dev.androidmcp")
        state.enable("dnd", true)
        try {
            await { manager.isNotificationPolicyAccessGranted }
            val on = call("set_do_not_disturb", obj("mode" to "priority", "request_id" to UUID.randomUUID().toString()))
            assertTrue(on.getBoolean("success")); assertEquals("priority", on.getString("mode"))
            val off = call("set_do_not_disturb", obj("mode" to "off", "request_id" to UUID.randomUUID().toString()))
            assertTrue(off.getBoolean("success")); assertEquals("off", off.getString("mode"))
        } finally {
            manager.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_ALL)
            if (!alreadyGranted) shell("cmd notification disallow_dnd dev.androidmcp")
        }
    }

    @Test fun finderRestoresSavedVolumeWithoutOverridingManualAdjustment() {
        val audio = context.getSystemService(android.media.AudioManager::class.java)
        val stream = android.media.AudioManager.STREAM_ALARM
        val before = audio.getStreamVolume(stream)
        val max = audio.getStreamMaxVolume(stream)
        val prefs = context.getSharedPreferences("finder", 0)
        try {
            audio.setStreamVolume(stream, max, 0)
            prefs.edit().putInt("previous_volume", 1).putInt("forced_volume", max).commit()
            FindPhoneSound.recoverVolume(context)
            assertEquals(1, audio.getStreamVolume(stream)); assertFalse(prefs.contains("previous_volume"))
            audio.setStreamVolume(stream, 2, 0)
            prefs.edit().putInt("previous_volume", 1).putInt("forced_volume", max).commit()
            FindPhoneSound.recoverVolume(context)
            assertEquals(2, audio.getStreamVolume(stream)); assertFalse(prefs.contains("previous_volume"))
        } finally { audio.setStreamVolume(stream, before, 0); prefs.edit().clear().commit() }
    }
}
