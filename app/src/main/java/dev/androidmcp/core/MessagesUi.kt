package dev.androidmcp.core

import java.util.Locale

/** Structural selectors shared by the real Accessibility adapter and scrubbed UI regression tests. */
class MessagesUi(private val nodes: List<Node>, private val headerHeightPx: Int) {
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun contains(other: Bounds) = other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom
    }
    data class Node(
        val parent: Int = -1, val id: String = "", val text: String = "", val description: String = "",
        val visible: Boolean = true, val enabled: Boolean = true, val clickable: Boolean = false,
        val editable: Boolean = false, val bounds: Bounds = Bounds(0, 0, 0, 0),
    )
    data class Header(val title: String, val button: Int?)
    data class SendButton(val button: Int, val transport: String)

    private fun ancestors(index: Int): Sequence<Int> = generateSequence(index) { nodes[it].parent.takeIf { p -> p >= 0 } }
    private fun within(index: Int, root: Int) = ancestors(index).any { it == root }
    private fun history(index: Int) = ancestors(index).any { nodes[it].id == "message_list" }
    private fun scope(editor: Int) = ancestors(editor).firstOrNull {
        nodes[it].id == "com.google.android.apps.messaging:id/conversation_root_container"
    }
    private fun clickable(index: Int, root: Int): Int? = ancestors(index).takeWhile { it != root }.take(6)
        .firstOrNull { nodes[it].visible && nodes[it].enabled && nodes[it].clickable }

    fun header(editor: Int): Header? {
        val root = scope(editor) ?: return null
        val modern = nodes.indices.filter { nodes[it].visible && nodes[it].id == "top_app_bar_title_row" && within(it, root) }
        if (modern.isNotEmpty()) {
            // Never fall back to arbitrary text when the identified title row is present.
            val row = modern.singleOrNull() ?: return null
            val titles = nodes.indices.filter { within(it, row) && nodes[it].visible && !nodes[it].editable && nodes[it].text.isNotBlank() }
            val title = titles.singleOrNull()?.let { nodes[it].text } ?: return null
            return Header(title, clickable(row, root))
        }
        // Older Messages has no title-row ID. Its avatar is inside the clickable title control.
        // Exclude the message list explicitly, including avatars/text scrolled underneath the bar.
        val scopeBounds = nodes[root].bounds
        val headers = nodes.indices.filter {
            val n = nodes[it]
            n.visible && n.id == "monogram_test_tag" && within(it, root) && !history(it) &&
                scopeBounds.contains(n.bounds) && n.bounds.bottom <= scopeBounds.top + headerHeightPx
        }.mapNotNull { avatar ->
            val button = clickable(avatar, root) ?: return@mapNotNull null
            if (nodes[button].bounds.bottom > scopeBounds.top + headerHeightPx) return@mapNotNull null
            val titles = nodes.indices.filter {
                within(it, button) && nodes[it].visible && !nodes[it].editable && nodes[it].text.isNotBlank()
            }
            titles.singleOrNull()?.let { Header(nodes[it].text, button) }
        }.distinct()
        return headers.singleOrNull()
    }

    fun sendButton(editor: Int): SendButton? {
        val root = scope(editor) ?: return null
        val scopeBounds = nodes[root].bounds
        fun candidate(index: Int): Boolean {
            val n = nodes[index]
            return n.visible && n.enabled && within(index, root) && !history(index) &&
                scopeBounds.contains(n.bounds) && (n.bounds.top + n.bounds.bottom) / 2 >= nodes[editor].bounds.top
        }
        val modern = nodes.indices.filter { nodes[it].id == "Compose:Draft:Send" && candidate(it) }
        val labels = setOf("send sms", "send mms", "send rcs message", "send rcs", "send message", "send chat message")
        val selected = if (modern.isNotEmpty()) modern.singleOrNull() else nodes.indices.singleOrNull {
            candidate(it) && nodes[it].description.lowercase(Locale.ROOT) in labels
        }
        selected ?: return null
        val button = clickable(selected, root) ?: return null
        val label = nodes.indices.filter { within(it, selected) }.joinToString(" ") { nodes[it].description }.lowercase(Locale.ROOT)
        val transport = when {
            "sms" in label -> "sms"
            "mms" in label -> "mms"
            "rcs" in label || "chat" in label -> "rcs"
            else -> "messages_auto"
        }
        return SendButton(button, transport)
    }
}
