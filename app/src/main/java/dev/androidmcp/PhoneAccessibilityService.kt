package dev.androidmcp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.Gravity
import android.view.View
import android.widget.TextView
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.androidmcp.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class PhoneAccessibilityService : AccessibilityService() {
    private val indicatorHandler = Handler(Looper.getMainLooper())
    private var controlIndicator: View? = null
    private var activeControls = 0
    private var indicatorGeneration = 0
    private val removeIndicator = Runnable { if (activeControls == 0) clearControlIndicator() }

    fun <T> withControlIndicator(action: () -> T): T {
        val generation = onMain {
            indicatorHandler.removeCallbacks(removeIndicator)
            if (controlIndicator == null) {
                fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
                val banner = TextView(this).apply {
                    text = "MCP is controlling this phone"
                    textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                    background = GradientDrawable().apply { setColor(Color.rgb(40, 64, 109)); cornerRadius = dp(8).toFloat() }
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                }
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = dp(8)
                    title = "Phone Link remote control indicator"
                }
                getSystemService(WindowManager::class.java).addView(banner, params)
                controlIndicator = banner
            }
            activeControls++
            indicatorGeneration
        }
        try { return action() }
        finally {
            indicatorHandler.post {
                if (generation == indicatorGeneration) {
                    activeControls = (activeControls - 1).coerceAtLeast(0)
                    if (activeControls == 0) indicatorHandler.postDelayed(removeIndicator, 2000)
                }
            }
        }
    }

    fun clearControlIndicator() {
        indicatorHandler.removeCallbacks(removeIndicator)
        controlIndicator?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        controlIndicator = null; activeControls = 0
        indicatorGeneration++
    }
    companion object {
        @Volatile var instance: PhoneAccessibilityService? = null
            private set
        fun requireService(): PhoneAccessibilityService = instance
            ?: throw ToolFailure("Enable Phone Link control in Android Accessibility settings first.")
    }
    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onUnbind(intent: Intent?): Boolean { disconnect(); return super.onUnbind(intent) }
    override fun onDestroy() { disconnect(); super.onDestroy() }
    private fun disconnect() {
        clearControlIndicator()
        if (instance === this) instance = null
        stopService(Intent(this, BridgeService::class.java))
    }

    internal fun unlocked() {
        if (getSystemService(KeyguardManager::class.java).isDeviceLocked)
            throw ToolFailure("The phone is locked. Unlock it before using screen controls or launching an action.")
        if (!getSystemService(android.os.PowerManager::class.java).isInteractive)
            throw ToolFailure("The screen is off. Wake the phone before using screen controls or launching an action.")
    }
    fun <T> onMain(action: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return action()
        val task = FutureTask(action)
        val handler = Handler(Looper.getMainLooper())
        handler.post(task)
        return try { task.get(5, TimeUnit.SECONDS) } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        } catch (e: Exception) {
            handler.removeCallbacks(task); task.cancel(false)
            throw ToolFailure("Phone UI did not respond in time. Check the phone before retrying.")
        }
    }

    fun launch(intent: Intent, message: String = "App opened."): JSONObject = onMain {
        unlocked()
        if (intent.resolveActivity(packageManager) == null) throw ToolFailure("No installed app can handle this action.")
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        obj("status" to "completed", "success" to true, "completion_basis" to "android_accepted", "message" to message)
    }

    fun screen(): JSONObject = onMain {
        unlocked()
        val root = rootInActiveWindow ?: throw ToolFailure("No accessible window is available.")
        try {
            if (root.packageName?.toString() == packageName)
                throw ToolFailure("Phone Link's own screen is excluded to protect tunnel credentials. Open another app first.")
            val nodes = JSONArray()
            var visited = 0
            fun visit(node: AccessibilityNodeInfo, depth: Int) {
                if (++visited > 600 || depth > 30 || nodes.length() >= 200) return
                if (node.isVisibleToUser) {
                    val bounds = Rect().also(node::getBoundsInScreen)
                    nodes.put(obj("view_id" to node.viewIdResourceName, "class" to node.className?.toString(),
                        "text" to if (node.isPassword) "[password]" else node.text?.toString()?.take(500),
                        "description" to if (node.isPassword) null else node.contentDescription?.toString()?.take(300),
                        "clickable" to node.isClickable, "editable" to node.isEditable, "focused" to node.isFocused,
                        "bounds" to arr(bounds.left, bounds.top, bounds.right, bounds.bottom)))
                }
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    try { visit(child, depth + 1) } finally { @Suppress("DEPRECATION") child.recycle() }
                }
            }
            visit(root, 0)
            obj("package" to root.packageName?.toString(), "nodes" to nodes,
                "truncated" to (visited > 600 || nodes.length() >= 200))
        } finally { @Suppress("DEPRECATION") root.recycle() }
    }

    fun type(text: String): JSONObject = onMain {
        unlocked()
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: throw ToolFailure("Focus an editable field first.")
        try {
            if (node.packageName?.toString() == packageName) throw ToolFailure("Remote edits to Phone Link settings are disabled.")
            if (!node.isEditable) throw ToolFailure("Focused field is not editable.")
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
            if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) throw ToolFailure("The app rejected text entry.")
            obj("status" to "completed")
        } finally { @Suppress("DEPRECATION") node.recycle() }
    }

    fun key(name: String): JSONObject = onMain {
        unlocked()
        val key = when (name) {
            "home" -> GLOBAL_ACTION_HOME; "back" -> GLOBAL_ACTION_BACK; "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS; "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            else -> throw ToolFailure("Unsupported navigation action")
        }
        if (!performGlobalAction(key)) throw ToolFailure("Android rejected the navigation action.")
        obj("status" to "completed")
    }

    fun gesture(x: Int, y: Int, endX: Int = x, endY: Int = y, duration: Long = 80): JSONObject {
        val done = CountDownLatch(1)
        var completed = false
        onMain {
            unlocked()
            val root = rootInActiveWindow
            try {
                if (root?.packageName?.toString() == packageName) throw ToolFailure("Remote taps on Phone Link settings are disabled.")
            } finally { @Suppress("DEPRECATION") root?.recycle() }
            val bounds = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
            if (!bounds.contains(x, y) || !bounds.contains(endX, endY)) throw ToolFailure("Coordinates are outside the screen.")
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()); if (x != endX || y != endY) lineTo(endX.toFloat(), endY.toFloat()) }
            val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
            if (!dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { completed = true; done.countDown() }
                override fun onCancelled(gestureDescription: GestureDescription?) { done.countDown() }
            }, null)) throw ToolFailure("Android rejected the gesture.")
        }
        if (!done.await(4, TimeUnit.SECONDS) || !completed) throw ToolFailure("Gesture did not complete. Read the screen before retrying.")
        return obj("status" to "completed")
    }
}
