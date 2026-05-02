package com.stridetech.coreai.ui.settings

import com.stridetech.coreai.security.ApiKeyManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SettingsViewModelTest {

    private lateinit var mockApiKeyManager: ApiKeyManager
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        mockApiKeyManager = mockk(relaxed = true)
        every { mockApiKeyManager.getExistingKeys() } returns emptyList()
        viewModel = SettingsViewModel(mockApiKeyManager)
    }

    // ── Initial state ─────────────────────────────────────────────────────────

    @Test
    fun `initial state loads existing keys from manager`() {
        every { mockApiKeyManager.getExistingKeys() } returns listOf("key-a", "key-b")
        val vm = SettingsViewModel(mockApiKeyManager)
        assertEquals(listOf("key-a", "key-b"), vm.uiState.value.apiKeys)
    }

    @Test
    fun `initial state is empty when no keys exist`() {
        assertTrue(viewModel.uiState.value.apiKeys.isEmpty())
    }

    // ── generateKey ───────────────────────────────────────────────────────────

    @Test
    fun `generateKey delegates to ApiKeyManager and reloads state`() {
        every { mockApiKeyManager.generateKey() } returns "new-key"
        every { mockApiKeyManager.getExistingKeys() } returns listOf("new-key")

        viewModel.generateKey()

        verify(exactly = 1) { mockApiKeyManager.generateKey() }
        assertEquals(listOf("new-key"), viewModel.uiState.value.apiKeys)
    }

    @Test
    fun `generateKey updates ui state with the newly added key`() {
        every { mockApiKeyManager.generateKey() } returns "fresh-key"
        // After generateKey() calls reload(), getExistingKeys returns the new key
        every { mockApiKeyManager.getExistingKeys() } returns listOf("fresh-key")

        viewModel.generateKey()

        assertTrue(viewModel.uiState.value.apiKeys.contains("fresh-key"))
    }

    // ── revokeKey ─────────────────────────────────────────────────────────────

    @Test
    fun `revokeKey delegates to ApiKeyManager with the correct key`() {
        every { mockApiKeyManager.getExistingKeys() } returns listOf("key-1", "key-2")
        val vm = SettingsViewModel(mockApiKeyManager)

        every { mockApiKeyManager.getExistingKeys() } returns listOf("key-2")
        vm.revokeKey("key-1")

        verify(exactly = 1) { mockApiKeyManager.revokeKey("key-1") }
        assertEquals(listOf("key-2"), vm.uiState.value.apiKeys)
    }

    @Test
    fun `revokeKey removes only the targeted key from ui state`() {
        every { mockApiKeyManager.getExistingKeys() }
            .returnsMany(listOf("key-a", "key-b"), listOf("key-b"))

        val vm = SettingsViewModel(mockApiKeyManager)
        vm.revokeKey("key-a")

        val keys = vm.uiState.value.apiKeys
        assertTrue(!keys.contains("key-a"))
        assertTrue(keys.contains("key-b"))
    }

    // ── revokeAll ─────────────────────────────────────────────────────────────

    @Test
    fun `revokeAll delegates to ApiKeyManager and clears ui state`() {
        every { mockApiKeyManager.getExistingKeys() }
            .returnsMany(listOf("k1", "k2"), emptyList())

        val vm = SettingsViewModel(mockApiKeyManager)
        vm.revokeAll()

        verify(exactly = 1) { mockApiKeyManager.revokeAll() }
        assertTrue(vm.uiState.value.apiKeys.isEmpty())
    }

    @Test
    fun `revokeAll on already empty state does not throw`() {
        viewModel.revokeAll()
        verify(exactly = 1) { mockApiKeyManager.revokeAll() }
        assertTrue(viewModel.uiState.value.apiKeys.isEmpty())
    }
}
