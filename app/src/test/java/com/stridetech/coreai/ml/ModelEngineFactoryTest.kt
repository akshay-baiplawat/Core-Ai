package com.stridetech.coreai.ml

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ModelEngineFactoryTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        // cacheDir is accessed by LiteRtModelEngine constructor
        val fakeCache = File(System.getProperty("java.io.tmpdir")!!)
        every { context.cacheDir } returns fakeCache
    }

    // ── LiteRT routing ────────────────────────────────────────────────────────

    @Test
    fun `litertlm extension routes to LiteRtModelEngine`() {
        val engine = ModelEngineFactory.create("/models/gemma.litertlm", context)
        assertTrue(
            "Expected LiteRtModelEngine but got ${engine::class.simpleName}",
            engine is LiteRtModelEngine
        )
    }

    @Test
    fun `litertlm extension is case-insensitive`() {
        val engine = ModelEngineFactory.create("/models/gemma.LITERTLM", context)
        assertTrue(engine is LiteRtModelEngine)
    }

    // ── MediaPipe routing ──────────────────────────────────────────────────────

    @Test
    fun `bin extension routes to MediaPipeModelEngine`() {
        val engine = ModelEngineFactory.create("/models/gemma.bin", context)
        assertTrue(
            "Expected MediaPipeModelEngine but got ${engine::class.simpleName}",
            engine is MediaPipeModelEngine
        )
    }

    @Test
    fun `task extension routes to MediaPipeModelEngine`() {
        val engine = ModelEngineFactory.create("/models/deepseek.task", context)
        assertTrue(engine is MediaPipeModelEngine)
    }

    @Test
    fun `bin extension is case-insensitive`() {
        val engine = ModelEngineFactory.create("/models/gemma.BIN", context)
        assertTrue(engine is MediaPipeModelEngine)
    }

    // ── GGUF routing ──────────────────────────────────────────────────────────

    @Test
    fun `gguf extension routes to GgufModelEngine`() {
        val engine = ModelEngineFactory.create("/models/model.gguf", context)
        assertTrue(
            "Expected GgufModelEngine but got ${engine::class.simpleName}",
            engine is GgufModelEngine
        )
    }

    @Test
    fun `gguf extension is case-insensitive`() {
        val engine = ModelEngineFactory.create("/models/model.GGUF", context)
        assertTrue(engine is GgufModelEngine)
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Test
    fun `no extension throws IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            ModelEngineFactory.create("/models/modelfile", context)
        }
    }

    @Test
    fun `empty string path throws IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            ModelEngineFactory.create("", context)
        }
    }

    @Test
    fun `error message contains unsupported extension`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            ModelEngineFactory.create("/models/model.onnx", context)
        }
        assertTrue(
            "Error should mention the bad extension",
            ex.message?.contains("onnx") == true
        )
    }
}
