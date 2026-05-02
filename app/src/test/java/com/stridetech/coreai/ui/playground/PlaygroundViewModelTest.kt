package com.stridetech.coreai.ui.playground

import android.app.Application
import android.content.Intent
import android.content.ServiceConnection
import com.stridetech.coreai.ICoreAiInterface
import com.stridetech.coreai.security.ApiKeyManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Field

@OptIn(ExperimentalCoroutinesApi::class)
class PlaygroundViewModelTest {

    private lateinit var testDispatcher: TestDispatcher
    private lateinit var viewModel: PlaygroundViewModel
    private lateinit var mockService: ICoreAiInterface
    private lateinit var mockApiKeyManager: ApiKeyManager

    companion object {
        private const val FAKE_MASTER_KEY = "test-api-key-1234"
    }

    @Before
    fun setUp() {
        testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)

        mockkStatic(Dispatchers::class)
        every { Dispatchers.IO } returns testDispatcher

        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().setClassName(any<String>(), any<String>()) } returns mockk(relaxed = true)

        mockApiKeyManager = mockk(relaxed = true)
        every { mockApiKeyManager.getExistingKeys() } returns listOf(FAKE_MASTER_KEY)
        every { mockApiKeyManager.generateKey() } returns FAKE_MASTER_KEY

        val fakeApp = mockk<Application>(relaxed = true)
        every { fakeApp.bindService(any<Intent>(), any<ServiceConnection>(), any<Int>()) } returns false
        every { fakeApp.packageName } returns "com.stridetech.coreai"

        viewModel = PlaygroundViewModel(fakeApp, mockApiKeyManager)

        mockService = mockk(relaxed = true)
        injectService(viewModel, mockService)
        setServiceBound(viewModel, true)
    }

    @After
    fun tearDown() {
        unmockkStatic(Dispatchers::class)
        unmockkConstructor(Intent::class)
        Dispatchers.resetMain()
    }

    // ── Inference success path ────────────────────────────────────────────────

    @Test
    fun `successful inference updates output and latency`() = runTest(testDispatcher) {
        val json = """{"completion":"Hello world","latency_ms":42,"success":true,"error":null}"""
        every { mockService.runInference(any(), any()) } returns json

        viewModel.onPromptChange("Say hello")
        viewModel.runInference()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Hello world", state.output)
        assertEquals(42L, state.latencyMs)
        assertFalse(state.isLoading)
        assertNull(state.error)
    }

    @Test
    fun `successful inference clears previous error`() = runTest(testDispatcher) {
        val errorJson = """{"completion":null,"latency_ms":0,"success":false,"error":"old error"}"""
        every { mockService.runInference(any(), any()) } returns errorJson
        viewModel.onPromptChange("first")
        viewModel.runInference()
        advanceUntilIdle()

        val successJson = """{"completion":"OK","latency_ms":10,"success":true,"error":null}"""
        every { mockService.runInference(any(), any()) } returns successJson
        viewModel.onPromptChange("second")
        viewModel.runInference()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.error)
        assertEquals("OK", viewModel.uiState.value.output)
    }

    // ── Inference failure path ────────────────────────────────────────────────

    @Test
    fun `service returning success=false sets error state`() = runTest(testDispatcher) {
        val json = """{"completion":null,"latency_ms":0,"success":false,"error":"Model not loaded"}"""
        every { mockService.runInference(any(), any()) } returns json

        viewModel.onPromptChange("trigger error")
        viewModel.runInference()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Model not loaded", state.error)
        assertTrue(state.output.isEmpty())
        assertFalse(state.isLoading)
    }

    @Test
    fun `service throwing RemoteException sets error state`() = runTest(testDispatcher) {
        every { mockService.runInference(any(), any()) } throws android.os.RemoteException("IPC dead")

        viewModel.onPromptChange("crash prompt")
        viewModel.runInference()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(
            "Error should mention 'Service error', was: ${state.error}",
            state.error?.startsWith("Service error") == true
        )
        assertFalse(state.isLoading)
    }

    @Test
    fun `malformed JSON sets parse error state`() = runTest(testDispatcher) {
        every { mockService.runInference(any(), any()) } returns "not-json-at-all"

        viewModel.onPromptChange("bad response")
        viewModel.runInference()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(
            "Error should mention parse failure, was: ${state.error}",
            state.error?.contains("parse", ignoreCase = true) == true ||
                state.error?.contains("Failed", ignoreCase = true) == true
        )
        assertFalse(state.isLoading)
    }

    // ── Guard: blank prompt skipped ───────────────────────────────────────────

    @Test
    fun `blank prompt does not trigger inference`() = runTest(testDispatcher) {
        viewModel.onPromptChange("   ")
        viewModel.runInference()
        advanceUntilIdle()

        verify(exactly = 0) { mockService.runInference(any(), any()) }
        assertFalse(viewModel.uiState.value.isLoading)
    }

    // ── API key is passed to service ─────────────────────────────────────────

    @Test
    fun `runInference passes master api key to service`() = runTest(testDispatcher) {
        val json = """{"completion":"ok","latency_ms":1,"success":true,"error":null}"""
        every { mockService.runInference(any(), any()) } returns json

        viewModel.onPromptChange("hello")
        viewModel.runInference()
        advanceUntilIdle()

        verify(exactly = 1) { mockService.runInference(FAKE_MASTER_KEY, "hello") }
    }

    @Test
    fun `master key reuses existing key instead of generating new one`() = runTest(testDispatcher) {
        // getExistingKeys returns a key, so generateKey should never be called
        every { mockService.runInference(any(), any()) } returns
            """{"completion":"ok","latency_ms":1,"success":true,"error":null}"""

        viewModel.onPromptChange("test")
        viewModel.runInference()
        advanceUntilIdle()

        verify(exactly = 0) { mockApiKeyManager.generateKey() }
        verify(exactly = 1) { mockService.runInference(FAKE_MASTER_KEY, any()) }
    }

    @Test
    fun `master key generates new key when no existing keys present`() = runTest(testDispatcher) {
        every { mockApiKeyManager.getExistingKeys() } returns emptyList()
        every { mockApiKeyManager.generateKey() } returns "brand-new-key"
        every { mockService.runInference(any(), any()) } returns
            """{"completion":"ok","latency_ms":1,"success":true,"error":null}"""

        // Create a fresh ViewModel with no existing keys
        val fakeApp = mockk<Application>(relaxed = true)
        every { fakeApp.bindService(any<Intent>(), any<ServiceConnection>(), any<Int>()) } returns false
        every { fakeApp.packageName } returns "com.stridetech.coreai"
        val freshVm = PlaygroundViewModel(fakeApp, mockApiKeyManager)
        injectService(freshVm, mockService)
        setServiceBound(freshVm, true)

        freshVm.onPromptChange("test")
        freshVm.runInference()
        advanceUntilIdle()

        verify(exactly = 1) { mockApiKeyManager.generateKey() }
        verify(exactly = 1) { mockService.runInference("brand-new-key", any()) }
    }

    // ── Prompt state ─────────────────────────────────────────────────────────

    @Test
    fun `onPromptChange updates prompt in ui state`() {
        viewModel.onPromptChange("hello")
        assertEquals("hello", viewModel.uiState.value.prompt)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun injectService(vm: PlaygroundViewModel, service: ICoreAiInterface) {
        val field: Field = PlaygroundViewModel::class.java.getDeclaredField("coreAiService")
        field.isAccessible = true
        field.set(vm, service)
    }

    @Suppress("UNCHECKED_CAST")
    private fun setServiceBound(vm: PlaygroundViewModel, bound: Boolean) {
        val field: Field = PlaygroundViewModel::class.java.getDeclaredField("_uiState")
        field.isAccessible = true
        val stateFlow = field.get(vm) as MutableStateFlow<PlaygroundUiState>
        stateFlow.value = stateFlow.value.copy(isServiceBound = bound)
    }
}
