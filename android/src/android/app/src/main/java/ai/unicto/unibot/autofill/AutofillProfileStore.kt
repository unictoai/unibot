package ai.unicto.unibot.autofill

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The on-device autofill profile: name, email, phone. Stored in a private
 * SharedPreferences file — never leaves the phone, never synced, never sent
 * to any provider. Filling only happens when the user explicitly enables the
 * service AND selects unibot as the autofill service in system settings
 * (Android requires the system-level opt-in; this toggle alone enables nothing).
 */
data class AutofillProfile(
    val enabled: Boolean = false,
    val name: String = "",
    val email: String = "",
    val phone: String = "",
)

class AutofillProfileStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _profile = MutableStateFlow(load())
    val profile: StateFlow<AutofillProfile> = _profile.asStateFlow()

    fun update(profile: AutofillProfile) {
        _profile.value = profile
        prefs.edit()
            .putBoolean(KEY_ENABLED, profile.enabled)
            .putString(KEY_NAME, profile.name)
            .putString(KEY_EMAIL, profile.email)
            .putString(KEY_PHONE, profile.phone)
            .apply()
    }

    private fun load() = AutofillProfile(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        name = prefs.getString(KEY_NAME, "") ?: "",
        email = prefs.getString(KEY_EMAIL, "") ?: "",
        phone = prefs.getString(KEY_PHONE, "") ?: "",
    )

    companion object {
        private const val PREFS = "unibot_autofill_profile"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_NAME = "name"
        private const val KEY_EMAIL = "email"
        private const val KEY_PHONE = "phone"

        @Volatile private var instance: AutofillProfileStore? = null

        fun get(context: Context): AutofillProfileStore =
            instance ?: synchronized(this) {
                instance ?: AutofillProfileStore(context.applicationContext).also { instance = it }
            }
    }
}
