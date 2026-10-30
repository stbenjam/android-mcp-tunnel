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
import dev.androidmcp.core.ActionNotPerformed
import dev.androidmcp.core.MessagesUi
import dev.androidmcp.core.SendAttempt
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
        val attempt = SendAttempt()
        return try { sendInternal(number, text, attempt) } catch (e: Exception) {
            if (attempt.cancelBeforeAttempt()) {
                val detail = if (e is ToolFailure) e.message.orEmpty().replace(Regex("(?i)[ ;]*nothing was sent\\.$"), "").trimEnd('.') + "."
                    else "Google Messages could not prepare this message."
                throw ActionNotPerformed("$detail Nothing was sent.")
            }
            // Once Send starts, a UI timeout or false callback cannot prove the message was not sent.
            obj("status" to "indeterminate", "success" to JSONObject.NULL, "action_performed" to JSONObject.NULL,
                "message" to "The Send action was attempted but its result is unknown. Check the conversation before sending again.")
        }
    }

    private fun sendInternal(number: String, text: String, attempt: SendAttempt): JSONObject {
        if (Telephony.Sms.getDefaultSmsPackage(context) != PACKAGE)
            throw ToolFailure("Set Google Messages as the default messaging app to send while unlocked, or use send_sms for SMS only.")
        val deadline = SystemClock.elapsedRealtime() + 12_000
        fun openTarget() = service.launch(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)).setPackage(PACKAGE))
        // Re-launching the same conversation can briefly clear its unsaved editor during restoration.
        // Inspect the open conversation first, and verify its recipient before deciding to navigate.
        var previousDraft = ""
        var reuseCurrent = service.onMain {
            service.unlocked()
            withNodes { nodes, ui ->
                val editor = nodes.indexOfFirst { it.isVisibleToUser && it.isEditable && it.viewIdResourceName == "$PACKAGE:id/compose_message_text" }
                if (editor < 0 || ui.header(editor) == null) false else {
                    previousDraft = if (nodes[editor].isShowingHintText) "" else nodes[editor].text?.toString().orEmpty()
                    true
                }
            } ?: false
        }
        if (!reuseCurrent) openTarget()
        var verifiedHeader: String? = null
        var inspectingHeader: String? = null
        var stage = "opening the conversation"
        var stableDraft: Pair<String, String>? = null
        var stableSince = 0L
        while (SystemClock.elapsedRealtime() < deadline && !Thread.currentThread().isInterrupted) {
            val outcome = service.onMain {
                service.unlocked()
                withNodes { nodes, ui ->
                    val editorIndex = nodes.indexOfFirst {
                        it.isVisibleToUser && it.isEditable && it.viewIdResourceName == "$PACKAGE:id/compose_message_text"
                    }
                    val editor = nodes.getOrNull(editorIndex)
                    if (inspectingHeader != null) {
                        stage = "verifying recipient details"
                        if (editor != null) return@withNodes null // Details view is still opening.
                        val labels = nodes.filter { it.isVisibleToUser }.mapNotNull { it.text?.toString() }
                        if (labels.none { it == "Add people" }) return@withNodes null
                        if (labels.none { it == "1 other person" } || labels.none { matchesNumber(it, number) }) {
                            if (reuseCurrent) {
                                // The currently open conversation belongs to someone else.
                                reuseCurrent = false; previousDraft = ""; inspectingHeader = null
                                service.key("back"); openTarget()
                                return@withNodes null
                            }
                            throw ToolFailure("The Google Messages recipient could not be verified. Nothing was sent.")
                        }
                        verifiedHeader = inspectingHeader; inspectingHeader = null
                        service.key("back")
                        return@withNodes null
                    }
                    if (editor == null) return@withNodes null
                    stage = "recognizing the conversation title"
                    val header = ui.header(editorIndex) ?: return@withNodes null
                    val title = header.title
                    if (!matchesNumber(title, number) && title != verifiedHeader) {
                        stage = "opening recipient details"
                        val opened = header.button?.let { nodes[it].performAction(AccessibilityNodeInfo.ACTION_CLICK) } ?: false
                        if (!opened)
                            throw ToolFailure("Google Messages conversation details could not be opened. Nothing was sent.")
                        inspectingHeader = title
                        return@withNodes null
                    }
                    // EditText exposes its hint as accessibility text when the draft is empty.
                    stage = "preparing the draft"
                    val draft = if (editor.isShowingHintText) "" else editor.text?.toString().orEmpty()
                    if ((previousDraft.isNotEmpty() && previousDraft != text) || (draft.isNotEmpty() && draft != text))
                        throw ToolFailure("This conversation already has a different draft. Clear or send that draft first; nothing was sent.")
                    // Give the editor's asynchronous draft restoration time to settle before editing.
                    val observed = title to draft
                    if (stableDraft != observed) {
                        stableDraft = observed; stableSince = SystemClock.elapsedRealtime()
                        return@withNodes null
                    }
                    if (SystemClock.elapsedRealtime() - stableSince < 300) return@withNodes null
                    if (draft != text) {
                        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
                        if (!editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
                            throw ToolFailure("Google Messages rejected the draft. Nothing was sent.")
                        return@withNodes null // Re-read the exact draft before clicking Send.
                    }
                    stage = "recognizing the Send control"
                    val send = ui.sendButton(editorIndex) ?: return@withNodes null
                    if (!attempt.begin()) throw ToolFailure("This send attempt was cancelled.")
                    val accepted = clickSend(nodes[send.button])
                    if (!accepted) throw ToolFailure("Google Messages did not accept the Send action. Check the conversation before retrying.")
                    obj("success" to true, "status" to "completed", "message" to "Text sent.",
                        "completion_basis" to "android_accepted", "route" to "google_messages", "transport" to send.transport)
                }
            }
            if (outcome != null) return outcome
            Thread.sleep(150)
        }
        throw ToolFailure("Google Messages timed out while $stage.")
    }

    private fun <T> withNodes(action: (List<AccessibilityNodeInfo>, MessagesUi) -> T?): T? {
        val root = service.rootInActiveWindow ?: return null
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        val snapshots = mutableListOf<MessagesUi.Node>()
        try {
            if (root.packageName?.toString() != PACKAGE) return null
            fun visit(node: AccessibilityNodeInfo, parent: Int, depth: Int) {
                val index = nodes.size
                nodes.add(node)
                val bounds = Rect().also(node::getBoundsInScreen)
                snapshots.add(MessagesUi.Node(parent, node.viewIdResourceName.orEmpty(), node.text?.toString().orEmpty(),
                    node.contentDescription?.toString().orEmpty(), node.isVisibleToUser, node.isEnabled, node.isClickable,
                    node.isEditable, MessagesUi.Bounds(bounds.left, bounds.top, bounds.right, bounds.bottom)))
                if (depth > 30 || nodes.size > 1000) return
                for (i in 0 until node.childCount) {
                    if (nodes.size > 1000) break
                    node.getChild(i)?.let { visit(it, index, depth + 1) }
                }
            }
            visit(root, -1, 0)
            return action(nodes, MessagesUi(snapshots, (112 * context.resources.displayMetrics.density).toInt()))
        } finally {
            if (nodes.isEmpty()) recycle(root) else nodes.forEach(::recycle)
        }
    }
    @Suppress("DEPRECATION") private fun recycle(node: AccessibilityNodeInfo) { node.recycle() }
}
