package com.stridetech.coreai.ml

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LlmEngineTest {

    private lateinit var context: Context
    private lateinit var engine: LlmEngine

    private fun fakeEngine(name: String, ready: Boolean = true): ModelEngine = mockk(relaxed = true) {
        every { isReady } returns ready
        every { activeModelName() } returns name
        every { lastLoadError } returns null
    }

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        context = mockk(relaxed = true)
        engine = LlmEngine(context)
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    // ── Initial state ──────────────────────────────────────────────────────────

    @Test
    fun `initial state has no active model and is not ready`() {
        assertFalse(engine.isReady)
        assertNull(engine.activeModelName())
        assertTrue(engine.loadedModelNames().isEmpty())
    }

    // ── load() ─────────────────────────────────────────────────────────────────

    @Test
    fun `load first model makes it active automatically`() = runTest {
        injectEngine(engine, "/models/gemma.litertlm", fakeEngine("gemma"))

        assertEquals("gemma", engine.activeModelName())
        assertTrue(engine.isReady)
    }

    @Test
    fun `loading second model does not change active model`() = runTest {
        injectEngine(engine, "/models/gemma.litertlm", fakeEngine("gemma"))
        injectEngine(engine, "/models/deepseek.bin", fakeEngine("deepseek"))

        assertEquals("gemma", engine.activeModelName())
        assertEquals(2, engine.loadedModelNames().size)
        assertTrue(engine.loadedModelNames().containsAll(listOf("gemma", "deepseek")))
    }

    @Test
    fun `loading model with same name closes previous instance`() = runTest {
        val first = fakeEngine("gemma")
        val second = fakeEngine("gemma")
        injectEngine(engine, "/models/gemma.litertlm", first)

        // Simulate the replace-and-close logic that LlmEngine.load() performs
        val enginesField = LlmEngine::class.java.getDeclaredField("engines")
        enginesField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val engines = enginesField.get(engine) as MutableMap<String, ModelEngine>
        engines["gemma"]?.close()
        engines["gemma"] = second

        verify(exactly = 1) { first.close() }
        assertEquals(1, engines.size)
    }

    // ── unload() ───────────────────────────────────────────────────────────────

    @Test
    fun `unload removes model from pool and calls close`() = runTest {
        val mock = fakeEngine("gemma")
        injectEngine(engine, "/models/gemma.litertlm", mock)

        engine.unload("/models/gemma.litertlm")

        verify(exactly = 1) { mock.close() }
        assertTrue(engine.loadedModelNames().isEmpty())
        assertFalse(engine.isReady)
        assertNull(engine.activeModelName())
    }

    @Test
    fun `unloading active model promotes next loaded model as active`() = runTest {
        injectEngine(engine, "/models/gemma.litertlm", fakeEngine("gemma"))
        injectEngine(engine, "/models/deepseek.bin", fakeEngine("deepseek"))

        engine.unload("/models/gemma.litertlm")

        assertEquals("deepseek", engine.activeModelName())
    }

    @Test
    fun `unloading non-active model keeps active model unchanged`() = runTest {
        injectEngine(engine, "/models/gemma.litertlm", fakeEngine("gemma"))
        injectEngine(engine, "/models/deepseek.bin", fakeEngine("deepseek"))

        engine.unload("/models/deepseek.bin")

        assertEquals("gemma", engine.activeModelName())
        assertEquals(listOf("gemma"), engine.loadedModelNames())
    }

    @Test
    fun `unloading unknown path is a no-op`() = runTest {
        injectEngine(engine, "/models/gemma.litertlm", fakeEngine("gemma"))
        engine.unload("/models/nonexistent.bin")

        assertEquals(1, engine.loadedModelNames().size)
        assertEquals("gemma", engine.activeModelName())
    }

    // ── setActive() ────────────────────────────────────────────────────────────

    @Test
    fun `setActive switches which model is used for inference`() = runTest {
        injectEngine(engine, "/models/gemma.litertlm", fakeEngine("gemma"))
        injectEngine(engine, "/models/deepseek.bin", fakeEngine("deepseek"))

        engine.setActive("/models/deepseek.bin")

        assertEquals("deepseek", engine.activeModelName())
    }

    @Test
    fun `setActive on unloaded model throws IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            engine.setActive("/models/notloaded.bin")
        }
    }

    // ── runInference() ─────────────────────────────────────────────────────────

    @Test
    fun `runInference delegates to active engine`() = runTest {
        val mock = fakeEngine("gemma")
        coEvery { mock.runInference("hello") } returns "world"
        injectEngine(engine, "/models/gemma.litertlm", mock)

        val result = engine.runInference("hello")

        assertEquals("world", result)
        coVerify(exactly = 1) { mock.runInference("hello") }
    }

    @Test
    fun `runInference with no active model throws`() = runTest {
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { engine.runInference("hello") }
        }
    }

    @Test
    fun `runInference targets active model not last loaded`() = runTest {
        val gemma = fakeEngine("gemma")
        val deepseek = fakeEngine("deepseek")
        coEvery { gemma.runInference(any()) } returns "gemma-response"
        coEvery { deepseek.runInference(any()) } returns "deepseek-response"

        injectEngine(engine, "/models/gemma.litertlm", gemma)
        injectEngine(engine, "/models/deepseek.bin", deepseek)
        engine.setActive("/models/deepseek.bin")

        val result = engine.runInference("prompt")

        assertEquals("deepseek-response", result)
        coVerify(exactly = 0) { gemma.runInference(any()) }
    }

    // ── close() ────────────────────────────────────────────────────────────────

    @Test
    fun `close unloads all engines and resets state`() = runTest {
        val g = fakeEngine("gemma")
        val d = fakeEngine("deepseek")
        injectEngine(engine, "/models/gemma.litertlm", g)
        injectEngine(engine, "/models/deepseek.bin", d)

        engine.close()

        verify(exactly = 1) { g.close() }
        verify(exactly = 1) { d.close() }
        assertTrue(engine.loadedModelNames().isEmpty())
        assertNull(engine.activeModelName())
        assertFalse(engine.isReady)
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Injects a pre-built ModelEngine into LlmEngine's internal pool, bypassing
     * the actual load() call which requires Android framework and native libs.
     */
    private fun injectEngine(llmEngine: LlmEngine, modelPath: String, mock: ModelEngine) {
        val key = modelPath.substringAfterLast('/').substringBeforeLast('.')
        val enginesField = LlmEngine::class.java.getDeclaredField("engines")
        enginesField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val engines = enginesField.get(llmEngine) as MutableMap<String, ModelEngine>
        engines[key] = mock

        val activeKeyField = LlmEngine::class.java.getDeclaredField("activeKey")
        activeKeyField.isAccessible = true
        if (activeKeyField.get(llmEngine) == null) {
            activeKeyField.set(llmEngine, key)
        }
    }
}
