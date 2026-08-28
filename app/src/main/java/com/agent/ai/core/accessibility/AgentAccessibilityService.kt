package com.agent.ai.core.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.agent.ai.core.AgentResult
import com.agent.ai.core.ErrorCode
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AccessibilityService for UI tree inspection and action injection.
 * User must enable manually in Settings → Accessibility.
 */
class AgentAccessibilityService : AccessibilityService() {

    private val commandQueue = ConcurrentLinkedQueue<Pair<UiCommand, CompletableDeferred<AgentResult<String>>>>()
    private val processing = AtomicBoolean(false)

    override fun onServiceConnected() {
        super.onServiceConnected()
        AgentAccessibilityBridge.register(this)
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onDestroy() {
        AgentAccessibilityBridge.unregister(this)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Event-driven processing not required — commands pull root on demand.
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    fun enqueue(command: UiCommand, deferred: CompletableDeferred<AgentResult<String>>): Boolean {
        commandQueue.add(command to deferred)
        if (processing.compareAndSet(false, true)) {
            mainExecutor.execute { drainQueue() }
        }
        return true
    }

    private fun drainQueue() {
        try {
            while (true) {
                val (command, deferred) = commandQueue.poll() ?: break
                deferred.complete(executeCommand(command))
            }
        } finally {
            processing.set(false)
            if (commandQueue.isNotEmpty() && processing.compareAndSet(false, true)) {
                mainExecutor.execute { drainQueue() }
            }
        }
    }

    private fun executeCommand(command: UiCommand): AgentResult<String> {
        val root = rootInActiveWindow
            ?: return AgentResult.Error(ErrorCode.TOOL_EXECUTION_FAILED, "No active window — open an app first")

        return when (command) {
            is UiCommand.Click -> performClick(root, command.target)
            is UiCommand.InputText -> performInput(
                root = root,
                target = command.target,
                text = command.text,
                useClipboard = command.useClipboard,
                submit = command.submit
            )
            is UiCommand.Scroll -> performScroll(root, command.forward)
            UiCommand.InspectScreen -> inspect(root)
        }
    }

    private fun performClick(root: AccessibilityNodeInfo, target: String): AgentResult<String> {
        val nodes = NodeTreeCrawler.crawl(root)
        val match = NodeTreeCrawler.findBest(nodes, target)
            ?: return AgentResult.Error(ErrorCode.TOOL_TARGET_NOT_FOUND, "No UI element matched '$target'")

        if (match.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return AgentResult.Success("Clicked '${displayLabel(match)}'")
        }

        // Coordinate tap fallback for nodes that aren't marked clickable
        val cx = match.bounds.centerX().toFloat()
        val cy = match.bounds.centerY().toFloat()
        return if (dispatchTap(cx, cy)) {
            AgentResult.Success("Tapped '${displayLabel(match)}' at (${cx.toInt()}, ${cy.toInt()})")
        } else {
            AgentResult.Error(ErrorCode.TOOL_EXECUTION_FAILED, "Click failed on '${displayLabel(match)}'")
        }
    }

    private fun performInput(
        root: AccessibilityNodeInfo,
        target: String,
        text: String,
        useClipboard: Boolean,
        submit: Boolean
    ): AgentResult<String> {
        val inputText = when {
            useClipboard -> readClipboardText()
                ?: return AgentResult.Error(ErrorCode.TOOL_INVALID_PARAMS, "Clipboard is empty")
            else -> text
        }
        if (inputText.isEmpty()) {
            return AgentResult.Error(
                ErrorCode.TOOL_INVALID_PARAMS,
                "No text to enter — set payload_text or use_clipboard=true"
            )
        }

        val nodes = NodeTreeCrawler.crawl(root)
        val match = resolveInputTarget(nodes, target.trim())
            ?: return AgentResult.Error(
                ErrorCode.TOOL_TARGET_NOT_FOUND,
                if (target.isBlank()) {
                    "No text field found — tap the message box first, or pass target_identifier (e.g. \"Message\")"
                } else {
                    "No input field matched '$target'"
                }
            )

        val node = match.node
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

        val entered = enterTextOnNode(node, inputText)
        if (!entered) {
            return AgentResult.Error(
                ErrorCode.TOOL_EXECUTION_FAILED,
                "Could not enter text into '${displayLabel(match)}' — try tapping the field first"
            )
        }

        val parts = mutableListOf("Entered text into '${displayLabel(match)}'")
        if (submit) {
            when (pressSubmit(root, nodes, node)) {
                true -> parts += "and pressed Send/Enter"
                false -> parts += "(Send button not found — tap Send manually)"
            }
        }
        return AgentResult.Success(parts.joinToString(" "))
    }

    private fun resolveInputTarget(
        nodes: List<NodeTreeCrawler.UiNode>,
        target: String
    ): NodeTreeCrawler.UiNode? {
        if (target.isNotEmpty()) return NodeTreeCrawler.findBest(nodes, target)
        return NodeTreeCrawler.findFocusedEditable(nodes)
            ?: NodeTreeCrawler.findBestEditable(nodes)
    }

    private fun enterTextOnNode(node: AccessibilityNodeInfo, text: CharSequence): Boolean {
        val setArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)) return true

        // Paste fallback — works better in WhatsApp/Telegram and supports emoji/symbols.
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val previous = clipboard.primaryClip
        clipboard.setPrimaryClip(ClipData.newPlainText("agent_input", text))
        val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        if (previous != null && previous.itemCount > 0) {
            clipboard.setPrimaryClip(previous)
        }
        return pasted
    }

    private fun readClipboardText(): String? {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(this)?.toString()?.takeIf { it.isNotBlank() }
    }

    private fun pressSubmit(
        root: AccessibilityNodeInfo,
        nodes: List<NodeTreeCrawler.UiNode>,
        @Suppress("UNUSED_PARAMETER") inputNode: AccessibilityNodeInfo
    ): Boolean {
        NodeTreeCrawler.findSubmitButton(nodes)?.let { send ->
            if (send.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            val cx = send.bounds.centerX().toFloat()
            val cy = send.bounds.centerY().toFloat()
            if (dispatchTap(cx, cy)) return true
        }

        for (label in listOf("Send", "Enter", "Done", "Go", "Post", "Reply")) {
            val clickResult = performClick(root, label)
            if (clickResult is AgentResult.Success) return true
        }
        return false
    }

    private fun performScroll(root: AccessibilityNodeInfo, forward: Boolean): AgentResult<String> {
        val nodes = NodeTreeCrawler.crawl(root)
        val scrollable = nodes.firstOrNull { it.isScrollable }
            ?: return AgentResult.Error(ErrorCode.TOOL_TARGET_NOT_FOUND, "No scrollable view on screen")

        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD

        return if (scrollable.node.performAction(action)) {
            AgentResult.Success(if (forward) "Scrolled forward" else "Scrolled back")
        } else {
            // Swipe gesture fallback
            val b = scrollable.bounds
            val path = Path().apply {
                if (forward) {
                    moveTo(b.centerX().toFloat(), b.bottom * 0.75f)
                    lineTo(b.centerX().toFloat(), b.top * 0.25f)
                } else {
                    moveTo(b.centerX().toFloat(), b.top * 0.25f)
                    lineTo(b.centerX().toFloat(), b.bottom * 0.75f)
                }
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
                .build()
            val ok = dispatchGesture(gesture, null, null)
            if (ok) AgentResult.Success("Scrolled via gesture")
            else AgentResult.Error(ErrorCode.TOOL_EXECUTION_FAILED, "Scroll gesture failed")
        }
    }

    private fun inspect(root: AccessibilityNodeInfo): AgentResult<String> {
        val nodes = NodeTreeCrawler.crawl(root)
        val summary = nodes.take(25).joinToString("; ") { n ->
            buildString {
                if (n.text.isNotEmpty()) append("\"${n.text}\"")
                else if (n.contentDescription.isNotEmpty()) append("[${n.contentDescription}]")
                if (n.viewId.isNotEmpty()) append(" id=${n.viewId.substringAfterLast('/')}")
                if (n.isClickable) append(" (click)")
                if (n.isEditable) append(" (edit)")
            }
        }
        return AgentResult.Success("Screen has ${nodes.size} nodes. Top: $summary")
    }

    private fun dispatchTap(x: Float, y: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun displayLabel(node: NodeTreeCrawler.UiNode): String =
        node.text.ifEmpty { node.contentDescription.ifEmpty { node.viewId.substringAfterLast('/') } }

    companion object {
        private const val TAG = "AgentAccessibility"
    }
}
