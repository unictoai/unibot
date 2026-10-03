package ai.unicto.unibot.connectors.nextcloud

import android.content.Context
import android.content.SharedPreferences
import android.util.Xml
import ai.unicto.unibot.logging.AppLogger
import ai.unicto.unibot.util.EncryptedPrefsFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Nextcloud connector (WebDAV, Basic auth with username + app password).
 *
 * Server URL, username and app password are stored encrypted on-device;
 * nothing is ever sent anywhere but the user's own server. Supports
 * listing folders, downloading files and uploading files.
 */
object NextcloudConnector {

    private const val TAG = "NextcloudConnector"
    private const val PREFS_FILE = "nextcloud_connector"

    private const val KEY_URL = "server_url"
    private const val KEY_USER = "username"
    private const val KEY_PASS = "app_password"

    data class RemoteFile(
        val name: String,
        val path: String,
        val isDir: Boolean,
        val size: Long,
        val modified: String,
    )

    sealed class ApiResult<out T> {
        data class Ok<T>(val value: T) : ApiResult<T>()
        data class NotConnected(val hint: String = "Nextcloud is not connected. Add your server URL and app password in Settings → Connectors.") :
            ApiResult<Nothing>()
        data class Error(val message: String) : ApiResult<Nothing>()
    }

    @Volatile
    private var prefsRef: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences =
        prefsRef ?: synchronized(this) {
            prefsRef ?: EncryptedPrefsFactory.safeCreate(context.applicationContext, PREFS_FILE)
                .also { prefsRef = it }
        }

    fun isConnected(context: Context): Boolean =
        prefs(context).getString(KEY_URL, null)?.isNotEmpty() == true &&
            prefs(context).getString(KEY_PASS, null)?.isNotEmpty() == true

    fun serverUrl(context: Context): String? =
        prefs(context).getString(KEY_URL, null)?.takeIf { it.isNotBlank() }

    fun username(context: Context): String? =
        prefs(context).getString(KEY_USER, null)?.takeIf { it.isNotBlank() }

    private fun credential(context: Context): String? =
        prefs(context).getString(KEY_PASS, null)?.takeIf { it.isNotEmpty() }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    /** Validate by PROPFIND-ing the user's WebDAV root; store on success. */
    suspend fun connect(
        context: Context,
        serverUrl: String,
        username: String,
        appPassword: String,
    ): String? = withContext(Dispatchers.IO) {
        val base = normalizeUrl(serverUrl) ?: return@withContext "That doesn't look like a valid https:// URL."
        val user = username.trim()
        if (user.isEmpty()) return@withContext "Enter your Nextcloud username."
        if (appPassword.isEmpty()) return@withContext "Enter an app password (Nextcloud → Settings → Security → Devices & sessions)."
        val err = propfind(base, user, appPassword, "", depth0 = true)
        if (err != null) return@withContext err
        prefs(context).edit().apply {
            putString(KEY_URL, base)
            putString(KEY_USER, user)
            putString(KEY_PASS, appPassword)
        }.apply()
        null
    }

    fun disconnect(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun normalizeUrl(raw: String): String? {
        var u = raw.trim().trimEnd('/')
        if (u.isEmpty()) return null
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        return if (u.matches(Regex("https?://[^\\s/]+(\\.[^\\s/]+)+(:\\d+)?(/.*)?"))) u else null
    }

    private fun davRoot(base: String, user: String): String =
        "$base/remote.php/dav/files/${URLEncoder.encode(user, "UTF-8")}"

    private fun authed(base: String, user: String, pass: String, url: String): Request.Builder =
        Request.Builder().url(url).header("Authorization", Credentials.basic(user, pass))

    /** Depth-0 PROPFIND used as a credential check; null = OK. */
    private fun propfind(
        base: String,
        user: String,
        pass: String,
        relPath: String,
        depth0: Boolean,
    ): String? {
        val url = davRoot(base, user) + "/" + relPath.trim('/').split("/")
            .filter { it.isNotEmpty() }
            .joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val body = """<?xml version="1.0"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:displayname/></d:prop></d:propfind>"""
            .toRequestBody("application/xml".toMediaType())
        return try {
            http.newCall(
                authed(base, user, pass, url)
                    .method("PROPFIND", body)
                    .header("Depth", if (depth0) "0" else "1")
                    .build(),
            ).execute().use { resp ->
                when (resp.code) {
                    207 -> null
                    401 -> "Login rejected — check the username and app password."
                    404 -> "Server reached, but the WebDAV path wasn't found. Check the server URL."
                    else -> "Server answered HTTP ${resp.code}."
                }
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "[propfind] ${t.message}")
            "Couldn't reach $base (${t.message ?: "connection failed"})."
        }
    }

    /** List a folder ("" = root). */
    suspend fun list(
        context: Context,
        relPath: String = "",
    ): ApiResult<List<RemoteFile>> = withContext(Dispatchers.IO) {
        val base = serverUrl(context) ?: return@withContext ApiResult.NotConnected()
        val user = username(context) ?: return@withContext ApiResult.NotConnected()
        val pass = credential(context) ?: return@withContext ApiResult.NotConnected()
        try {
            val root = davRoot(base, user)
            val url = root + "/" + relPath.trim('/').split("/")
                .filter { it.isNotEmpty() }
                .joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
            val body = """<?xml version="1.0"?>
<d:propfind xmlns:d="DAV:"><d:prop>
<d:displayname/><d:getcontentlength/><d:getcontenttype/>
<d:resourcetype/><d:getlastmodified/>
</d:prop></d:propfind>""".toRequestBody("application/xml".toMediaType())
            http.newCall(
                authed(base, user, pass, url)
                    .method("PROPFIND", body)
                    .header("Depth", "1")
                    .build(),
            ).execute().use { resp ->
                if (resp.code == 401) return@withContext ApiResult.Error("Login rejected — check the username and app password.")
                if (resp.code != 207) return@withContext ApiResult.Error("Server answered HTTP ${resp.code}.")
                val xml = resp.body?.string().orEmpty()
                ApiResult.Ok(parseMultistatus(xml, root, relPath.trim('/')))
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "[list] ${t.message}")
            ApiResult.Error(t.message ?: "list failed")
        }
    }

    private fun parseMultistatus(xml: String, root: String, relPath: String): List<RemoteFile> {
        val out = mutableListOf<RemoteFile>()
        try {
            val p: XmlPullParser = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(xml.reader())
            var href = ""; var name = ""; var len = 0L
            var type = ""; var modified = ""; var isDir = false
            var inResponse = false
            var tag = ""
            var event = p.eventType
            fun flush() {
                if (href.isEmpty()) return
                // href is the full DAV path; strip the root prefix.
                val rel = href.removePrefix(root.removeSuffix("/") + "/").trim('/')
                // Skip the folder itself (depth-1 includes the parent).
                if (rel.equals(relPath.trim('/'), ignoreCase = true)) return
                val display = name.ifBlank { rel.substringAfterLast('/') }
                if (display.isNotBlank()) {
                    out.add(RemoteFile(display, rel, isDir, len, modified))
                }
            }
            while (event != XmlPullParser.END_DOCUMENT) {
                val n = p.name?.lowercase().orEmpty()
                when (event) {
                    XmlPullParser.START_TAG -> {
                        when (n) {
                            "response" -> {
                                flush(); href = ""; name = ""; len = 0L
                                type = ""; modified = ""; isDir = false
                                inResponse = true
                            }
                            "collection" -> if (inResponse) isDir = true
                        }
                        tag = n
                    }
                    XmlPullParser.TEXT -> {
                        if (!inResponse) { event = p.next(); continue }
                        val t = p.text?.trim().orEmpty()
                        if (t.isNotEmpty()) when (tag) {
                            "href" -> href += t
                            "displayname" -> name += t
                            "getcontentlength" -> len = t.toLongOrNull() ?: 0L
                            "getcontenttype" -> type = t
                            "getlastmodified" -> if (modified.isBlank()) modified = t
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (n == "response") { flush(); inResponse = false }
                        tag = ""
                    }
                }
                event = p.next()
            }
            flush()
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "[parse] ${t.message}")
        }
        return out.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    /** Download a file into the app cache dir; returns the local file. */
    suspend fun download(
        context: Context,
        relPath: String,
    ): ApiResult<File> = withContext(Dispatchers.IO) {
        val base = serverUrl(context) ?: return@withContext ApiResult.NotConnected()
        val user = username(context) ?: return@withContext ApiResult.NotConnected()
        val pass = credential(context) ?: return@withContext ApiResult.NotConnected()
        val rel = relPath.trim('/').split("/").filter { it.isNotEmpty() }
        if (rel.isEmpty()) return@withContext ApiResult.Error("Pick a file to download.")
        try {
            val url = davRoot(base, user) + "/" +
                rel.joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
            http.newCall(authed(base, user, pass, url).get().build()).execute().use { resp ->
                if (resp.code == 401) return@withContext ApiResult.Error("Login rejected — check the username and app password.")
                if (!resp.isSuccessful) return@withContext ApiResult.Error("Server answered HTTP ${resp.code}.")
                val local = File(context.cacheDir, "nextcloud_${System.currentTimeMillis()}_${rel.last()}")
                resp.body?.byteStream()?.use { input ->
                    local.outputStream().use { input.copyTo(it) }
                }
                ApiResult.Ok(local)
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "[download] ${t.message}")
            ApiResult.Error(t.message ?: "download failed")
        }
    }

    /** Upload a local file into [relDir] ("" = root). */
    suspend fun upload(
        context: Context,
        local: File,
        relDir: String = "",
    ): ApiResult<Unit> = withContext(Dispatchers.IO) {
        val base = serverUrl(context) ?: return@withContext ApiResult.NotConnected()
        val user = username(context) ?: return@withContext ApiResult.NotConnected()
        val pass = credential(context) ?: return@withContext ApiResult.NotConnected()
        if (!local.exists()) return@withContext ApiResult.Error("Local file not found.")
        try {
            val dir = relDir.trim('/').split("/").filter { it.isNotEmpty() }
            val url = davRoot(base, user) + "/" +
                (dir + local.name).joinToString("/") {
                    URLEncoder.encode(it, "UTF-8").replace("+", "%20")
                }
            val mime = when (local.extension.lowercase()) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "pdf" -> "application/pdf"
                "txt", "md" -> "text/plain"
                "mp4" -> "video/mp4"
                else -> "application/octet-stream"
            }.toMediaType()
            http.newCall(
                authed(base, user, pass, url)
                    .put(local.readBytes().toRequestBody(mime))
                    .build(),
            ).execute().use { resp ->
                if (resp.code == 401) return@withContext ApiResult.Error("Login rejected — check the username and app password.")
                if (resp.code !in listOf(200, 201, 204)) {
                    return@withContext ApiResult.Error("Upload failed (HTTP ${resp.code}).")
                }
                ApiResult.Ok(Unit)
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "[upload] ${t.message}")
            ApiResult.Error(t.message ?: "upload failed")
        }
    }
}
