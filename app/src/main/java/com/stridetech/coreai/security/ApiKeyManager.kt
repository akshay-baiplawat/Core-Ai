package com.stridetech.coreai.security

import android.app.Application
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val PREFS_FILE = "coreai_api_keys"
private const val KEY_API_KEYS = "api_keys"

@Singleton
class ApiKeyManager @Inject constructor(application: Application) {

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(application)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            application,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun generateKey(): String {
        val key = UUID.randomUUID().toString()
        val updated = storedKeys().toMutableSet().also { it.add(key) }
        prefs.edit().putStringSet(KEY_API_KEYS, updated).commit()
        return key
    }

    fun isValidKey(key: String): Boolean = key.isNotBlank() && storedKeys().contains(key)

    fun getExistingKeys(): List<String> = storedKeys().toList()

    fun revokeKey(key: String) {
        val updated = storedKeys().toMutableSet().also { it.remove(key) }
        prefs.edit().putStringSet(KEY_API_KEYS, updated).commit()
    }

    fun revokeAll() {
        prefs.edit().putStringSet(KEY_API_KEYS, emptySet()).commit()
    }

    private fun storedKeys(): Set<String> =
        prefs.getStringSet(KEY_API_KEYS, emptySet()) ?: emptySet()
}
