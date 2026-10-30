package dev.androidmcp.core

import dev.androidmcp.core.MessagesUi.Bounds
import dev.androidmcp.core.MessagesUi.Node
import org.junit.Assert.*
import org.junit.Test

/** Fictional text in the relevant tree structure observed on the Android 17 phone. */
class MessagesUiTest {
    private val scopeId = "com.google.android.apps.messaging:id/conversation_root_container"
    private val screen = Bounds(0, 0, 1200, 2608)
    private fun modern() = mutableListOf(
        Node(id = scopeId, bounds = screen), // 0
        Node(parent = 0, id = "message_list", bounds = screen), // 1
        // History precedes the toolbar in tree order and can be drawn behind it.
        Node(parent = 1, id = "message_text", text = "+12025550123", bounds = Bounds(24, 144, 600, 220)), // 2
        Node(parent = 0, id = "top_app_bar", bounds = Bounds(0, 0, 1200, 336)), // 3
        Node(parent = 3, id = "top_app_bar_title_row", clickable = true, bounds = Bounds(180, 168, 500, 312)), // 4
        Node(parent = 4, text = "Fixture Friend", bounds = Bounds(336, 204, 500, 276)), // 5
        Node(parent = 0, editable = true, bounds = Bounds(168, 2368, 696, 2536)), // 6
        Node(parent = 0, id = "Compose:Draft:Send", clickable = true, bounds = Bounds(1008, 2368, 1176, 2536)), // 7
        Node(parent = 7, description = "Send RCS message", bounds = Bounds(1008, 2368, 1176, 2536)), // 8
    )
    @Test fun modernHeaderIgnoresMessageTextEvenWhenItLooksLikeRecipientNumber() {
        val header = MessagesUi(modern(), 336).header(6)!!
        assertEquals("Fixture Friend", header.title); assertEquals(4, header.button)
    }
    @Test fun unknownOrAmbiguousToolbarNeverFallsBackToHistory() {
        val nodes = modern()
        nodes[5] = nodes[5].copy(text = "")
        assertNull(MessagesUi(nodes, 336).header(6))
        nodes.add(Node(parent = 3, id = "top_app_bar_title_row", clickable = true))
        assertNull(MessagesUi(nodes, 336).header(6))
    }
    @Test fun legacyAvatarSelectsTitleControlInsideConversationPaneOnly() {
        val nodes = listOf(
            Node(bounds = Bounds(0, 0, 2208, 1840)),
            Node(parent = 0, id = "monogram_test_tag", bounds = Bounds(42, 111, 147, 206)), // other pane
            Node(parent = 0, id = scopeId, bounds = Bounds(1107, 0, 2208, 1840)),
            Node(parent = 2, id = "message_list"),
            Node(parent = 3, clickable = true, bounds = Bounds(1129, 95, 1808, 221)),
            Node(parent = 4, id = "monogram_test_tag", bounds = Bounds(1140, 111, 1235, 206)),
            Node(parent = 4, text = "Wrong person from history"),
            Node(parent = 2, clickable = true, bounds = Bounds(1129, 95, 1808, 221)),
            Node(parent = 7, id = "monogram_test_tag", bounds = Bounds(1140, 111, 1235, 206)),
            Node(parent = 7, text = "Fixture Friend"),
            Node(parent = 2, editable = true),
        )
        val header = MessagesUi(nodes, 294).header(10)!!
        assertEquals("Fixture Friend", header.title); assertEquals(7, header.button)
    }
    @Test fun modernSendUsesComposeControlAndKeepsTransportLabel() {
        val button = MessagesUi(modern(), 336).sendButton(6)!!
        assertEquals(7, button.button); assertEquals("rcs", button.transport)
    }
    @Test fun legacySendUsesClickableParentOfDescriptionAndExcludesHistory() {
        val nodes = modern()
        nodes[7] = nodes[7].copy(id = "")
        nodes[8] = nodes[8].copy(description = "Send SMS")
        nodes.add(Node(parent = 1, description = "Send SMS", clickable = true, bounds = nodes[7].bounds))
        val button = MessagesUi(nodes, 336).sendButton(6)!!
        assertEquals(7, button.button); assertEquals("sms", button.transport)
    }
    @Test fun disabledComposeSendDoesNotSelectMatchingTextInHistory() {
        val nodes = modern()
        nodes[7] = nodes[7].copy(enabled = false)
        nodes[8] = nodes[8].copy(enabled = false)
        nodes.add(Node(parent = 1, description = "Send RCS message", clickable = true, bounds = nodes[7].bounds))
        assertNull(MessagesUi(nodes, 336).sendButton(6))
    }
}
