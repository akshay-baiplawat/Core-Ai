package com.stridetech.coreai.ui.playground

import android.app.Application
import android.content.Intent
import android.content.ServiceConnection
import com.stridetech.coreai.ICoreAiCallback
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
    fun `successful inference appends user and model messages`() = runTest(testDispatcher) {
        val json = """{"completion":"Hello world","latency_ms":42,"success":true,"error":null}"""
        stubInference(json)

        viewModel.onPromptChange("Say hello")
        viewModel.runInference()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.messages.size)
        assertEquals(MessageRole.USER, state.messages[0].role)
        assertEquals("Say hello", state.messages[0].content)
        assertEquals(MessageRole.MODEL, state.messages[1].role)
        assertEquals("Hello world", state.messages[1].content)
        assertEquals(42L, state.messages[1].latencyMs)
        assertFalse(state.isLoading)
        assertNull(state.error)
    }

    @Test
    fun `successful inference clears prompt field after send`() = runTest(testDispatcher) {
        stubInference("""{"completion":"OK","latency_ms":10,"success":true,"error":null}""")

        viewModel.onPromptChange("hello")
        viewModel.runInference()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.prompt.isEmpty())
    }

    @Test
    fun `multi-turn inference builds accumulated context`() = runTest(testDispatcher) {
        val json1 = """{"completion":"I am an AI","latency_ms":10,"success":true,"error":null}"""
        val json2 = """{"completion":"Still me","latency_ms":5,"success":true,"error":null}"""
        stubInferenceSequence(json1, json2)

        viewModel.onPromptChange("Who are you?")
        viewModel.runInference()
        advanceUntilIdle()

        viewModel.onPromptChange("Are you sure?")
        viewModel.runInference()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(4, state.messages.size)
        assertEquals("Who are you?", state.messages[0].content)
        assertEquals("I am an AI", state.messages[1].content)
        assertEquals("Are you sure?", state.messages[2].content)
        assertEquals("Still me", state.messages[3].content)

        verify {
            mockService.runInference(
                FAKE_MASTER_KEY,
                match { it.contains("User: Who are you?") && it.contains("Model: I am an AI") }
            )
        }
    }

    @Test
    fun `successful inference clears previous error`() = runTest(testDispatcher) {
        stubInference("""{"completion":null,"latency_ms":0,"success":false,"error":"old error"}""")
        viewModel.onPromptChange("first")
        viewModel.runInference()
        advanceUntilIdle()

        stubInference("""{"completion":"OK","latency_ms":10,"success":true,"error":null}""")
        viewModel.onPromptChange("second")
        viewModel.runInference()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.error)
        val modelMessages = viewModel.uiState.value.messages.filter { it.role == MessageRole.MODEL }
        assertTrue(modelMessages.any { it.content == "OK" })
    }

    // ── Inference failure path ────────────────────────────────────────────────

    @Test
    fun `service returning success=false sets error state`() = runTest(testDispatcher) {
        stubInference("""{"completion":null,"latency_ms":0,"success":false,"error":"Model not loaded"}""")

        viewModel.onPromptChange("trigger error")
        viewModel.runInference()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Model not loaded", state.error)
        assertEquals(1, state.messages.size)
        assertEquals(MessageRole.USER, state.messages[0].role)
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
        stubInference("not-json-at-all")

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
        assertTrue(viewModel.uiState.value.messages.isEmpty())
    }

    // ── Clear history ─────────────────────────────────────────────────────────

    @Test
    fun `clearHistory wipes messages and error`() = runTest(testDispatcher) {
        stubInference("""{"completion":"Hello","latency_ms":5,"success":true,"error":null}""")
        viewModel.onPromptChange("hi")
        viewModel.runInference()
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.messages.size)

        viewModel.clearHistory()

        assertTrue(viewModel.uiState.value.messages.isEmpty())
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `clearHistory on empty list is a no-op`() {
        viewModel.clearHistory()
        assertTrue(viewModel.uiState.value.messages.isEmpty())
    }

    // ── API key handling ─────────────────────────────────────────────────────

    @Test
    fun `runInference passes master api key to service`() = runTest(testDispatcher) {
        stubInference("""{"completion":"ok","latency_ms":1,"success":true,"error":null}""")

        viewModel.onPromptChange("hello")
        viewModel.runInference()
        advanceUntilIdle()

        verify(exactly = 1) { mockService.runInference(eq(FAKE_MASTER_KEY), any()) }
    }

    @Test
    fun `master key reuses existing key instead of generating new one`() = runTest(testDispatcher) {
        stubInference("""{"completion":"ok","latency_ms":1,"success":true,"error":null}""")

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

        val fakeApp = mockk<Application>(relaxed = true)
        every { fakeApp.bindService(any<Intent>(), any<ServiceConnection>(), any<Int>()) } returns false
        every { fakeApp.packageName } returns "com.stridetech.coreai"
        val freshVm = PlaygroundViewModel(fakeApp, mockApiKeyManager)
        injectService(freshVm, mockService)
        setServiceBound(freshVm, true)
        stubInferenceFor(freshVm, mockService, """{"completion":"ok","latency_ms":1,"success":true,"error":null}""")

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

    // ── Context string format ─────────────────────────────────────────────────

    @Test
    fun `first message sends only user prefix in context`() = runTest(testDispatcher) {
        stubInference("""{"completion":"hi","latency_ms":1,"success":true,"error":null}""")

        viewModel.onPromptChange("hello")
        viewModel.runInference()
        advanceUntilIdle()

        verify {
            mockService.runInference(any(), match { it == "User: hello" })
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Stubs runInference to return [json] AND synchronously fire the ViewModel's
     * modelCallback.onInferenceResult — mirroring what CoreAiService does in production.
     */
    private fun stubInference(json: String) {
        stubInferenceFor(viewModel, mockService, json)
    }

    private fun stubInferenceSequence(vararg jsons: String) {
        val callback = getModelCallback(viewModel)
        var callCount = 0
        every { mockService.runInference(any(), any()) } answers {
            val result = jsons.getOrElse(callCount) { jsons.last() }
            callCount++
            callback.onInferenceResult(result)
            result
        }
    }

    private fun stubInferenceFor(vm: PlaygroundViewModel, service: ICoreAiInterface, json: String) {
        val callback = getModelCallback(vm)
        every { service.runInference(any(), any()) } answers {
            callback.onInferenceResult(json)
            json
        }
    }

    private fun getModelCallback(vm: PlaygroundViewModel): ICoreAiCallback {
        val field: Field = PlaygroundViewModel::class.java.getDeclaredField("modelCallback")
        field.isAccessible = true
        return field.get(vm) as ICoreAiCallback
    }

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
