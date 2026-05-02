package com.stridetech.coreai.ml

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LiteRtModelEngineTest {

    private val cacheDir = System.getProperty("java.io.tmpdir")!!
    private lateinit var engine: LiteRtModelEngine
    private lateinit var mockBackend: Backend

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        engine = LiteRtModelEngine(cacheDir)
        mockBackend = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ── Initial state ──────────────────────────────────────────────────────────

    @Test
    fun `initial state - isReady is false, no model name, no load error`() {
        assertFalse(engine.isReady)
        assertNull(engine.activeModelName())
        assertNull(engine.lastLoadError)
    }

    // ── load() ─────────────────────────────────────────────────────────────────

    @Test
    fun `load success - initializes SDK engine, sets ready state and model name`() = runTest {
        mockkConstructor(EngineConfig::class)
        mockkConstructor(Engine::class)
        every { anyConstructed<Engine>().initialize() } just runs

        engine.load("/models/gemma.litertlm", mockBackend)

        assertTrue(engine.isReady)
        assertEquals("gemma", engine.activeModelName())
        assertNull(engine.lastLoadError)
        verify(exactly = 1) { anyConstructed<Engine>().initialize() }
    }

    @Test
    fun `load failure - records error detail and rethrows the exception`() {
        mockkConstructor(EngineConfig::class)
        mockkConstructor(Engine::class)
        every { anyConstructed<Engine>().initialize() } throws RuntimeException("native init failed")

        assertThrows(RuntimeException::class.java) {
            runBlocking { engine.load("/models/gemma.litertlm", mockBackend) }
        }

        assertFalse(engine.isReady)
        assertNotNull(engine.lastLoadError)
        assertTrue(
            "lastLoadError should contain the exception message",
            engine.lastLoadError!!.contains("native init failed")
        )
    }

    @Test
    fun `load closes previous engine before loading a new one`() = runTest {
        mockkConstructor(EngineConfig::class)
        mockkConstructor(Engine::class)
        every { anyConstructed<Engine>().initialize() } just runs
        every { anyConstructed<Engine>().close() } just runs

        engine.load("/models/gemma.litertlm", mockBackend)
        engine.load("/models/deepseek.litertlm", mockBackend)

        assertEquals("deepseek", engine.activeModelName())
        // close() is called on the first engine during the second load
        verify(atLeast = 1) { anyConstructed<Engine>().close() }
    }

    // ── close() ────────────────────────────────────────────────────────────────

    @Test
    fun `close - calls underlying SDK engine close and resets to unready state`() {
        val mockNativeEngine = mockk<Engine>(relaxed = true)
        injectEngineField(mockNativeEngine)

        engine.close()

        verify(exactly = 1) { mockNativeEngine.close() }
        assertFalse(engine.isReady)
        assertNull(engine.activeModelName())
    }

    @Test
    fun `close when not loaded is a safe no-op`() {
        // Must not throw when engine field is null
        engine.close()
        assertFalse(engine.isReady)
    }

    // ── runInference() ─────────────────────────────────────────────────────────

    @Test
    fun `runInference - joins streamed tokens into a single string response`() = runTest {
        val mockNativeEngine = mockk<Engine>(relaxed = true)
        val mockConversation = mockk<Conversation>(relaxed = true)
        val msgHello = mockk<Message>(relaxed = true)
        val msgWorld = mockk<Message>(relaxed = true)
        every { msgHello.toString() } returns "Hello"
        every { msgWorld.toString() } returns " world"
        every { mockNativeEngine.createConversation(any()) } returns mockConversation
        every { mockConversation.sendMessageAsync(any<String>(), any<Map<String, Any>>()) } returns flowOf(msgHello, msgWorld)
        injectEngineField(mockNativeEngine)

        val result = engine.runInference("test prompt")

        assertEquals("Hello world", result)
        verify(exactly = 1) { mockConversation.sendMessageAsync("test prompt", any<Map<String, Any>>()) }
    }

    @Test
    fun `runInference - SDK exception during generation is propagated to the caller`() {
        val mockNativeEngine = mockk<Engine>(relaxed = true)
        every { mockNativeEngine.createConversation(any()) } throws RuntimeException("generation failed")
        injectEngineField(mockNativeEngine)

        assertThrows(RuntimeException::class.java) {
            runBlocking { engine.runInference("test prompt") }
        }
    }

    @Test
    fun `runInference - throws IllegalArgumentException when engine is not loaded`() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { engine.runInference("test prompt") }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Bypasses load() entirely by reflecting the native Engine mock directly into
     * the engine's private field — prevents any JNI execution in lifecycle tests.
     */
    private fun injectEngineField(mockNativeEngine: Engine) {
        val engineField = LiteRtModelEngine::class.java.getDeclaredField("engine")
        engineField.isAccessible = true
        engineField.set(engine, mockNativeEngine)

        val modelNameField = LiteRtModelEngine::class.java.getDeclaredField("modelName")
        modelNameField.isAccessible = true
        modelNameField.set(engine, "injected-model")
    }
}
