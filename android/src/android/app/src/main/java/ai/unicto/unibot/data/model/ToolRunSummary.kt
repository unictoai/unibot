package ai.unicto.unibot.data.model

/**
 * v1.4.0 item 80 — data behind tool-call folding.
 *
 * An agent run's tool calls collapse to a one-line summary
 * ("Ran 3 tools · 9s") with an expand affordance, instead of a wall of
 * per-tool rows. This model is UI-agnostic: the chat renderer maps its
 * own tool-block type onto [ToolCallSummary] and hands the list to
 * `ui.components.ToolRunFoldRow`.
 */
data class ToolCallSummary(
    /** Tool name as shown, e.g. "file_read". */
    val name: String,
    /** False when the call errored or was cancelled. */
    val succeeded: Boolean,
    /** Wall-clock duration of the call, when known. */
    val durationMs: Long? = null,
)

/**
 * Folded view of one agent turn's tool calls.
 *
 * [label] renders like "Ran 3 tools · 9s" (failures surface as
 * "Ran 3 tools · 1 failed · 9s"). The duration segment is omitted when
 * no call reported a duration.
 */
data class ToolRunFoldSummary(val calls: List<ToolCallSummary>) {
    val toolCount: Int get() = calls.size
    val failedCount: Int get() = calls.count { !it.succeeded }

    val totalDurationMs: Long?
        get() {
            if (calls.isEmpty()) return null
            var total = 0L
            for (c in calls) total += c.durationMs ?: return null
            return total
        }

    val label: String
        get() = buildString {
            append("Ran $toolCount tool")
            if (toolCount != 1) append('s')
            if (failedCount > 0) {
                append(" · $failedCount failed")
            }
            totalDurationMs?.let { append(" · ${formatDuration(it)}") }
        }

    companion object {
        /**
         * 9000 → "9s", 1500 → "1.5s", 400 → "0.4s". One decimal max —
         * the folded line is a glance, not a stopwatch.
         */
        fun formatDuration(durationMs: Long): String {
            if (durationMs < 0) return "0s"
            val seconds = durationMs / 1000.0
            val oneDecimal = (seconds * 10).toLong() / 10.0
            return if (oneDecimal == oneDecimal.toLong().toDouble()) {
                "${oneDecimal.toLong()}s"
            } else {
                "${oneDecimal}s"
            }
        }
    }
}
