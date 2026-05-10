package com.stridetech.coreai.security

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ApiKeyManager].
 *
 * [EncryptedSharedPreferences.create] and [MasterKey.Builder] require the Android Keystore,
 * so both are mocked statically. Actual preference read/write logic is exercised through
 * an in-memory [FakeSharedPreferences] that implements [SharedPreferences].
 */
class ApiKeyManagerTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var manager: ApiKeyManager

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()

        mockkStatic(EncryptedSharedPreferences::class)
        mockkConstructor(MasterKey.Builder::class)

        val mockMasterKey = mockk<MasterKey>(relaxed = true)
        every {
            anyConstructed<MasterKey.Builder>().setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        } returns mockk(relaxed = true) {
            every { build() } returns mockMasterKey
        }
        every {
            EncryptedSharedPreferences.create(
                any<Context>(),
                any<String>(),
                any<MasterKey>(),
                any<EncryptedSharedPreferences.PrefKeyEncryptionScheme>(),
                any<EncryptedSharedPreferences.PrefValueEncryptionScheme>()
            )
        } returns fakePrefs

        val mockApp = mockk<Application>(relaxed = true)
        manager = ApiKeyManager(mockApp)
        manager.getExistingKeys() // triggers lazy `prefs` initialisation
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ── generateKey ───────────────────────────────────────────────────────────

    @Test
    fun `generateKey returns a non-blank UUID-format string`() {
        val key = manager.generateKey()
        assertTrue("Key must not be blank", key.isNotBlank())
        assertTrue(
            "Key should be UUID format, was: $key",
            key.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
        )
    }

    @Test
    fun `generateKey stores the key so getExistingKeys returns it`() {
        val key = manager.generateKey()
        assertTrue(manager.getExistingKeys().contains(key))
    }

    @Test
    fun `generating multiple keys accumulates all of them`() {
        val key1 = manager.generateKey()
        val key2 = manager.generateKey()
        val keys = manager.getExistingKeys()
        assertTrue(keys.contains(key1))
        assertTrue(keys.contains(key2))
        assertEquals(2, keys.size)
    }

    // ── isValidKey ────────────────────────────────────────────────────────────

    @Test
    fun `isValidKey returns true for a key that was generated`() {
        val key = manager.generateKey()
        assertTrue(manager.isValidKey(key))
    }

    @Test
    fun `isValidKey returns false for an unknown key`() {
        assertFalse(manager.isValidKey("completely-unknown-key"))
    }

    @Test
    fun `isValidKey returns false for a blank string`() {
        assertFalse(manager.isValidKey(""))
    }

    @Test
    fun `isValidKey returns false after the key has been revoked`() {
        val key = manager.generateKey()
        manager.revokeKey(key)
        assertFalse(manager.isValidKey(key))
    }

    // ── revokeKey ─────────────────────────────────────────────────────────────

    @Test
    fun `revokeKey removes only the specified key leaving others intact`() {
        val key1 = manager.generateKey()
        val key2 = manager.generateKey()
        manager.revokeKey(key1)
        assertFalse(manager.getExistingKeys().contains(key1))
        assertTrue(manager.getExistingKeys().contains(key2))
    }

    @Test
    fun `revokeKey on non-existent key does not throw`() {
        manager.generateKey()
        manager.revokeKey("key-that-does-not-exist")
        assertEquals(1, manager.getExistingKeys().size)
    }

    // ── revokeAll ─────────────────────────────────────────────────────────────

    @Test
    fun `revokeAll clears all stored keys`() {
        manager.generateKey()
        manager.generateKey()
        manager.revokeAll()
        assertTrue(manager.getExistingKeys().isEmpty())
    }

    @Test
    fun `revokeAll on empty store does not throw`() {
        manager.revokeAll()
        assertTrue(manager.getExistingKeys().isEmpty())
    }

    // ── getExistingKeys ───────────────────────────────────────────────────────

    @Test
    fun `getExistingKeys returns empty list when no keys have been generated`() {
        assertTrue(manager.getExistingKeys().isEmpty())
    }

    @Test
    fun `getExistingKeys returns all keys after several generates and one revoke`() {
        val key1 = manager.generateKey()
        val key2 = manager.generateKey()
        val key3 = manager.generateKey()
        manager.revokeKey(key2)
        val keys = manager.getExistingKeys()
        assertTrue(keys.contains(key1))
        assertFalse(keys.contains(key2))
        assertTrue(keys.contains(key3))
        assertEquals(2, keys.size)
    }
}

// ── Fake SharedPreferences ────────────────────────────────────────────────────

/**
 * In-memory [SharedPreferences] sufficient for [ApiKeyManager]'s
 * `getStringSet` / `putStringSet` / `apply` usage pattern.
 */
private class FakeSharedPreferences : SharedPreferences {

    private val store = mutableMapOf<String, Any?>()

    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? {
        @Suppress("UNCHECKED_CAST")
        return (store[key] as? Set<String>) ?: defValues
    }

    override fun edit(): SharedPreferences.Editor = FakeEditor(store)
    override fun contains(key: String) = store.containsKey(key)
    override fun getAll(): Map<String, *> = store.toMap()
    override fun getString(key: String, defValue: String?) = store[key] as? String ?: defValue
    override fun getInt(key: String, defValue: Int) = store[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long) = store[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float) = store[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = store[key] as? Boolean ?: defValue
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private class FakeEditor(private val store: MutableMap<String, Any?>) : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()

        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor =
            apply { pending[key] = values?.toHashSet() }

        override fun apply() { store.putAll(pending); pending.clear() }
        override fun commit(): Boolean { apply(); return true }
        override fun remove(key: String) = apply { pending[key] = null }
        override fun clear() = apply { store.clear() }
        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
    }
}
