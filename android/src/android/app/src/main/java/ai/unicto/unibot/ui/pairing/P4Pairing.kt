package ai.unicto.unibot.ui.pairing

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import ai.unicto.unibot.cloud.UnibotCloud
import ai.unicto.unibot.hub.Hub
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * P4 phone-to-desktop pairing (v0.2.0) — the PHONE side.
 *
 * The phone shows a QR code encoding a pairing payload; a desktop (or web)
 * client scans it, connects to the relay and presents the token. The phone
 * lists paired remote sessions here and can revoke them.
 *
 * Protocol (v1, JSON in the QR — see `p4-pairing-protocol.md`):
 * ```
 * {"v":1,"app":"unibot","device":"<name>","deviceId":"<id>",
 *  "relay":"<relay base url>","token":"<base64url 32B>","ts":<epoch sec>}
 * ```
 * - The desktop opens a relay session to `relay`, identifies with `deviceId`
 *   and proves possession with `token` (bearer, over TLS).
 * - The phone records the desktop under "remote sessions" on first
 *   authenticated contact; revoking deletes the token binding (the desktop
 *   must re-scan to pair again — the token itself is rotated on demand via
 *   "Regenerate").
 * - The token is a local secret: it never leaves the device except inside
 *   this QR payload (shown on the phone's own screen) and the relay TLS
 *   session. Regenerate it if the QR was photographed by someone else.
 *
 * The desktop client does not exist yet — this ships the phone side and the
 * documented protocol so the client can be built against it.
 */
const val ROUTE_PAIRING = "pairing"

object P4PairingStore {
    private const val PREFS = "p4_pairing"
    private const val KEY_TOKEN = "pairing_token"
    private const val KEY_REMOTES = "remote_sessions"

    data class RemoteSession(
        val id: String,
        val name: String,
        val pairedAt: Long,
        val lastSeen: Long,
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The pairing token, generated once and persisted. Never logged. */
    fun token(context: Context): String {
        val p = prefs(context)
        p.getString(KEY_TOKEN, null)?.let { return it }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        p.edit().putString(KEY_TOKEN, token).apply()
        return token
    }

    /** Rotates the token; previously paired desktops must scan again. */
    fun regenerateToken(context: Context): String {
        prefs(context).edit().remove(KEY_TOKEN).apply()
        return token(context)
    }

    fun remoteSessions(context: Context): List<RemoteSession> {
        val raw = prefs(context).getString(KEY_REMOTES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                RemoteSession(
                    id = o.optString("id"),
                    name = o.optString("name", "Desktop"),
                    pairedAt = o.optLong("pairedAt", 0L),
                    lastSeen = o.optLong("lastSeen", 0L),
                ).takeIf { it.id.isNotBlank() }
            }
        }.getOrDefault(emptyList())
    }

    fun noteRemoteSession(context: Context, id: String, name: String) {
        if (id.isBlank()) return
        val now = System.currentTimeMillis()
        val updated = remoteSessions(context)
            .filterNot { it.id == id } + RemoteSession(id, name.ifBlank { "Desktop" }, pairedAt = now, lastSeen = now)
        saveRemotes(context, updated)
    }

    fun revokeRemoteSession(context: Context, id: String) {
        saveRemotes(context, remoteSessions(context).filterNot { it.id == id })
    }

    private fun saveRemotes(context: Context, sessions: List<RemoteSession>) {
        val arr = JSONArray()
        sessions.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id)
                put("name", it.name)
                put("pairedAt", it.pairedAt)
                put("lastSeen", it.lastSeen)
            })
        }
        prefs(context).edit().putString(KEY_REMOTES, arr.toString()).apply()
    }
}

/** Builds the v1 pairing payload encoded in the QR. */
fun p4PairingPayload(context: Context): String {
    val o = JSONObject()
    o.put("v", 1)
    o.put("app", "unibot")
    o.put("device", Hub.name(context))
    o.put("deviceId", Hub.deviceId(context))
    o.put("relay", UnibotCloud.baseUrl(context).trimEnd('/'))
    o.put("token", P4PairingStore.token(context))
    o.put("ts", System.currentTimeMillis() / 1000L)
    return o.toString()
}

/** Minimal QR renderer over zxing-core (no extra Android deps). */
object P4Qr {
    fun render(payload: String, sizePx: Int = 640): ImageBitmap? {
        return runCatching {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.MARGIN to 2,
            )
            val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            for (y in 0 until sizePx) {
                for (x in 0 until sizePx) {
                    bmp.setPixel(x, y, if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
                }
            }
            bmp.asImageBitmap()
        }.getOrNull()
    }
}
