package com.stridetech.coreai.service

import android.os.RemoteCallbackList
import android.util.Log
import com.stridetech.coreai.ICoreAiCallback
import com.stridetech.coreai.ml.LlmEngine
import com.stridetech.coreai.security.ApiKeyManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [CoreAiService] targeting:
 *   - Happy path: GPU backend loads successfully
 *   - Fallback path: GPU fails → service retries with CPU
 *   - Total failure: model file not found in external storage
 *   - Total failure: both GPU and CPU backends fail
 *
 * Android framework dependencies are removed via:
 *   - [TestableCoreAiService] subclass overriding [CoreAiService.getExternalFilesDir]
 *   - Reflection injection of a mocked [RemoteCallbackList] into the `callbacks` field
 *   - [mockkStatic] silencing [Log] calls
 *   - [CountDownLatch] tied to [RemoteCallbackList.finishBroadcast] for deterministic async sync
 */
class CoreAiServiceTest {

    private lateinit var mockEngine: LlmEngine
    private lateinit var mockCallback: ICoreAiCallback
    private lateinit var mockCallbacks: RemoteCallbackList<ICoreAiCallback>
    private lateinit var mockApiKeyManager: ApiKeyManager
    private lateinit var tempModelsDir: File
    private lateinit var service: TestableCoreAiService

    companion object {
        private const val VALID_KEY = "valid-test-key"
    }

    // ── Testable subclass ──────────────────────────────────────────────────────

    /**
     * Subclass that overrides [getExternalFilesDir] so the service runs on the JVM
     * without a real Android context. All business logic in the superclass is unchanged.
     */
    private class TestableCoreAiService : CoreAiService() {
        var modelsDir: File? = null

        override fun getExternalFilesDir(type: String?): File? =
            if (type == "models") modelsDir else null
    }

    // ── Setup / Teardown ───────────────────────────────────────────────────────

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        mockCallback = mockk(relaxed = true)

        // Use a typed mock; RelaxedMock returns sensible defaults (0 for beginBroadcast)
        // so we only need to stub the methods that the broadcast loops actually depend on.
        @Suppress("UNCHECKED_CAST")
        mockCallbacks = mockk<RemoteCallbackList<ICoreAiCallback>>(relaxed = true)
        every { mockCallbacks.beginBroadcast() } returns 1
        every { mockCallbacks.getBroadcastItem(0) } returns mockCallback
        every { mockCallbacks.finishBroadcast() } just runs

        mockEngine = mockk(relaxed = true)
        tempModelsDir = Files.createTempDirectory("coreai-test-").toFile()

        mockApiKeyManager = mockk(relaxed = true)
        every { mockApiKeyManager.isValidKey(VALID_KEY) } returns true
        every { mockApiKeyManager.isValidKey(not(eq(VALID_KEY))) } returns false

        service = TestableCoreAiService().also { it.modelsDir = tempModelsDir }

        // Inject mocks into private fields.  Kotlin `val` compiles to a non-static
        // private final field; setAccessible(true) + field.set() works on JDK 11.
        injectField(service, "engine", mockEngine)
        injectField(service, "callbacks", mockCallbacks)
        injectField(service, "apiKeyManager", mockApiKeyManager)
    }

    @After
    fun tearDown() {
        unmockkAll()
        tempModelsDir.deleteRecursively()
    }

    // ── Happy Path: GPU backend loads successfully ─────────────────────────────

    @Test
    fun `GPU backend loads successfully and broadcasts model ready state to callbacks`() {
        File(tempModelsDir, "gemma.bin").createNewFile()

        every { mockEngine.isReady } returns true
        every { mockEngine.activeModelName() } returns "gemma"
        coEvery { mockEngine.load(any(), any()) } just runs

        val latch = awaitFirstBroadcast()
        callPrivate(service, "loadWithFallback")

        assertTrue("Broadcast not completed within timeout", latch.await(5, TimeUnit.SECONDS))

        coVerify(exactly = 1) { mockEngine.load(any(), any()) }
        verify(exactly = 1) { mockCallback.onModelStateChanged(true, "gemma") }
        verify(exactly = 0) { mockCallback.onError(any()) }
    }

    @Test
    fun `isModelLoading flag is reset to false after successful GPU load`() {
        File(tempModelsDir, "model.litertlm").createNewFile()

        every { mockEngine.isReady } returns true
        every { mockEngine.activeModelName() } returns "model"
        coEvery { mockEngine.load(any(), any()) } just runs

        val latch = awaitFirstBroadcast()
        callPrivate(service, "loadWithFallback")
        latch.await(5, TimeUnit.SECONDS)

        val isModelLoading = (getField(service, "isModelLoading") as java.util.concurrent.atomic.AtomicBoolean).get()
        assertFalse("isModelLoading should be false after load completes", isModelLoading)
    }

    // ── Fallback Path: GPU fails → CPU succeeds ────────────────────────────────

    @Test
    fun `GPU FAILED_PRECONDITION error causes service to retry with CPU backend`() {
        File(tempModelsDir, "gemma.bin").createNewFile()

        every { mockEngine.isReady } returns true
        every { mockEngine.activeModelName() } returns "gemma"

        // First call (GpuArtisan) throws; second call (CPU) returns normally.
        val callCount = AtomicInteger(0)
        coEvery { mockEngine.load(any(), any()) } answers {
            if (callCount.incrementAndGet() == 1)
                throw RuntimeException("FAILED_PRECONDITION: OpenCL not supported on this device")
        }

        val latch = awaitFirstBroadcast()
        callPrivate(service, "loadWithFallback")

        assertTrue("CPU-fallback broadcast not completed within timeout", latch.await(5, TimeUnit.SECONDS))

        coVerify(exactly = 2) { mockEngine.load(any(), any()) }
        verify(exactly = 1) { mockCallback.onModelStateChanged(true, "gemma") }
        verify(exactly = 0) { mockCallback.onError(any()) }
    }

    @Test
    fun `loadFailed flag remains false when CPU fallback succeeds`() {
        File(tempModelsDir, "gemma.bin").createNewFile()

        every { mockEngine.isReady } returns true
        every { mockEngine.activeModelName() } returns "gemma"

        val callCount = AtomicInteger(0)
        coEvery { mockEngine.load(any(), any()) } answers {
            if (callCount.incrementAndGet() == 1)
                throw RuntimeException("FAILED_PRECONDITION: Vulkan validation failure")
        }

        val latch = awaitFirstBroadcast()
        callPrivate(service, "loadWithFallback")
        latch.await(5, TimeUnit.SECONDS)

        val loadFailed = (getField(service, "loadFailed") as java.util.concurrent.atomic.AtomicBoolean).get()
        assertFalse("loadFailed should stay false when CPU fallback succeeds", loadFailed)
    }

    // ── Total Failure: Missing model file ──────────────────────────────────────

    @Test
    fun `empty models directory sets modelNotFound and broadcasts error with correct message`() {
        // tempModelsDir is empty — no .bin or .litertlm files present

        val latch = awaitFirstBroadcast()
        callPrivate(service, "loadWithFallback")

        assertTrue("Error broadcast not completed within timeout", latch.await(5, TimeUnit.SECONDS))

        val modelNotFound = (getField(service, "modelNotFound") as java.util.concurrent.atomic.AtomicBoolean).get()
        assertTrue("modelNotFound should be true when directory contains no model files", modelNotFound)
        verify(exactly = 1) { mockCallback.onError(match { it.contains("No model file found") }) }
        verify(exactly = 0) { mockCallback.onModelStateChanged(any(), any()) }
    }

    @Test
    fun `null external storage sets modelNotFound and mentions unavailable storage in error`() {
        service.modelsDir = null

        val latch = awaitFirstBroadcast()
        callPrivate(service, "loadWithFallback")

        assertTrue("Error broadcast not completed within timeout", latch.await(5, TimeUnit.SECONDS))

        val modelNotFound = (getField(service, "modelNotFound") as java.util.concurrent.atomic.AtomicBoolean).get()
        assertTrue(modelNotFound)
        verify(exactly = 1) { mockCallback.onError(match { it.contains("external storage unavailable") }) }
        verify(exactly = 0) { mockCallback.onModelStateChanged(any(), any()) }
    }

    // ── Total Failure: Both GPU and CPU backends fail ──────────────────────────

    @Test
    fun `both GPU and CPU backends failing sets loadFailed and broadcasts error message`() {
        File(tempModelsDir, "gemma.bin").createNewFile()

        coEvery { mockEngine.load(any(), any()) } throws
            RuntimeException("CPU load failed: insufficient memory for 7B model")

        val latch = awaitFirstBroadcast()
        callPrivate(service, "loadWithFallback")

        assertTrue("Error broadcast not completed within timeout", latch.await(5, TimeUnit.SECONDS))

        val loadFailed = (getField(service, "loadFailed") as java.util.concurrent.atomic.AtomicBoolean).get()
        assertTrue("loadFailed should be true when both backends fail", loadFailed)
        verify(exactly = 1) { mockCallback.onError(match { it.startsWith("Model failed to load:") }) }
        verify(exactly = 0) { mockCallback.onModelStateChanged(any(), any()) }
    }

    @Test
    fun `total failure does not broadcast model ready state`() {
        File(tempModelsDir, "gemma.bin").createNewFile()

        coEvery { mockEngine.load(any(), any()) } throws RuntimeException("native crash")

        val latch = awaitFirstBroadcast()
        callPrivate(service, "loadWithFallback")
        latch.await(5, TimeUnit.SECONDS)

        verify(exactly = 0) { mockCallback.onModelStateChanged(any(), any()) }
    }

    // ── validateApiKey ────────────────────────────────────────────────────────

    @Test
    fun `validateApiKey returns true for a key the manager recognises`() {
        val binder = getField(service, "binder") as android.os.IBinder
        val result = (binder as com.stridetech.coreai.ICoreAiInterface.Stub).validateApiKey(VALID_KEY)
        assertTrue(result)
    }

    @Test
    fun `validateApiKey returns false for an unknown key`() {
        val binder = getField(service, "binder") as android.os.IBinder
        val result = (binder as com.stridetech.coreai.ICoreAiInterface.Stub).validateApiKey("bad-key")
        assertFalse(result)
    }

    @Test
    fun `validateApiKey returns false for a null key`() {
        val binder = getField(service, "binder") as android.os.IBinder
        val result = (binder as com.stridetech.coreai.ICoreAiInterface.Stub).validateApiKey(null)
        assertFalse(result)
    }

    // ── runInference API key guard ────────────────────────────────────────────

    @Test
    fun `runInference returns error json when api key is invalid`() {
        val binder = getField(service, "binder") as com.stridetech.coreai.ICoreAiInterface.Stub
        val response = binder.runInference("wrong-key", "Hello")
        val json = JSONObject(response)
        assertFalse(json.getBoolean("success"))
        assertTrue(
            "Error should mention API key, was: ${json.optString("error")}",
            json.optString("error").contains("API key", ignoreCase = true)
        )
    }

    @Test
    fun `runInference does not call engine when api key is invalid`() {
        val binder = getField(service, "binder") as com.stridetech.coreai.ICoreAiInterface.Stub
        binder.runInference("wrong-key", "Hello")
        coVerify(exactly = 0) { mockEngine.runInference(any()) }
    }

    @Test
    fun `runInference proceeds past key check when api key is valid and engine is ready`() {
        every { mockEngine.isReady } returns true
        coEvery { mockEngine.runInferenceStream(any()) } returns kotlinx.coroutines.flow.flowOf("Pong")

        val latch = CountDownLatch(1)
        every { mockCallbacks.finishBroadcast() } answers { latch.countDown() }

        val binder = getField(service, "binder") as com.stridetech.coreai.ICoreAiInterface.Stub
        val response = binder.runInference(VALID_KEY, "Ping")
        val json = JSONObject(response)

        // Synchronous return is a pending acknowledgement (success=true, pending=true)
        assertTrue(json.getBoolean("success"))

        // Wait for async inference to complete and deliver via onInferenceResult callback
        assertTrue("Inference callback not delivered within timeout", latch.await(5, TimeUnit.SECONDS))
        coVerify(exactly = 1) { mockEngine.runInferenceStream("Ping") }
    }

    // ── EngineLock concurrency ────────────────────────────────────────────────

    /**
     * Mathematically proves the EngineLock contract in three phases:
     *
     * Phase 1 (Rejection): Coroutine A acquires the lock and begins a 200 ms load on
     *   Dispatchers.IO. While the lock is held, Coroutine B immediately hits
     *   tryAcquireLock → CAS(false→true) fails → callbackB.onError is called.
     *
     * Phase 2 (Success): A's finally block runs releaseLock(). We assert
     *   callbackA.onModelStateChanged was invoked and isEngineLocked == false.
     *
     * Phase 3 (Release proof): Coroutine C successfully loads the same model,
     *   confirming the AtomicBoolean was reset to false and the lock is reusable.
     */
    @Test
    fun verifyEngineLockRejectsConcurrentRequests() = runTest {
        // ── Arrange ────────────────────────────────────────────────────────────
        File(tempModelsDir, "gemma-2b.bin").createNewFile()

        val callbackA = mockk<ICoreAiCallback>(relaxed = true)
        val callbackB = mockk<ICoreAiCallback>(relaxed = true)
        val callbackC = mockk<ICoreAiCallback>(relaxed = true)

        every { mockEngine.isReady } returns true
        every { mockEngine.activeModelName() } returns null

        // A's load sleeps on the IO thread — keeps the lock held long enough
        // for the test thread to call loadModel() a second time while A is running.
        coEvery { mockEngine.load(any(), any()) } coAnswers { delay(200) }

        val latchA = CountDownLatch(1)
        every { callbackA.onModelStateChanged(any(), any()) } answers { latchA.countDown() }

        val binder = getField(service, "binder") as com.stridetech.coreai.ICoreAiInterface.Stub

        // ── Phase 1: Rejection ─────────────────────────────────────────────────
        // A acquires the lock synchronously via CAS, then launches work on Dispatchers.IO.
        binder.loadModel(VALID_KEY, "gemma-2b", callbackA)

        // B is called on the same thread before A's IO coroutine can finish.
        // tryAcquireLock returns false → onError is fired immediately.
        binder.loadModel(VALID_KEY, "llama-3", callbackB)

        verify(exactly = 1) {
            callbackB.onError(match { "locked" in it.lowercase() || "active process" in it.lowercase() })
        }
        verify(exactly = 0) { callbackB.onModelStateChanged(any(), any()) }

        // ── Phase 2: Success + release ─────────────────────────────────────────
        assertTrue("Coroutine A did not complete within timeout", latchA.await(5, TimeUnit.SECONDS))

        verify(exactly = 1) { callbackA.onModelStateChanged(true, any()) }
        verify(exactly = 0) { callbackA.onError(any()) }

        val isEngineLocked = (getField(service, "isEngineLocked") as AtomicBoolean).get()
        assertFalse("isEngineLocked must be false after Coroutine A's finally block runs", isEngineLocked)

        // ── Phase 3: Lock reusability ──────────────────────────────────────────
        // C should succeed now that the lock is free, proving releaseLock() was called.
        coEvery { mockEngine.load(any(), any()) } coAnswers { /* instant */ }

        val latchC = CountDownLatch(1)
        every { callbackC.onModelStateChanged(any(), any()) } answers { latchC.countDown() }

        binder.loadModel(VALID_KEY, "gemma-2b", callbackC)

        assertTrue("Coroutine C did not complete within timeout", latchC.await(5, TimeUnit.SECONDS))
        verify(exactly = 1) { callbackC.onModelStateChanged(true, any()) }
        verify(exactly = 0) { callbackC.onError(any()) }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Replaces [RemoteCallbackList.finishBroadcast] with a version that counts down the
     * latch, allowing the test to block until the IO-thread broadcast finishes.
     */
    private fun awaitFirstBroadcast(): CountDownLatch {
        val latch = CountDownLatch(1)
        every { mockCallbacks.finishBroadcast() } answers { latch.countDown() }
        return latch
    }

    /** Injects [value] into the named field, walking up the class hierarchy. */
    private fun injectField(target: Any, fieldName: String, value: Any?) {
        findField(target.javaClass, fieldName).set(target, value)
    }

    /** Returns the value of the named field, walking up the class hierarchy. */
    private fun getField(target: Any, fieldName: String): Any? =
        findField(target.javaClass, fieldName).get(target)

    /** Calls a private no-arg method by name, walking up the class hierarchy. */
    private fun callPrivate(target: Any, methodName: String) {
        var clazz: Class<*>? = target.javaClass
        while (clazz != null) {
            try {
                val method = clazz.getDeclaredMethod(methodName)
                method.isAccessible = true
                method.invoke(target)
                return
            } catch (_: NoSuchMethodException) {
                clazz = clazz.superclass
            }
        }
        throw NoSuchMethodException(
            "Method '$methodName' not found in hierarchy of ${target.javaClass.name}"
        )
    }

    private fun findField(startClass: Class<*>, fieldName: String): java.lang.reflect.Field {
        var clazz: Class<*>? = startClass
        while (clazz != null) {
            try {
                return clazz.getDeclaredField(fieldName).also { it.isAccessible = true }
            } catch (_: NoSuchFieldException) {
                clazz = clazz.superclass
            }
        }
        throw NoSuchFieldException(
            "Field '$fieldName' not found in hierarchy of ${startClass.name}"
        )
    }
}
