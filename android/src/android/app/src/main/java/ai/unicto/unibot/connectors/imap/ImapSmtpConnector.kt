package ai.unicto.unibot.connectors.imap

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import ai.unicto.unibot.logging.AppLogger
import ai.unicto.unibot.util.EncryptedPrefsFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.SocketTimeoutException
import java.nio.charset.Charset
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Generic email connector: IMAP (read) + SMTP (send) over implicit TLS.
 *
 * No mail library is in the app's dependency set, so this implements the
 * minimal protocol surface directly over SSLSocket:
 *  - IMAP: LOGIN, LIST, SELECT, UID SEARCH, UID FETCH (headers + snippet)
 *  - SMTP: EHLO, AUTH LOGIN, MAIL/RCPT/DATA
 *
 * All traffic is TLS from the first byte (IMAP 993 / SMTP 465); the
 * account password is stored in EncryptedSharedPreferences and is never
 * written to logs. Provider presets cover the common hosts; anything else
 * can be entered manually.
 */
object ImapSmtpConnector {

    private const val TAG = "ImapSmtpConnector"
    private const val PREFS_FILE = "imap_smtp_connector"

    private const val KEY_IMAP_HOST = "imap_host"
    private const val KEY_IMAP_PORT = "imap_port"
    private const val KEY_SMTP_HOST = "smtp_host"
    private const val KEY_SMTP_PORT = "smtp_port"
    private const val KEY_EMAIL = "email"
    private const val KEY_PASSWORD = "password"

    data class ProviderPreset(
        val name: String,
        val imapHost: String,
        val imapPort: Int,
        val smtpHost: String,
        val smtpPort: Int,
    )

    val PRESETS = listOf(
        ProviderPreset("Gmail", "imap.gmail.com", 993, "smtp.gmail.com", 465),
        ProviderPreset("Outlook / Hotmail", "outlook.office365.com", 993, "smtp.office365.com", 587),
        ProviderPreset("Yahoo", "imap.mail.yahoo.com", 993, "smtp.mail.yahoo.com", 465),
        ProviderPreset("iCloud", "imap.mail.me.com", 993, "smtp.mail.me.com", 587),
        ProviderPreset("Fastmail", "imap.fastmail.com", 993, "smtp.fastmail.com", 465),
        ProviderPreset("Custom…", "", 993, "", 465),
    )

    data class Account(
        val imapHost: String,
        val imapPort: Int,
        val smtpHost: String,
        val smtpPort: Int,
        val email: String,
    )

    data class EmailMessage(
        val uid: Long,
        val from: String,
        val subject: String,
        val date: String,
        val snippet: String,
    )

    sealed class ApiResult<out T> {
        data class Ok<T>(val value: T) : ApiResult<T>()
        data class NotConnected(val hint: String = "Email is not connected. Add your IMAP/SMTP account in Settings → Connectors.") :
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
        prefs(context).getString(KEY_EMAIL, null)?.isNotEmpty() == true &&
            prefs(context).getString(KEY_PASSWORD, null)?.isNotEmpty() == true

    fun account(context: Context): Account? {
        val p = prefs(context)
        val email = p.getString(KEY_EMAIL, null)?.takeIf { it.isNotBlank() } ?: return null
        if (p.getString(KEY_PASSWORD, null).isNullOrEmpty()) return null
        return Account(
            imapHost = p.getString(KEY_IMAP_HOST, "").orEmpty(),
            imapPort = p.getInt(KEY_IMAP_PORT, 993),
            smtpHost = p.getString(KEY_SMTP_HOST, "").orEmpty(),
            smtpPort = p.getInt(KEY_SMTP_PORT, 465),
            email = email,
        )
    }

    private fun password(context: Context): String? =
        prefs(context).getString(KEY_PASSWORD, null)?.takeIf { it.isNotEmpty() }

    /**
     * Validate by logging in over IMAP; on success stores everything
     * encrypted. Returns null on success, or a human-readable error.
     */
    suspend fun connect(
        context: Context,
        imapHost: String,
        imapPort: Int,
        smtpHost: String,
        smtpPort: Int,
        email: String,
        password: String,
    ): String? = withContext(Dispatchers.IO) {
        val err = imapLogin(
            imapHost.trim(), imapPort, email.trim(), password,
            loginOnly = true,
        )
        if (err != null) return@withContext err
        prefs(context).edit().apply {
            putString(KEY_IMAP_HOST, imapHost.trim())
            putInt(KEY_IMAP_PORT, imapPort)
            putString(KEY_SMTP_HOST, smtpHost.trim())
            putInt(KEY_SMTP_PORT, smtpPort)
            putString(KEY_EMAIL, email.trim())
            putString(KEY_PASSWORD, password)
        }.apply()
        null
    }

    fun disconnect(context: Context) {
        prefs(context).edit().clear().apply()
    }

    // ------------------------------------------------------------------
    // IMAP
    // ------------------------------------------------------------------

    /** A line-oriented reader that also handles IMAP literal blocks ({n}). */
    private class ImapReader(private val input: InputStream) {
        private val line = ByteArrayOutputStream()

        /** Next line (CRLF stripped), or null on EOF. */
        fun readLine(): String? {
            line.reset()
            while (true) {
                val b = input.read()
                if (b == -1) return if (line.size() == 0) null else line.toString("UTF-8")
                if (b == '\r'.code) {
                    val n = input.read()
                    if (n != '\n'.code && n != -1) {
                        // Unusual; keep the byte for the next line.
                        return line.toString("UTF-8")
                    }
                    return line.toString("UTF-8")
                }
                if (b == '\n'.code) return line.toString("UTF-8")
                line.write(b)
            }
        }

        /** Read exactly [n] raw bytes (for literal blocks). */
        fun readBytes(n: Int): ByteArray {
            val out = ByteArray(n)
            var done = 0
            while (done < n) {
                val r = input.read(out, done, n - done)
                if (r == -1) break
                done += r
            }
            return out.copyOf(done)
        }
    }

    private class ImapSession(
        host: String,
        port: Int,
        private val tagPrefix: String = "a",
    ) : AutoCloseable {
        private val socket: SSLSocket =
            (SSLSocketFactory.getDefault().createSocket(host, port) as SSLSocket).apply {
                soTimeout = 30_000
            }
        private val reader = ImapReader(socket.inputStream)
        private val out = socket.outputStream
        private var tag = 0
        val charset: Charset = Charsets.UTF_8

        init {
            val greet = reader.readLine()
                ?: throw ImapException("No greeting from server")
            if (!greet.startsWith("* OK")) throw ImapException("Bad greeting: ${safe(greet)}")
        }

        private fun nextTag(): String = "$tagPrefix${++tag}"

        fun cmd(command: String): List<String> {
            val t = nextTag()
            out.write("$t $command\r\n".toByteArray(charset))
            out.flush()
            val lines = mutableListOf<String>()
            while (true) {
                var l = reader.readLine() ?: throw ImapException("Connection closed")
                // Literal block: "... {1234}" — swallow the bytes, keep the line.
                val lit = Regex("\\{(\\d+)\\}$").find(l)
                if (lit != null) {
                    val n = lit.groupValues[1].toInt()
                    val bytes = reader.readBytes(n)
                    l = l + "<$n bytes>"
                    lines.add(l)
                    // Stash literal payload lines after a marker for the caller.
                    lines.add("LITERAL:" + String(bytes, charset))
                } else {
                    lines.add(l)
                }
                if (l.startsWith("$t ")) {
                    if (l.startsWith("$t OK")) return lines
                    throw ImapException("Server said: ${safe(l.removePrefix("$t "))}")
                }
                if (lines.size > 2000) throw ImapException("Response too large")
            }
        }

        fun login(user: String, pass: String) {
            cmd("LOGIN ${quote(user)} ${quote(pass)}")
        }

        override fun close() {
            runCatching { cmd("LOGOUT") }
            runCatching { socket.close() }
        }

        companion object {
            fun quote(s: String): String =
                "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

            fun safe(s: String): String =
                if (s.length > 160) s.take(160) + "…" else s
        }
    }

    private class ImapException(message: String) : Exception(message)

    /** Returns null on success, or a user-facing error string. */
    private fun imapLogin(
        host: String,
        port: Int,
        user: String,
        pass: String,
        loginOnly: Boolean,
    ): String? {
        if (host.isBlank()) return "Enter the IMAP host."
        return try {
            ImapSession(host, port).use { s ->
                s.login(user, pass)
                if (loginOnly) {
                    // Leave the mailbox alone on a pure credential check.
                }
            }
            null
        } catch (e: ImapException) {
            friendlyImapError(e.message)
        } catch (e: SocketTimeoutException) {
            "Timed out talking to $host. Check the host and port."
        } catch (e: Exception) {
            AppLogger.warning(TAG, "[imap] ${e.javaClass.simpleName}")
            "Couldn't reach $host (${e.message ?: "connection failed"})."
        }
    }

    private fun friendlyImapError(serverMsg: String?): String {
        val m = serverMsg.orEmpty()
        return when {
            m.contains("AUTHENTICATIONFAILED", ignoreCase = true) ||
                m.contains("Invalid credentials", ignoreCase = true) ||
                m.contains("LOGIN failed", ignoreCase = true) ->
                "Login rejected — wrong email or password. (Gmail/Yahoo/iCloud need an app-specific password.)"
            else -> "IMAP error: $m"
        }
    }

    private fun <T> withImap(context: Context, block: (ImapSession, Account) -> T): ApiResult<T> {
        val acc = account(context) ?: return ApiResult.NotConnected()
        val pass = password(context) ?: return ApiResult.NotConnected()
        return try {
            ImapSession(acc.imapHost, acc.imapPort).use { s ->
                s.login(acc.email, pass)
                ApiResult.Ok(block(s, acc))
            }
        } catch (e: ImapException) {
            AppLogger.warning(TAG, "[imap] ${e.message}")
            ApiResult.Error(friendlyImapError(e.message))
        } catch (e: Exception) {
            AppLogger.warning(TAG, "[imap] ${e.javaClass.simpleName}")
            ApiResult.Error("Couldn't reach ${acc.imapHost} (${e.message ?: "connection failed"}).")
        }
    }

    /** Mailbox names on this account. */
    suspend fun folders(context: Context): ApiResult<List<String>> =
        withContext(Dispatchers.IO) {
            withImap(context) { s, _ ->
                s.cmd("LIST \"\" \"*\"").mapNotNull { line ->
                    // * LIST (\HasNoChildren) "/" "INBOX"
                    Regex("\"([^\"]+)\"$").find(line)?.groupValues?.get(1)
                        ?: Regex(" ([^ ]+)$").find(line)?.groupValues?.get(1)
                }.filter { it.isNotBlank() }.distinct()
            }
        }

    /** Latest messages from a folder (newest first). Header + short snippet only. */
    suspend fun latest(
        context: Context,
        folder: String = "INBOX",
        limit: Int = 10,
    ): ApiResult<List<EmailMessage>> = withContext(Dispatchers.IO) {
        withImap(context) { s, _ ->
            val folderName = folder.ifBlank { "INBOX" }
            s.cmd("SELECT ${ImapSession.quote(folderName)}")
            val search = s.cmd("UID SEARCH ALL")
            val uids = search.firstOrNull { it.startsWith("* SEARCH") }
                ?.removePrefix("* SEARCH")?.trim()
                ?.split(Regex("\\s+"))?.mapNotNull { it.toLongOrNull() }
                .orEmpty()
            val take = uids.takeLast(limit.coerceIn(1, 25)).reversed()
            if (take.isEmpty()) return@withImap emptyList()
            val fetch = s.cmd(
                "UID FETCH ${take.joinToString(",")} " +
                    "(UID BODY.PEEK[HEADER.FIELDS (FROM SUBJECT DATE)] BODY.PEEK[TEXT]<0.300>)",
            )
            parseFetch(fetch)
        }
    }

    private fun parseFetch(lines: List<String>): List<EmailMessage> {
        val out = mutableListOf<EmailMessage>()
        var uid = 0L
        var headers = StringBuilder()
        var text = StringBuilder()
        // Which buffer the next LITERAL payload belongs to.
        var pending: StringBuilder? = null

        fun flush() {
            if (uid != 0L || headers.isNotEmpty()) {
                out.add(
                    EmailMessage(
                        uid = uid,
                        from = headerValue(headers.toString(), "from"),
                        subject = decodeHeader(headerValue(headers.toString(), "subject")),
                        date = headerValue(headers.toString(), "date"),
                        snippet = text.toString().replace(Regex("\\s+"), " ").trim().take(140),
                    ),
                )
            }
            uid = 0L; headers = StringBuilder(); text = StringBuilder()
            pending = null
        }

        for (line in lines) {
            when {
                line.startsWith("LITERAL:") -> {
                    pending?.append(line.removePrefix("LITERAL:"))
                }
                line.startsWith("* ") && line.contains("FETCH") -> {
                    flush()
                    Regex("UID (\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull()
                        ?.let { uid = it }
                    // The literal announced on this line (if any) belongs to…
                    pending = when {
                        line.contains("HEADER", ignoreCase = true) -> headers
                        line.contains("TEXT", ignoreCase = true) -> text
                        else -> null
                    }
                }
                // Continuation line announcing the second literal, e.g.
                // " BODY[TEXT]<0> {123}".
                line.contains("{") && line.contains("<") -> {
                    pending = when {
                        line.contains("HEADER", ignoreCase = true) -> headers
                        line.contains("TEXT", ignoreCase = true) -> text
                        else -> pending
                    }
                }
                // Tagged completion line ("a3 OK ...").
                Regex("^a\\d+ ").containsMatchIn(line) -> flush()
            }
        }
        flush()
        return out
    }

    private fun headerValue(raw: String, name: String): String {
        var value = StringBuilder()
        var capture = false
        for (line in raw.lines()) {
            if (line.startsWith(name + ":", ignoreCase = true)) {
                capture = true
                value.append(line.substringAfter(":").trim())
            } else if (capture && (line.startsWith(" ") || line.startsWith("\t"))) {
                value.append(" ").append(line.trim())
            } else if (capture) break
        }
        return value.toString().trim()
    }

    /** Decode =?charset?B?...?= / =?charset?Q?...?= words (best effort). */
    private fun decodeHeader(s: String): String {
        if (!s.contains("=?")) return s
        return Regex("=\\?([^?]+)\\?([bBqQ])\\?([^?]+)\\?=").replace(s) { m ->
            val charset = m.groupValues[1]
            val enc = m.groupValues[2].uppercase()
            val data = m.groupValues[3]
            runCatching {
                val bytes = if (enc == "B") Base64.decode(data, Base64.DEFAULT)
                else data.replace("_", " ").let { q ->
                    // Quoted-printable decode (minimal)
                    val bos = ByteArrayOutputStream()
                    var i = 0
                    while (i < q.length) {
                        val c = q[i]
                        if (c == '=' && i + 2 < q.length) {
                            bos.write(q.substring(i + 1, i + 3).toInt(16)); i += 3
                        } else { bos.write(c.code); i++ }
                    }
                    bos.toByteArray()
                }
                String(bytes, Charset.forName(charset))
            }.getOrDefault(m.value)
        }
    }

    // ------------------------------------------------------------------
    // SMTP
    // ------------------------------------------------------------------

    /**
     * Send a plain-text email via the stored account. Returns null on
     * success, or a user-facing error.
     */
    suspend fun send(
        context: Context,
        to: String,
        subject: String,
        body: String,
    ): ApiResult<Unit> = withContext(Dispatchers.IO) {
        val acc = account(context) ?: return@withContext ApiResult.NotConnected()
        val pass = password(context) ?: return@withContext ApiResult.NotConnected()
        if (to.isBlank() || !to.contains("@")) return@withContext ApiResult.Error("Enter a valid recipient address.")
        try {
            val socket = (SSLSocketFactory.getDefault()
                .createSocket(acc.smtpHost, acc.smtpPort) as SSLSocket).apply {
                soTimeout = 30_000
            }
            socket.use { sock ->
                val reader = ImapReader(sock.inputStream)
                val out = sock.outputStream
                fun expect(vararg codes: String): String {
                    val line = reader.readLine() ?: throw SmtpException("Connection closed")
                    if (codes.none { line.startsWith(it) }) throw SmtpException("Server said: $line")
                    // swallow multiline continuations (250-...)
                    var last = line
                    while (last.length > 3 && last[3] == '-') {
                        last = reader.readLine() ?: break
                    }
                    return line
                }
                fun sendLine(l: String) {
                    out.write("$l\r\n".toByteArray(Charsets.UTF_8)); out.flush()
                }
                expect("220")
                sendLine("EHLO unibot")
                expect("250")
                sendLine("AUTH LOGIN")
                expect("334")
                sendLine(Base64.encodeToString(acc.email.toByteArray(), Base64.NO_WRAP))
                expect("334")
                sendLine(Base64.encodeToString(pass.toByteArray(), Base64.NO_WRAP))
                expect("235")
                sendLine("MAIL FROM:<${acc.email}>")
                expect("250")
                sendLine("RCPT TO:<${to.trim()}>")
                expect("250", "251")
                sendLine("DATA")
                expect("354")
                val msg = buildString {
                    append("From: ${acc.email}\r\n")
                    append("To: ${to.trim()}\r\n")
                    append("Subject: ${subject.replace(Regex("[\\r\\n]"), " ")}\r\n")
                    append("Content-Type: text/plain; charset=utf-8\r\n\r\n")
                    append(body.replace(Regex("\\r?\\n"), "\r\n"))
                }.replace(Regex("(?m)^\\."), "..") // dot-stuffing
                out.write("$msg\r\n.\r\n".toByteArray(Charsets.UTF_8)); out.flush()
                expect("250")
                sendLine("QUIT")
            }
            ApiResult.Ok(Unit)
        } catch (e: SmtpException) {
            AppLogger.warning(TAG, "[smtp] ${e.message}")
            ApiResult.Error(smtpFriendly(e.message))
        } catch (e: Exception) {
            AppLogger.warning(TAG, "[smtp] ${e.javaClass.simpleName}")
            ApiResult.Error("Couldn't reach ${acc.smtpHost} (${e.message ?: "connection failed"}).")
        }
    }

    private class SmtpException(message: String) : Exception(message)

    private fun smtpFriendly(serverMsg: String?): String {
        val m = serverMsg.orEmpty()
        return when {
            m.contains("535", ignoreCase = true) ->
                "SMTP login rejected — check the password (Gmail/Yahoo/iCloud need an app-specific password)."
            else -> "SMTP error: $m"
        }
    }
}
