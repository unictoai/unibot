package ai.unicto.unibot.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * T-android-keystore-aead-fail: self-healing wrapper around
 * [EncryptedSharedPreferences.create].
 *
 * The default flow throws `AEADBadTagException` (wrapped as
 * `GeneralSecurityException`) on launch when the AndroidKeystore master
 * key can no longer decrypt the Tink keyset blob — observed on Samsung
 * One UI / Android 16 after backup-restore or biometric re-enroll. The
 * exception bubbles to the main thread and the app dies in a relaunch
 * loop because every cold start hits the same lazy init.
 *
 * Strategy:
 *  1. Try the normal create.
 *  2. On any crypto error: delete ONLY this store's own XML file (the
 *     Tink keysets live inside `$fileName.xml`, verified against the
 *     androidx.security:crypto 1.1.0-alpha06 AAR) and retry. The shared
 *     master key is deliberately left alone here — deleting it would
 *     invalidate every OTHER encrypted store on the device (the cascade
 *     that used to wipe all credentials again and again).
 *  3. If that still fails, the master key itself is broken: delete the
 *     keystore alias (once per process) and retry. This does invalidate
 *     the other stores, but the alternative is a boot loop.
 *  4. If recreate still fails: fall back to an IN-MEMORY
 *     SharedPreferences so the rest of the app sees an empty,
 *     read-write store and never crashes. Nothing is ever written to
 *     disk in this state — a plaintext file would end up in cloud
 *     backup (the old `${fileName}_plain_fallback` files are excluded
 *     from backup rules for the same reason).
 *
 * Note: per-store master-key aliases were considered and rejected —
 * switching aliases would make every existing store undecryptable on
 * upgrade, wiping all stored credentials for all users at once. The
 * staged recovery above is the safe migration-free path.
 */
object EncryptedPrefsFactory {
    private const val TAG = "EncryptedPrefsFactory"

    /** Guard so the shared master key is regenerated at most once per process. */
    private val masterKeyWiped = AtomicBoolean(false)

    fun safeCreate(context: Context, fileName: String): SharedPreferences {
        runCatching { return build(context, fileName) }
            .onFailure { Log.w(TAG, "first create($fileName) failed: ${it.message}") }

        // Recovery 1: this store's own files are corrupt. Delete only
        // $fileName.xml (SP data + Tink keysets) — the shared master key
        // stays, so the other ~40 stores keep working.
        wipeStoreFiles(context, fileName)
        runCatching { return build(context, fileName) }
            .onFailure { Log.w(TAG, "rebuild($fileName) after file wipe failed: ${it.message}") }

        // Recovery 2: the master key itself is undecryptable. Regenerate
        // it (once per process) — other stores will need re-auth, but the
        // app boots instead of looping.
        if (masterKeyWiped.compareAndSet(false, true)) {
            wipeMasterKey(context)
            runCatching { return build(context, fileName) }
                .onFailure {
                    Log.e(TAG, "rebuild($fileName) after master-key wipe failed: ${it.message}", it)
                }
        } else {
            Log.w(TAG, "master key already wiped this process; skipping second wipe for $fileName")
        }

        Log.w(TAG, "falling back to in-memory prefs for $fileName — credentials lost, nothing persisted")
        return InMemoryPrefs()
    }

    private fun build(context: Context, fileName: String): SharedPreferences {
        val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            fileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** Deletes this store's XML file (data + Tink keysets live inside it). */
    private fun wipeStoreFiles(context: Context, fileName: String) {
        runCatching {
            val dir = File(context.applicationInfo.dataDir, "shared_prefs")
            File(dir, "$fileName.xml").delete()
        }.onFailure { Log.w(TAG, "wipe store files failed: ${it.message}") }
    }

    /**
     * Deletes the MasterKey keyset file plus the AndroidKeystore alias so
     * the next [build] regenerates them. Verified against the
     * androidx.security:crypto AAR: MasterKey keeps its wrapped keyset in
     * the `_androidx_security_master_key_` prefs file (NOT
     * `__androidx_security_crypto_encrypted_prefs__.xml`, which belongs to
     * the deprecated MasterKeys API and is never written here).
     */
    private fun wipeMasterKey(context: Context) {
        runCatching {
            val dir = File(context.applicationInfo.dataDir, "shared_prefs")
            File(dir, "_androidx_security_master_key_.xml").delete()
        }.onFailure { Log.w(TAG, "wipe master-key prefs file failed: ${it.message}") }

        runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)) {
                ks.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            }
        }.onFailure { Log.w(TAG, "wipe master-key alias failed: ${it.message}") }
    }

    /**
     * Non-persisted SharedPreferences — the last-resort fallback when
     * encrypted storage is unavailable. Nothing here ever touches disk,
     * so secrets re-entered in this state can't leak into backups.
     */
    private class InMemoryPrefs : SharedPreferences {
        private val data = ConcurrentHashMap<String, Any?>()
        private val listeners = CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()

        override fun getAll(): Map<String, *> = HashMap(data)

        override fun getString(key: String, defValue: String?): String? =
            data[key] as? String ?: defValue

        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
            (data[key] as? Set<*>)?.filterIsInstance<String>()?.toSet() ?: defValues

        override fun getInt(key: String, defValue: Int): Int = data[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = data[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = data[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            data[key] as? Boolean ?: defValue

        override fun contains(key: String): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = MemEditor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener,
        ) {
            listeners.add(listener)
        }

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener,
        ) {
            listeners.remove(listener)
        }

        private inner class MemEditor : SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>()
            private var clearAll = false

            private fun notifyChanged(keys: Set<String>) {
                for (l in listeners) {
                    for (k in keys) {
                        runCatching { l.onSharedPreferenceChanged(this@InMemoryPrefs, k) }
                    }
                }
            }

            override fun putString(key: String, value: String?): SharedPreferences.Editor =
                apply { pending[key] = value }

            override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor =
                apply { pending[key] = values?.toSet() }

            override fun putInt(key: String, value: Int): SharedPreferences.Editor =
                apply { pending[key] = value }

            override fun putLong(key: String, value: Long): SharedPreferences.Editor =
                apply { pending[key] = value }

            override fun putFloat(key: String, value: Float): SharedPreferences.Editor =
                apply { pending[key] = value }

            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor =
                apply { pending[key] = value }

            override fun remove(key: String): SharedPreferences.Editor =
                apply { pending[key] = null }

            override fun clear(): SharedPreferences.Editor = apply { clearAll = true }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearAll) {
                    data.clear()
                    clearAll = false
                }
                for ((k, v) in pending) {
                    if (v == null) data.remove(k) else data[k] = v
                }
                notifyChanged(pending.keys)
                pending.clear()
            }
        }
    }
}
