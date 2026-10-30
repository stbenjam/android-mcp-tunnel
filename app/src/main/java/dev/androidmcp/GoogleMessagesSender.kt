package dev.androidmcp

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Telephony
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.view.accessibility.AccessibilityNodeInfo
import dev.androidmcp.core.ToolFailure
import dev.androidmcp.core.obj
import org.json.JSONObject
import java.util.Locale

/** A bounded, one-send UI flow. Never falls back to SMS after starting this route. */
class GoogleMessagesSender(
    private val context: Context,
    private val service: PhoneAccessibilityService,
    // Injection is only used by instrumentation to inspect the real Messages UI without sending.
    private val clickSend: (AccessibilityNodeInfo) -> Boolean = { it.performAction(AccessibilityNodeInfo.ACTION_CLICK) },
) {
    companion object { const val PACKAGE = "com.google.android.apps.messaging" }
    private val region: String get() = context.getSystemService(TelephonyManager::class.java).networkCountryIso
        .ifBlank { Locale.getDefault().country }.uppercase(Locale.ROOT)
    private fun matchesNumber(value: String, number: String): Boolean {
        if (!value.matches(Regex("\\+?[0-9 ()\\-.]{3,40}"))) return false
        val expected = PhoneNumberUtils.formatNumberToE164(number, region)
        val actual = PhoneNumberUtils.formatNumberToE164(value, region)
        return if (expected != null && actual != null) expected == actual
            else value.filter(Char::isDigit) == number.filter(Char::isDigit)
    }

    fun send(number: String, text: String): JSONObject {
        if (Telephony.Sms.getDefaultSmsPackage(context) != PACKAGE)
            throw ToolFailure("Set Google Messages as the default messaging app to send while unlocked, or use send_sms for SMS only.")
        val deadline = SystemClock.elapsedRealtime() + 12_000
        // Open the conversation without replacing an existing draft.
        service.launch(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)).setPackage(PACKAGE))
        var verifiedHeader: String? = null
        var inspectingHeader: String? = null
        while (SystemClock.elapsedRealtime() < deadline && !Thread.currentThread().isInterrupted) {
            val outcome = service.onMain {
                withNodes { nodes ->
                    val editor = nodes.firstOrNull {
                        it.isVisibleToUser && it.isEditable && it.viewIdResourceName == "$PACKAGE:id/compose_message_text"
                    }
                    if (inspectingHeader != null) {
                        if (editor != null) return@withNodes null // Details view is still opening.
                        val labels = nodes.filter { it.isVisibleToUser }.mapNotNull { it.text?.toString() }
                        if (labels.none { it == "Add people" }) return@withNodes null
                        if (labels.none { it == "1 other person" } || labels.none { matchesNumber(it, number) })
                            throw ToolFailure("The Google Messages recipient could not be verified. Nothing was sent.")
                        verifiedHeader = inspectingHeader; inspectingHeader = null
                        service.key("back")
                        return@withNodes null
                    }
                    if (editor == null) return@withNodes null
                    val scope = nodes.firstOrNull { it.viewIdResourceName == "$PACKAGE:id/conversation_root_container" }
                        ?: nodes.first()
                    val scopeBounds = Rect().also(scope::getBoundsInScreen)
                    val headerBottom = scopeBounds.top + (112 * context.resources.displayMetrics.density).toInt()
                    val headers = nodes.filter {
                        val bounds = Rect().also(it::getBoundsInScreen)
                        it.isVisibleToUser && !it.isEditable && !it.text.isNullOrBlank() &&
                            scopeBounds.contains(bounds) && bounds.bottom <= headerBottom &&
                            it.text.toString() !in setOf("Messages", "Search")
                    }
                    val header = headers.firstOrNull { matchesNumber(it.text.toString(), number) } ?: headers.firstOrNull()
                        ?: return@withNodes null
                    val title = header.text.toString()
                    if (!matchesNumber(title, number) && title != verifiedHeader) {
                        val detailsButton = clickableAncestor(header)
                        val opened = if (detailsButton == null) false else try {
                            detailsButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        } finally { if (detailsButton !== header) recycle(detailsButton) }
                        if (!opened)
                            throw ToolFailure("Google Messages conversation details could not be opened. Nothing was sent.")
                        inspectingHeader = title
                        return@withNodes null
                    }
                    // EditText exposes its hint as accessibility text when the draft is empty.
                    val draft = if (editor.isShowingHintText) "" else editor.text?.toString().orEmpty()
                    if (draft.isNotEmpty() && draft != text)
                        throw ToolFailure("This conversation already has a different draft. Clear or send that draft first; nothing was sent.")
                    if (draft != text) {
                        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
                        if (!editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
                            throw ToolFailure("Google Messages rejected the draft. Nothing was sent.")
                        return@withNodes null // Re-read the exact draft before clicking Send.
                    }
                    val editorBounds = Rect().also(editor::getBoundsInScreen)
                    val sendNode = nodes.firstOrNull {
                        val bounds = Rect().also(it::getBoundsInScreen)
                        val label = it.contentDescription?.toString().orEmpty().lowercase(Locale.ROOT)
                        it.isVisibleToUser && it.isEnabled && scopeBounds.contains(bounds) &&
                            bounds.centerY() >= editorBounds.top - (48 * context.resources.displayMetrics.density).toInt() &&
                            label in setOf("send sms", "send mms", "send rcs message", "send rcs", "send message", "send chat message")
                    } ?: return@withNodes null
                    val transport = when {
                        sendNode.contentDescription.toString().contains("SMS", true) -> "sms"
                        sendNode.contentDescription.toString().contains("MMS", true) -> "mms"
                        sendNode.contentDescription.toString().contains("RCS", true) || sendNode.contentDescription.toString().contains("chat", true) -> "rcs"
                        else -> "messages_auto"
                    }
                    val button = clickableAncestor(sendNode) ?: return@withNodes null
                    val accepted = try { clickSend(button) } finally { if (button !== sendNode) recycle(button) }
                    if (!accepted) throw ToolFailure("Google Messages did not accept the Send action. Check the conversation before retrying.")
                    obj("success" to true, "status" to "completed", "message" to "Text sent.",
                        "completion_basis" to "android_accepted", "route" to "google_messages", "transport" to transport)
                }
            }
            if (outcome != null) return outcome
            Thread.sleep(150)
        }
        throw ToolFailure("Google Messages was not ready to send in time. Check the conversation before retrying; no automatic SMS retry was made.")
    }

    /** Returns a borrowed node when already clickable, otherwise an owned parent. */
    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable && node.isEnabled) return node
        var parent = node.parent
        repeat(4) {
            val current = parent ?: return null
            if (current.isClickable && current.isEnabled) return current
            parent = current.parent; recycle(current)
        }
        parent?.let(::recycle)
        return null
    }

    private fun <T> withNodes(action: (List<AccessibilityNodeInfo>) -> T?): T? {
        val root = service.rootInActiveWindow ?: return null
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        try {
            if (root.packageName?.toString() != PACKAGE) return null
            fun visit(node: AccessibilityNodeInfo, depth: Int) {
                nodes.add(node)
                if (depth > 30 || nodes.size > 1000) return
                for (i in 0 until node.childCount) {
                    if (nodes.size > 1000) break
                    node.getChild(i)?.let { visit(it, depth + 1) }
                }
            }
            visit(root, 0)
            return action(nodes)
        } finally {
            if (nodes.isEmpty()) recycle(root) else nodes.forEach(::recycle)
        }
    }
    @Suppress("DEPRECATION") private fun recycle(node: AccessibilityNodeInfo) { node.recycle() }
}
