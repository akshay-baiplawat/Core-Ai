package com.stridetech.coreai.ml

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
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

/**
 * Test subclass that overrides createRunner() to return a pure-Kotlin InferenceRunner mock.
 * LlmInference (which has a native static initializer) is never referenced or instantiated.
 */
private class FakeMediaPipeModelEngine(
    context: Context,
    private val fakeRunner: InferenceRunner? = null,
    private val throwOnCreate: Exception? = null,
) : MediaPipeModelEngine(context) {

    var capturedModelPath: String? = null

    override fun createRunner(modelPath: String): InferenceRunner {
        capturedModelPath = modelPath
        throwOnCreate?.let { throw it }
        return requireNotNull(fakeRunner) { "No fake runner provided" }
    }
}

class MediaPipeModelEngineTest {

    private lateinit var context: Context
    private lateinit var mockBackend: Backend

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        context = mockk(relaxed = true)
        mockBackend = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ── Initial state ──────────────────────────────────────────────────────────

    @Test
    fun `initial state - isReady is false, no model name, no load error`() {
        val engine = FakeMediaPipeModelEngine(context)
        assertFalse(engine.isReady)
        assertNull(engine.activeModelName())
        assertNull(engine.lastLoadError)
    }

    // ── load() ─────────────────────────────────────────────────────────────────

    @Test
    fun `load success - createRunner is called, sets ready state and model name`() = runTest {
        val engine = FakeMediaPipeModelEngine(context, fakeRunner = mockk(relaxed = true))

        engine.load("/models/gemma.bin", mockBackend)

        assertTrue(engine.isReady)
        assertEquals("gemma", engine.activeModelName())
        assertNull(engine.lastLoadError)
    }

    @Test
    fun `load passes the correct model path to createRunner`() = runTest {
        val engine = FakeMediaPipeModelEngine(context, fakeRunner = mockk(relaxed = true))

        engine.load("/sdcard/models/deepseek.task", mockBackend)

        assertEquals("/sdcard/models/deepseek.task", engine.capturedModelPath)
        assertEquals("deepseek", engine.activeModelName())
    }

    @Test
    fun `load failure - records error detail in lastLoadError and rethrows the exception`() {
        val engine = FakeMediaPipeModelEngine(
            context,
            throwOnCreate = RuntimeException("model file not found")
        )

        assertThrows(RuntimeException::class.java) {
            runBlocking { engine.load("/models/gemma.bin", mockBackend) }
        }

        assertFalse(engine.isReady)
        assertNotNull(engine.lastLoadError)
        assertTrue(
            "lastLoadError should contain the exception message",
            engine.lastLoadError!!.contains("model file not found")
        )
    }

    @Test
    fun `load calls release on previous runner before loading a new one`() = runTest {
        val firstRunner = mockk<InferenceRunner>(relaxed = true)
        val secondRunner = mockk<InferenceRunner>(relaxed = true)

        val engine = FakeMediaPipeModelEngine(context, fakeRunner = firstRunner)
        engine.load("/models/model-a.bin", mockBackend)
        assertTrue(engine.isReady)

        // Replace the fake runner for the second load
        injectRunnerField(engine, firstRunner)
        val engine2 = object : MediaPipeModelEngine(context) {
            override fun createRunner(modelPath: String) = secondRunner
        }
        injectRunnerField(engine2, firstRunner)
        engine2.load("/models/model-b.bin", mockBackend)

        // release() on the first runner prevents 1.5 GB memory leaks
        verify(exactly = 1) { firstRunner.release() }
        assertEquals("model-b", engine2.activeModelName())
    }

    // ── close() ────────────────────────────────────────────────────────────────

    @Test
    fun `close - calls release on the runner and resets to unready state`() {
        val mockRunner = mockk<InferenceRunner>(relaxed = true)
        val engine = FakeMediaPipeModelEngine(context)
        injectRunnerField(engine, mockRunner)

        engine.close()

        verify(exactly = 1) { mockRunner.release() }
        assertFalse(engine.isReady)
        assertNull(engine.activeModelName())
    }

    @Test
    fun `close when not loaded is a safe no-op`() {
        val engine = FakeMediaPipeModelEngine(context)
        // Must not throw when runner field is null
        engine.close()
        assertFalse(engine.isReady)
    }

    // ── runInference() ─────────────────────────────────────────────────────────

    @Test
    fun `runInference - delegates to runner generate and returns the result`() = runTest {
        val mockRunner = mockk<InferenceRunner>(relaxed = true)
        every { mockRunner.generate("test prompt") } returns "Hello world"
        val engine = FakeMediaPipeModelEngine(context)
        injectRunnerField(engine, mockRunner)

        val result = engine.runInference("test prompt")

        assertEquals("Hello world", result)
        verify(exactly = 1) { mockRunner.generate("test prompt") }
    }

    @Test
    fun `runInference - SDK exception during generation is propagated to the caller`() {
        val mockRunner = mockk<InferenceRunner>(relaxed = true)
        every { mockRunner.generate(any()) } throws RuntimeException("generation failed")
        val engine = FakeMediaPipeModelEngine(context)
        injectRunnerField(engine, mockRunner)

        assertThrows(RuntimeException::class.java) {
            runBlocking { engine.runInference("test prompt") }
        }
    }

    @Test
    fun `runInference - throws IllegalArgumentException when runner is not loaded`() {
        val engine = FakeMediaPipeModelEngine(context)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { engine.runInference("test prompt") }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Reflects an InferenceRunner mock directly into the engine's private field to
     * test lifecycle methods without going through load().
     */
    private fun injectRunnerField(engine: MediaPipeModelEngine, mockRunner: InferenceRunner, modelName: String = "injected-model") {
        val runnerField = MediaPipeModelEngine::class.java.getDeclaredField("runner")
        runnerField.isAccessible = true
        runnerField.set(engine, mockRunner)

        val modelNameField = MediaPipeModelEngine::class.java.getDeclaredField("modelName")
        modelNameField.isAccessible = true
        modelNameField.set(engine, modelName)
    }
}
