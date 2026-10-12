package ai.unicto.unibot.auth

import android.app.Activity
import android.os.Bundle
import android.util.Log

/**
 * Invisible activity that captures OAuth redirect URLs (http://localhost:54545/callback).
 *
 * When the OAuth provider (e.g. Anthropic) redirects to localhost, Android would normally
 * show a "Choose activity" dialog. This activity's intent filter in AndroidManifest catches
 * the redirect URL instead, extracts the code/state parameters, and forwards them to the
 * OAuthCallbackServer via a local HTTP request.
 *
 * The activity finishes immediately (Theme.NoDisplay) — the user never sees it.
 */
class OAuthRedirectActivity : Activity() {

    companion object {
        private const val TAG = "OAuthRedirect"
        // [Security] Known OAuth callback (port, path) pairs from the
        // manifest intent-filters. Explicit intents bypass those filters,
        // so validate here before forwarding to the loopback server.
        private val ALLOWED_CALLBACKS = setOf(
            54545 to "/callback", // Claude
            1455 to "/auth/callback", // OpenAI
            8085 to "/oauth2callback", // Gemini
            3000 to "/callback", // OpenRouter (+ fallbacks)
            3001 to "/callback",
            3002 to "/callback",
            56121 to "/callback", // XAI
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent?.data
        // [Security] Never log the full redirect URI — it carries the
        // single-use auth code and state, which would land in logcat, the
        // in-app log export and bug reports.
        Log.i(TAG, "OAuth redirect received: path=${uri?.path}")

        if (uri != null) {
            val code = uri.getQueryParameter("code")
            val state = uri.getQueryParameter("state")

            if (code != null) {
                val port = uri.port.takeIf { it > 0 } ?: 54545
                val path = uri.path ?: ""
                // [Security] Only forward to known OAuth callback endpoints.
                if ((port to path) !in ALLOWED_CALLBACKS) {
                    Log.w(TAG, "Ignoring redirect to unknown callback: port=$port path=$path")
                    finish()
                    return
                }
                // Forward the callback to our local server by making a local HTTP request.
                // The OAuthCallbackServer is already listening on the port.
                Thread {
                    try {
                        val localUrl = "http://127.0.0.1:$port$path?${uri.query}"
                        Log.d(TAG, "Forwarding to local server: port=$port path=${uri.path}")
                        val conn = java.net.URL(localUrl).openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 5000
                        conn.readTimeout = 5000
                        conn.requestMethod = "GET"
                        val responseCode = conn.responseCode
                        Log.i(TAG, "Local server response: $responseCode")
                        conn.disconnect()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to forward to local server", e)
                    }
                }.start()
            } else {
                Log.w(TAG, "No 'code' parameter in redirect URI")
            }
        }

        finish()
    }
}
