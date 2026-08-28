package com.agent.ai.core.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * BFS crawl of the active window's AccessibilityNodeInfo tree.
 * Builds a flat list of actionable/display nodes for matching and injection.
 */
object NodeTreeCrawler {

    data class UiNode(
        val text: String,
        val contentDescription: String,
        val viewId: String,
        val className: String,
        val bounds: Rect,
        val isClickable: Boolean,
        val isEditable: Boolean,
        val isScrollable: Boolean,
        val node: AccessibilityNodeInfo
    )

    fun crawl(root: AccessibilityNodeInfo?): List<UiNode> {
        if (root == null) return emptyList()
        val result = mutableListOf<UiNode>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            collectNode(node)?.let { result.add(it) }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return result
    }

    private fun collectNode(node: AccessibilityNodeInfo): UiNode? {
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val viewId = node.viewIdResourceName.orEmpty()
        val hasSignal = text.isNotEmpty() || desc.isNotEmpty() || viewId.isNotEmpty()
        val actionable = node.isClickable || node.isEditable || node.isScrollable || node.isFocusable
        if (!hasSignal && !actionable) return null

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return null

        return UiNode(
            text = text,
            contentDescription = desc,
            viewId = viewId,
            className = node.className?.toString().orEmpty(),
            bounds = bounds,
            isClickable = node.isClickable,
            isEditable = node.isEditable,
            isScrollable = node.isScrollable,
            node = node
        )
    }

    /**
     * Match priority: exact viewId → exact text/desc → fuzzy contains → resource id substring.
     */
    fun findBest(nodes: List<UiNode>, target: String): UiNode? {
        val q = target.trim()
        if (q.isEmpty()) return null
        val qLower = q.lowercase()

        nodes.firstOrNull { it.viewId.equals(q, ignoreCase = true) || it.viewId.endsWith("/$q", ignoreCase = true) }
            ?.let { return it }

        nodes.firstOrNull {
            it.text.equals(q, ignoreCase = true) || it.contentDescription.equals(q, ignoreCase = true)
        }?.let { return it }

        nodes.firstOrNull {
            it.text.contains(q, ignoreCase = true) || it.contentDescription.contains(q, ignoreCase = true)
        }?.let { return it }

        return nodes.firstOrNull {
            it.viewId.contains(qLower, ignoreCase = true) && (it.isClickable || it.isEditable)
        }
    }

    /** Currently focused text field, if any. */
    fun findFocusedEditable(nodes: List<UiNode>): UiNode? =
        nodes.firstOrNull { it.node.isFocused && isInputCandidate(it) }

    /**
     * Best editable field when target/focus unknown — prefers message-style fields at bottom of screen.
     */
    fun findBestEditable(nodes: List<UiNode>): UiNode? =
        nodes.filter { isInputCandidate(it) }
            .maxWithOrNull(
                compareBy<UiNode> { if (it.node.isFocused) 1 else 0 }
                    .thenBy { if (it.isEditable) 1 else 0 }
                    .thenBy { inputFieldScore(it) }
                    .thenBy { it.bounds.bottom }
            )

    /** Send / Enter / Done button commonly shown next to a message field. */
    fun findSubmitButton(nodes: List<UiNode>): UiNode? {
        val keywords = listOf("send", "post", "submit", "done", "go", "enter", "reply")
        return nodes.filter { it.isClickable }
            .sortedByDescending { it.bounds.bottom }
            .firstOrNull { node ->
                val hay = "${node.text} ${node.contentDescription} ${node.viewId}".lowercase()
                keywords.any { hay.contains(it) }
            }
    }

    private fun isInputCandidate(node: UiNode): Boolean {
        if (node.isEditable) return true
        val cls = node.className.lowercase()
        if (cls.contains("edittext") || cls.contains("autocomplete") || cls.contains("input")) return true
        return node.node.isFocusable && node.node.isEditable
    }

    /** Higher = more likely a message compose box. */
    private fun inputFieldScore(node: UiNode): Int {
        var score = 0
        val hay = "${node.text} ${node.contentDescription} ${node.viewId} ${node.className}".lowercase()
        if (hay.contains("message")) score += 3
        if (hay.contains("compose")) score += 2
        if (hay.contains("chat")) score += 2
        if (hay.contains("input")) score += 1
        if (hay.contains("search")) score -= 2
        if (node.isEditable) score += 2
        return score
    }
}
