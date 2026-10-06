package ai.unicto.unibot.privacy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Wave 5 (v1.0) — privacy core. Clipboard auto-clear: when the user has
 * turned on "Auto-clear clipboard" in Settings → Privacy, copied text is
 * wiped from the system clipboard 60 seconds after copying.
 *
 * Honesty scope: this only clears copies made THROUGH [copyWithAutoClear].
 * Copies made by other apps, by Android's own clipboard history, or by
 * keyboard apps are outside this app's control — the Settings row says
 * "copies from unibot", not "the whole clipboard".
 */
object ClipboardGuard {

    private const val TAG = "ClipboardGuard"

    /** Delay before the clipboard is cleared, when auto-clear is enabled. */
    private const val AUTO_CLEAR_DELAY_MS = 60_000L

    /**
     * Copies [text] under [label], then — when the auto-clear toggle is on —
     * schedules a wipe after [AUTO_CLEAR_DELAY_MS]. The wipe replaces the
     * current clip with an empty one only if the clipboard still holds OUR
     * clip; if the user copied something else in the meantime we leave it
     * alone.
     *
     * Privacy item 64: URLs inside the copied text are swept for
     * credential-like parameters ([UrlTokenAudit]) and redacted BEFORE the
     * copy lands on the clipboard. Values are never logged — only the count
     * of redacted parameters.
     */
    fun copyWithAutoClear(context: Context, label: String, text: String) {
        val safeText = UrlTokenAudit.redactUrlsInText(text)
        if (safeText != text) {
            Log.w(TAG, "redacted credential-like URL parameter(s) before copying ($label) — values never logged")
        }
        val appContext = context.applicationContext
        val clipboard =
            appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return
        val clip = ClipData.newPlainText(label, safeText)
        clipboard.setPrimaryClip(clip)

        if (!PrivacyPrefs.clipboardAutoClear) return

        Handler(Looper.getMainLooper()).postDelayed({
            try {
                // Only clear if our clip is still current — don't nuke a
                // newer copy the user made elsewhere.
                if (clipboard.primaryClip === clip ||
                    clipboard.primaryClipDescription?.label == label
                ) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                    Log.i(TAG, "auto-cleared clipboard ($label)")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "auto-clear failed: ${t.message}")
            }
        }, AUTO_CLEAR_DELAY_MS)
    }
}
