package ai.unicto.unibot.cloud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import ai.unicto.unibot.agent.SoulStore
import ai.unicto.unibot.logging.AppLogger
import ai.unicto.unibot.avatar.AvatarStore
import ai.unicto.unibot.ui.avatar.AgentMood
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * The agent's name and look, the same on every device of the account.
 *
 * The relay keeps one profile per account (`/v1/me/profile`: the name, which face, and a drawn
 * face's five stills as WebP) and says `{"type": "profile", "rev"}` on the hub when it changes.
 * Here: **pull** on sign-in, on app start and on that frame, worn when the relay's `rev` is
 * newer than the one last seen on this phone; **push** a moment after the name (SOUL.md) or
 * the face ([AvatarStore]) changes here. The pictures ride along only when the face itself
 * changed. Never a key, never a message — the runtime does the same in `unibot/hub/profile.py`.
 *
 * The phone keeps the face it drew — the clips, the prompt — when the account's pictures are
 * the ones it already wears: the relay names them with `face_id` (the hash of the idle still),
 * older relays are checked against the downloaded still. So a rename on the desktop changes
 * the name here and nothing else.
 *
 * An emoji look chosen on the web has no picture here, so the phone wears the dragon for it.
 */
object ProfileSync {
    private const val TAG = "ProfileSync"
    private const val PREFS = "unibot_profile_sync"
    private const val KEY_REV = "rev"
    private const val KEY_PUSHED_FACE = "pushed_face"
    private const val KEY_PUSHED_NAME = "pushed_name"
    /** The hash of the idle still as the relay holds it (what it reports as `face_id`). */
    private const val KEY_FACE_HASH = "face_hash"
    private const val PUSH_DELAY_MS = 2_500L
    private const val DRAGON = "dragon"

    private val moods = mapOf(
        "idle" to AgentMood.IDLE,
        "working" to AgentMood.WORKING,
        "waiting" to AgentMood.WAITING,
        "happy" to AgentMood.HAPPY,
        "error" to AgentMood.ERROR,
    )

    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "nm-profile-sync").apply { isDaemon = true } }
    private var app: Context? = null
    private var pending: ScheduledFuture<*>? = null
    /** Set while a pulled profile is being worn, so the change does not push back. */
    @Volatile private var applying = false

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** App start: remember the context for the stores' callbacks and fetch what the account wears. */
    fun init(context: Context) {
        app = context.applicationContext
        pullSoon(context)
    }

    /** Signed out: the next account starts from its own profile. */
    fun forget(context: Context) {
        prefs(context).edit().clear().apply()
    }

    // -- the frame, the pull --------------------------------------------------------------

    fun onFrame(context: Context, frame: JSONObject) {
        val rev = frame.optInt("rev", 0)
        if (frame.optString("device") == ai.unicto.unibot.hub.Hub.deviceId(context)) {
            // our own write coming back
            if (rev > prefs(context).getInt(KEY_REV, 0)) prefs(context).edit().putInt(KEY_REV, rev).apply()
            return
        }
        pullSoon(context)
    }

    fun pullSoon(context: Context) {
        val ctx = context.applicationContext
        if (!UnibotCloud.isSignedIn(ctx)) return
        worker.execute {
            try {
                pull(ctx)
            } catch (e: Exception) {
                AppLogger.info(TAG, "pull: ${e.message}")
            }
        }
    }

    /** Fetch the relay's profile and wear it when it is newer. */
    private fun pull(context: Context) {
        val light = UnibotCloud.profile(context, withFace = false)
        val rev = light.optInt("rev", 0)
        val known = prefs(context).getInt(KEY_REV, 0)
        if (rev == 0) {
            // the relay has nothing for this account yet: this phone's look seeds it, unless
            // it is the plain default (nothing worth telling the other devices)
            val name = SoulStore.load(context)?.metadata?.name.orEmpty()
            if (AvatarStore.current.value != null || (name.isNotBlank() && name != "unibot")) push(context)
            return
        }
        if (rev <= known) return
        val name = light.optString("name").trim()
        val avatar = light.optString("avatar")
        applying = true
        try {
            if (name.isNotBlank()) {
                val cur = SoulStore.load(context)
                if (cur != null && cur.metadata.name != name) {
                    SoulStore.save(context, cur.copy(metadata = cur.metadata.copy(name = name)))
                }
            }
            val knownHash = prefs(context).getString(KEY_FACE_HASH, "").orEmpty()
            when {
                avatar == "face" && light.optBoolean("has_face") -> {
                    val remote = light.optString("face_id")
                    if (AvatarStore.current.value == null || remote.isEmpty() || remote != knownHash) {
                        val full = UnibotCloud.profile(context, withFace = true)
                        wearFace(context, full, knownHash)
                    }
                    // else: the pictures this phone already wears — only the name changed
                }
                // the dragon, or an emoji the phone cannot draw: the built-in face
                AvatarStore.current.value != null -> AvatarStore.reset()
            }
        } finally {
            applying = false
        }
        prefs(context).edit()
            .putInt(KEY_REV, rev)
            .putString(KEY_PUSHED_FACE, faceStamp())
            .putString(KEY_PUSHED_NAME, name)
            .apply()
        AppLogger.info(TAG, "wearing rev $rev from the account ($avatar)")
    }

    private fun wearFace(context: Context, full: JSONObject, knownHash: String) {
        val face = full.optJSONObject("face") ?: return
        val idleBytes = bytes(face.optString("idle")) ?: return
        val hash = sha1(idleBytes)
        if (AvatarStore.current.value != null && hash == knownHash) return
        val idle = BitmapFactory.decodeByteArray(idleBytes, 0, idleBytes.size) ?: return
        AvatarStore.adopt(idle, full.optString("description"), full.optString("style"), "account")
        for ((key, mood) in moods) {
            if (mood == AgentMood.IDLE) continue
            val pic = decode(face.optString(key)) ?: continue
            AvatarStore.putMood(mood, pic)
        }
        prefs(context).edit().putString(KEY_FACE_HASH, hash).apply()
    }

    private fun bytes(b64: String): ByteArray? {
        if (b64.isBlank()) return null
        return runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
    }

    private fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }.take(12)

    private fun decode(b64: String): Bitmap? {
        val bytes = bytes(b64) ?: return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    // -- the push ---------------------------------------------------------------------------

    /** The name or the face changed on this phone (SOUL.md saved, a face adopted or reset). */
    fun changed() {
        val ctx = app ?: return
        if (applying || !UnibotCloud.isSignedIn(ctx)) return
        pending?.cancel(false)
        pending = worker.schedule({
            try {
                push(ctx)
            } catch (e: Exception) {
                AppLogger.warning(TAG, "push: ${e.message}")
            }
        }, PUSH_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    /** What identifies the face worn here, so a rename does not resend the pictures. */
    private fun faceStamp(): String {
        val cur = AvatarStore.current.value ?: return ""
        return "${cur.createdAt}:${cur.moods.keys.sortedBy { it.ordinal }.joinToString(",") { it.name }}"
    }

    private fun push(context: Context) {
        val name = SoulStore.load(context)?.metadata?.name?.takeIf { it.isNotBlank() } ?: "unibot"
        val p = prefs(context)
        val stamp = faceStamp()
        val sameFace = stamp == p.getString(KEY_PUSHED_FACE, null)
        if (sameFace && name == p.getString(KEY_PUSHED_NAME, null)) return
        val body = JSONObject()
            .put("device", ai.unicto.unibot.hub.Hub.deviceId(context))
            .put("name", name.take(60))
        val cur = AvatarStore.current.value
        var sentHash: String? = null
        if (cur == null) {
            body.put("avatar", DRAGON)
        } else {
            body.put("avatar", "face")
                .put("style", cur.style.take(20))
                .put("description", cur.prompt.take(200))
            if (!sameFace) {
                val face = JSONObject()
                AvatarStore.decodeBitmap(AvatarStore.baseFile())?.let {
                    val idle = webp(it)
                    face.put("idle", Base64.encodeToString(idle, Base64.NO_WRAP))
                    sentHash = sha1(idle)
                }
                for ((key, mood) in moods) {
                    if (mood == AgentMood.IDLE) continue
                    AvatarStore.decodeBitmap(AvatarStore.moodFile(mood))?.let {
                        face.put(key, Base64.encodeToString(webp(it), Base64.NO_WRAP))
                    }
                }
                if (face.has("idle")) {
                    body.put("face", face)
                } else {
                    // a face with no picture on disk cannot be shared: the others keep theirs
                    body.put("avatar", DRAGON)
                    body.remove("style")
                    body.remove("description")
                }
            }
        }
        val out = UnibotCloud.putProfile(context, body)
        val editor = p.edit()
            .putInt(KEY_REV, out.optInt("rev", p.getInt(KEY_REV, 0)))
            .putString(KEY_PUSHED_FACE, if (body.optString("avatar") == "face") stamp else "")
            .putString(KEY_PUSHED_NAME, name)
        if (body.optString("avatar") != "face") editor.putString(KEY_FACE_HASH, "")
        else if (sentHash != null) editor.putString(KEY_FACE_HASH, sentHash)
        editor.apply()
        AppLogger.info(TAG, "shared with the account as rev ${out.optInt("rev")}")
    }

    /** A still as WebP bytes, small enough for the relay (512 px, lossy). */
    private fun webp(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        @Suppress("DEPRECATION")
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        bitmap.compress(format, 82, out)
        return out.toByteArray()
    }
}
