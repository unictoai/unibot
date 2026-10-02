package ai.unicto.unibot.connectors.whatsapp

/**
 * In-memory ring buffer of recently seen WhatsApp notifications.
 *
 * PRIVACY — READ THIS:
 * - ON-DEVICE ONLY: entries never leave this phone. Nothing is logged,
 *   persisted to disk, synced, or uploaded — ever.
 * - IN-MEMORY ONLY: the buffer is lost on process death. That is BY
 *   DESIGN, for privacy: no WhatsApp message content survives a restart.
 * - BOUNDED: only the last [MAX_SIZE] messages are kept; older ones are
 *   dropped as new ones arrive.
 *
 * Filled by [ai.unicto.unibot.service.WhatsAppListenerService] (opt-in)
 * and read by the `whatsapp_recent` agent tool.
 */
object WhatsAppInbox {

    /** How many recent notifications are kept. */
    const val MAX_SIZE = 30

    data class Entry(
        val sender: String,
        val text: String,
        val receivedAt: Long,
    )

    private val entries = ArrayDeque<Entry>()

    /** Append a notification; drops the oldest once over [MAX_SIZE]. */
    @Synchronized
    fun add(entry: Entry) {
        entries.addLast(entry)
        while (entries.size > MAX_SIZE) entries.removeFirst()
    }

    /** Snapshot of buffered messages, oldest first. */
    @Synchronized
    fun recent(): List<Entry> = entries.toList()

    /** Wipe the buffer (e.g. when the user turns the reader off). */
    @Synchronized
    fun clear() {
        entries.clear()
    }
}
