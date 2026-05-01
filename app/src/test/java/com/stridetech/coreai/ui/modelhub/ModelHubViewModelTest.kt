package com.stridetech.coreai.ui.modelhub

import android.app.Application
import android.content.Intent
import android.content.ServiceConnection
import com.stridetech.coreai.ICoreAiInterface
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Field

@OptIn(ExperimentalCoroutinesApi::class)
class ModelHubViewModelTest {

    private lateinit var testDispatcher: TestDispatcher
    private lateinit var viewModel: ModelHubViewModel
    private lateinit var mockService: ICoreAiInterface

    @Before
    fun setUp() {
        testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)

        mockkStatic(Dispatchers::class)
        every { Dispatchers.IO } returns testDispatcher

        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().setClassName(any<String>(), any<String>()) } returns mockk(relaxed = true)

        val fakeApp = mockk<Application>(relaxed = true)
        every { fakeApp.bindService(any<Intent>(), any<ServiceConnection>(), any<Int>()) } returns false
        every { fakeApp.getExternalFilesDir(any()) } returns null

        viewModel = ModelHubViewModel(fakeApp)
        mockService = mockk(relaxed = true)
        injectService(viewModel, mockService)
    }

    @After
    fun tearDown() {
        unmockkStatic(Dispatchers::class)
        unmockkConstructor(Intent::class)
        Dispatchers.resetMain()
    }

    // ── loadModel ──────────────────────────────────────────────────────────────

    @Test
    fun `loadModel calls service loadModel with correct path`() = runTest(testDispatcher) {
        val model = fakeModel("gemma.litertlm", "/ext/models/gemma.litertlm")
        every { mockService.getActiveModelName() } returns null
        every { mockService.getLoadedModelNames() } returns ""

        viewModel.loadModel(model)
        advanceUntilIdle()

        verify(exactly = 1) { mockService.loadModel("/ext/models/gemma.litertlm") }
    }

    @Test
    fun `loadModel does not crash when service is null`() = runTest(testDispatcher) {
        injectService(viewModel, null)
        val model = fakeModel("gemma.litertlm", "/ext/models/gemma.litertlm")

        viewModel.loadModel(model)
        advanceUntilIdle()
        // No exception = pass
    }

    // ── unloadModel ────────────────────────────────────────────────────────────

    @Test
    fun `unloadModel calls service unloadModel with correct path`() = runTest(testDispatcher) {
        val model = fakeModel("gemma.litertlm", "/ext/models/gemma.litertlm", isLoaded = true)
        every { mockService.getActiveModelName() } returns null
        every { mockService.getLoadedModelNames() } returns ""

        viewModel.unloadModel(model)
        advanceUntilIdle()

        verify(exactly = 1) { mockService.unloadModel("/ext/models/gemma.litertlm") }
    }

    @Test
    fun `unloadModel does not crash when service is null`() = runTest(testDispatcher) {
        injectService(viewModel, null)
        val model = fakeModel("gemma.litertlm", "/ext/models/gemma.litertlm", isLoaded = true)

        viewModel.unloadModel(model)
        advanceUntilIdle()
        // No exception = pass
    }

    // ── setActiveModel ─────────────────────────────────────────────────────────

    @Test
    fun `setActiveModel calls service setActiveModel with correct path`() = runTest(testDispatcher) {
        val model = fakeModel("gemma.litertlm", "/ext/models/gemma.litertlm", isLoaded = true)
        every { mockService.getActiveModelName() } returns null
        every { mockService.getLoadedModelNames() } returns ""

        viewModel.setActiveModel(model)
        advanceUntilIdle()

        verify(exactly = 1) { mockService.setActiveModel("/ext/models/gemma.litertlm") }
    }

    @Test
    fun `setActiveModel does not crash when service is null`() = runTest(testDispatcher) {
        injectService(viewModel, null)
        val model = fakeModel("gemma.litertlm", "/ext/models/gemma.litertlm", isLoaded = true)

        viewModel.setActiveModel(model)
        advanceUntilIdle()
        // No exception = pass
    }

    // ── deleteModel ────────────────────────────────────────────────────────────

    @Test
    fun `deleteModel calls unloadModel on service when model is loaded`() = runTest(testDispatcher) {
        val model = fakeModel("gemma.litertlm", "/ext/models/gemma.litertlm", isLoaded = true)

        viewModel.deleteModel(model)
        advanceUntilIdle()

        verify(exactly = 1) { mockService.unloadModel("/ext/models/gemma.litertlm") }
    }

    @Test
    fun `deleteModel does not call unloadModel when model is not loaded`() = runTest(testDispatcher) {
        val model = fakeModel("gemma.litertlm", "/ext/models/gemma.litertlm", isLoaded = false)

        viewModel.deleteModel(model)
        advanceUntilIdle()

        verify(exactly = 0) { mockService.unloadModel(any()) }
    }

    // ── ModelInfo state mapping ────────────────────────────────────────────────

    @Test
    fun `ModelInfo isLoaded and isActive flags are independent`() {
        val loadedActive = fakeModel("a.bin", "/m/a.bin", isLoaded = true, isActive = true)
        val loadedNotActive = fakeModel("b.bin", "/m/b.bin", isLoaded = true, isActive = false)
        val notLoaded = fakeModel("c.bin", "/m/c.bin", isLoaded = false, isActive = false)

        assertTrue(loadedActive.isLoaded)
        assertTrue(loadedActive.isActive)
        assertTrue(loadedNotActive.isLoaded)
        assertFalse(loadedNotActive.isActive)
        assertFalse(notLoaded.isLoaded)
        assertFalse(notLoaded.isActive)
    }

    // ── dismissError / dismissSuccess ──────────────────────────────────────────

    @Test
    fun `dismissError clears importError`() {
        setUiState(viewModel) { it.copy(importError = "some error") }
        viewModel.dismissError()
        assertEquals(null, viewModel.uiState.value.importError)
    }

    @Test
    fun `dismissSuccess clears importSuccess flag`() {
        setUiState(viewModel) { it.copy(importSuccess = true) }
        viewModel.dismissSuccess()
        assertFalse(viewModel.uiState.value.importSuccess)
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun fakeModel(
        fileName: String,
        absolutePath: String,
        isLoaded: Boolean = false,
        isActive: Boolean = false
    ) = ModelInfo(
        fileName = fileName,
        absolutePath = absolutePath,
        fileSizeBytes = 1_000_000L,
        lastModified = 1_700_000_000_000L,
        isLoaded = isLoaded,
        isActive = isActive
    )

    private fun injectService(vm: ModelHubViewModel, service: ICoreAiInterface?) {
        val field: Field = ModelHubViewModel::class.java.getDeclaredField("coreAiService")
        field.isAccessible = true
        field.set(vm, service)
    }

    @Suppress("UNCHECKED_CAST")
    private fun setUiState(vm: ModelHubViewModel, transform: (ModelHubUiState) -> ModelHubUiState) {
        val field: Field = ModelHubViewModel::class.java.getDeclaredField("_uiState")
        field.isAccessible = true
        val flow = field.get(vm) as MutableStateFlow<ModelHubUiState>
        flow.value = transform(flow.value)
    }
}
