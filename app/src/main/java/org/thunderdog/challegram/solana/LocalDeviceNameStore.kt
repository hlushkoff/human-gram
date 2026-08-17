package org.thunderdog.challegram.solana

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Local Device Name storage — direct port of ondo-zero-android's LocalDeviceNameStore.
 *
 * The name is shown to other participants and attached to on-chain media proofs
 * (LocalDeviceNameMemo). Stored in EncryptedSharedPreferences with a legacy-prefs
 * migration path, exactly like Ondo-Zero Video.
 */
internal const val KEY_REGISTERED_NAME = "registered_name"

private const val LEGACY_PREFS_NAME = "humangram_prefs"
private const val SECURE_PREFS_NAME = "humangram_secure_prefs"
private const val TAG = "LocalDeviceNameStore"

object LocalDeviceNameRules {
    const val MAX_LENGTH = 40

    private val allowedCharacters = Regex("^[\\p{L}\\p{N}_*()\\- ]+$")

    enum class ValidationError {
        REQUIRED, TOO_LONG, INVALID_CHARS
    }

    fun validationError(value: String): ValidationError? = when {
        value.isBlank() -> ValidationError.REQUIRED
        value.length > MAX_LENGTH -> ValidationError.TOO_LONG
        !allowedCharacters.matches(value) -> ValidationError.INVALID_CHARS
        else -> null
    }

    fun isValid(value: String): Boolean = validationError(value) == null
}

object LocalDeviceNameStore {
    @JvmStatic
    fun read(context: Context): String? {
        val securePrefs = securePrefsOrNull(context)
        if (securePrefs != null) {
            migrateLegacyValue(context, securePrefs)
            return securePrefs.getString(KEY_REGISTERED_NAME, null)
        }
        return legacyPrefs(context).getString(KEY_REGISTERED_NAME, null)
    }

    @JvmStatic
    fun hasStoredName(context: Context): Boolean = !read(context).isNullOrEmpty()

    @JvmStatic
    fun save(context: Context, value: String) {
        require(LocalDeviceNameRules.isValid(value)) { "Invalid Local Device Name" }
        val securePrefs = securePrefsOrNull(context)
        if (securePrefs != null) {
            migrateLegacyValue(context, securePrefs)
            securePrefs.edit().putString(KEY_REGISTERED_NAME, value).apply()
            legacyPrefs(context).edit().remove(KEY_REGISTERED_NAME).apply()
            return
        }

        Log.w(TAG, "Encrypted prefs unavailable, storing Local Device Name in legacy prefs")
        legacyPrefs(context).edit().putString(KEY_REGISTERED_NAME, value).apply()
    }

    @JvmStatic
    fun clear(context: Context) {
        securePrefsOrNull(context)?.edit()?.remove(KEY_REGISTERED_NAME)?.apply()
        legacyPrefs(context).edit().remove(KEY_REGISTERED_NAME).apply()
    }

    private fun migrateLegacyValue(context: Context, securePrefs: SharedPreferences) {
        if (securePrefs.contains(KEY_REGISTERED_NAME)) return
        val legacyValue = legacyPrefs(context).getString(KEY_REGISTERED_NAME, null) ?: return
        securePrefs.edit().putString(KEY_REGISTERED_NAME, legacyValue).apply()
        legacyPrefs(context).edit().remove(KEY_REGISTERED_NAME).apply()
    }

    private fun legacyPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)

    private fun securePrefsOrNull(context: Context): SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (error: Exception) {
        Log.e(TAG, "Failed to open encrypted Local Device Name storage", error)
        null
    }
}
