package com.stridetech.coreai.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTemplateFormatterTest {

    // ── isLlama3 detection ────────────────────────────────────────────────────

    @Test
    fun `isLlama3 returns true for exact llama-3 filename`() {
        assertTrue(ChatTemplateFormatter.isLlama3("Llama-3.2-1B-Instruct-Q4_K_M"))
    }

    @Test
    fun `isLlama3 returns true for mixed case llama name`() {
        assertTrue(ChatTemplateFormatter.isLlama3("LLAMA-3-8B-INSTRUCT"))
    }

    @Test
    fun `isLlama3 returns false for unrelated model names`() {
        assertFalse(ChatTemplateFormatter.isLlama3("gemma-3-1b-q4"))
        assertFalse(ChatTemplateFormatter.isLlama3("mistral-7b-instruct"))
        assertFalse(ChatTemplateFormatter.isLlama3("phi-3-mini"))
    }

    // ── isGemma detection ─────────────────────────────────────────────────────

    @Test
    fun `isGemma returns true for gemma model names`() {
        assertTrue(ChatTemplateFormatter.isGemma("gemma-3-1b-q4"))
        assertTrue(ChatTemplateFormatter.isGemma("Gemma-2-9B-Instruct"))
        assertTrue(ChatTemplateFormatter.isGemma("GEMMA-7B"))
    }

    @Test
    fun `isGemma returns false for non-gemma model names`() {
        assertFalse(ChatTemplateFormatter.isGemma("Llama-3.2-1B-Instruct-Q4_K_M"))
        assertFalse(ChatTemplateFormatter.isGemma("mistral-7b-instruct"))
        assertFalse(ChatTemplateFormatter.isGemma("qwen2-7b"))
    }

    // ── stopSequences ─────────────────────────────────────────────────────────

    @Test
    fun `stopSequences returns eot_id and eos for llama3 models`() {
        val stops = ChatTemplateFormatter.stopSequences("Llama-3.2-1B-Instruct-Q4_K_M")
        assertTrue(stops.contains("<|eot_id|>"))
        assertTrue(stops.contains("<|end_of_text|>"))
    }

    @Test
    fun `stopSequences returns end_of_turn for gemma models`() {
        val stops = ChatTemplateFormatter.stopSequences("gemma-3-1b-q4")
        assertEquals(listOf("<end_of_turn>"), stops)
    }

    @Test
    fun `stopSequences returns im_end for chatml models`() {
        assertEquals(listOf("<|im_end|>"), ChatTemplateFormatter.stopSequences("mistral-7b"))
        assertEquals(listOf("<|im_end|>"), ChatTemplateFormatter.stopSequences("qwen2-7b"))
        assertEquals(listOf("<|im_end|>"), ChatTemplateFormatter.stopSequences("phi-3-mini"))
    }

    @Test
    fun `stopSequences returns im_end for empty model name`() {
        assertEquals(listOf("<|im_end|>"), ChatTemplateFormatter.stopSequences(""))
    }

    // ── Llama 3 template formatting ───────────────────────────────────────────

    @Test
    fun `llama3 single user turn produces correct chat template`() {
        val turns = listOf("user" to "hi")
        val result = ChatTemplateFormatter.format(turns, "Llama-3.2-1B-Instruct-Q4_K_M")

        assertEquals(
            "<|begin_of_text|>" +
                "<|start_header_id|>user<|end_header_id|>\n\nhi<|eot_id|>" +
                "<|start_header_id|>assistant<|end_header_id|>\n\n",
            result
        )
    }

    @Test
    fun `llama3 multi-turn conversation includes all turns and opens assistant header`() {
        val turns = listOf(
            "user" to "Who are you?",
            "assistant" to "I am an AI.",
            "user" to "Are you sure?"
        )
        val result = ChatTemplateFormatter.format(turns, "Llama-3.2-1B-Instruct-Q4_K_M")

        assertTrue(result.startsWith("<|begin_of_text|>"))
        assertTrue(result.contains("<|start_header_id|>user<|end_header_id|>\n\nWho are you?<|eot_id|>"))
        assertTrue(result.contains("<|start_header_id|>assistant<|end_header_id|>\n\nI am an AI.<|eot_id|>"))
        assertTrue(result.contains("<|start_header_id|>user<|end_header_id|>\n\nAre you sure?<|eot_id|>"))
        assertTrue(result.endsWith("<|start_header_id|>assistant<|end_header_id|>\n\n"))
    }

    @Test
    fun `llama3 system turn is included in template`() {
        val turns = listOf(
            "system" to "You are a helpful assistant.",
            "user" to "Hello"
        )
        val result = ChatTemplateFormatter.format(turns, "Llama-3.2-1B-Instruct-Q4_K_M")

        assertTrue(result.contains("<|start_header_id|>system<|end_header_id|>\n\nYou are a helpful assistant.<|eot_id|>"))
        assertTrue(result.contains("<|start_header_id|>user<|end_header_id|>\n\nHello<|eot_id|>"))
    }

    @Test
    fun `llama3 result does NOT contain naive User colon or Model colon prefixes`() {
        val turns = listOf("user" to "hi", "assistant" to "hello")
        val result = ChatTemplateFormatter.format(turns, "Llama-3.2-1B-Instruct-Q4_K_M")

        assertFalse("Llama 3 output must not contain 'User:'", result.contains("User:"))
        assertFalse("Llama 3 output must not contain 'Model:'", result.contains("Model:"))
    }

    // ── Gemma template formatting ─────────────────────────────────────────────

    @Test
    fun `gemma single user turn produces correct chat template`() {
        val turns = listOf("user" to "hi")
        val result = ChatTemplateFormatter.format(turns, "gemma-3-1b-q4")

        assertEquals(
            "<start_of_turn>user\nhi<end_of_turn>\n<start_of_turn>model\n",
            result
        )
    }

    @Test
    fun `gemma multi-turn conversation maps assistant role to model`() {
        val turns = listOf(
            "user" to "Who are you?",
            "assistant" to "I am an AI.",
            "user" to "Are you sure?"
        )
        val result = ChatTemplateFormatter.format(turns, "gemma-3-1b-q4")

        assertTrue(result.contains("<start_of_turn>user\nWho are you?<end_of_turn>\n"))
        assertTrue(result.contains("<start_of_turn>model\nI am an AI.<end_of_turn>\n"))
        assertTrue(result.contains("<start_of_turn>user\nAre you sure?<end_of_turn>\n"))
        assertTrue(result.endsWith("<start_of_turn>model\n"))
        assertFalse("Gemma must use 'model' not 'assistant'", result.contains("<start_of_turn>assistant"))
    }

    @Test
    fun `gemma skips system turns`() {
        val turns = listOf(
            "system" to "Be concise.",
            "user" to "Hello"
        )
        val result = ChatTemplateFormatter.format(turns, "gemma-3-1b-q4")

        assertFalse(result.contains("Be concise."))
        assertTrue(result.contains("<start_of_turn>user\nHello<end_of_turn>\n"))
    }

    @Test
    fun `gemma result does NOT contain llama3 or chatml tokens`() {
        val turns = listOf("user" to "hi")
        val result = ChatTemplateFormatter.format(turns, "Gemma-2-9B-Instruct")

        assertFalse(result.contains("<|begin_of_text|>"))
        assertFalse(result.contains("<|im_start|>"))
    }

    // ── ChatML template formatting ────────────────────────────────────────────

    @Test
    fun `chatml single user turn produces correct format`() {
        val turns = listOf("user" to "hi")
        val result = ChatTemplateFormatter.format(turns, "mistral-7b-instruct")

        assertEquals(
            "<|im_start|>user\nhi<|im_end|>\n<|im_start|>assistant\n",
            result
        )
    }

    @Test
    fun `chatml multi-turn conversation includes all turns`() {
        val turns = listOf(
            "user" to "Who are you?",
            "assistant" to "I am an AI."
        )
        val result = ChatTemplateFormatter.format(turns, "qwen2-7b")

        assertEquals(
            "<|im_start|>user\nWho are you?<|im_end|>\n" +
                "<|im_start|>assistant\nI am an AI.<|im_end|>\n" +
                "<|im_start|>assistant\n",
            result
        )
    }

    @Test
    fun `chatml includes system turn`() {
        val turns = listOf(
            "system" to "Be concise.",
            "user" to "Hello"
        )
        val result = ChatTemplateFormatter.format(turns, "qwen2-7b")

        assertTrue(result.contains("<|im_start|>system\nBe concise.<|im_end|>\n"))
        assertTrue(result.contains("<|im_start|>user\nHello<|im_end|>\n"))
    }

    @Test
    fun `chatml used when model name is empty string`() {
        val turns = listOf("user" to "test")
        val result = ChatTemplateFormatter.format(turns, "")

        assertEquals("<|im_start|>user\ntest<|im_end|>\n<|im_start|>assistant\n", result)
    }

    @Test
    fun `chatml result does NOT contain naive User colon or Model colon prefixes`() {
        val turns = listOf("user" to "hi", "assistant" to "hello")
        val result = ChatTemplateFormatter.format(turns, "qwen2-7b")

        assertFalse(result.contains("User:"))
        assertFalse(result.contains("Model:"))
    }

    // ── Catalog template map ─────────────────────────────────────────────────

    @Test
    fun `catalog id gemma-3-1b-q4 resolves to gemma template`() {
        val result = ChatTemplateFormatter.format(listOf("user" to "hi"), "gemma-3-1b-q4")
        assertTrue(result.contains("<start_of_turn>user\n"))
    }

    @Test
    fun `catalog id gemma-3-4b-q4 resolves to gemma template`() {
        val result = ChatTemplateFormatter.format(listOf("user" to "hi"), "gemma-3-4b-q4")
        assertTrue(result.contains("<start_of_turn>user\n"))
    }

    @Test
    fun `catalog id llama-3_2-1b-instruct resolves to llama3 template`() {
        val result = ChatTemplateFormatter.format(listOf("user" to "hi"), "llama-3.2-1b-instruct")
        assertTrue(result.contains("<|begin_of_text|>"))
        assertTrue(result.contains("<|start_header_id|>user<|end_header_id|>"))
    }

    @Test
    fun `catalog id phi-3_5-mini-q4 resolves to chatml template`() {
        val result = ChatTemplateFormatter.format(listOf("user" to "hi"), "phi-3.5-mini-q4")
        assertTrue(result.contains("<|im_start|>user\n"))
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Test
    fun `empty turn list produces only assistant header for llama3`() {
        val result = ChatTemplateFormatter.format(emptyList(), "Llama-3.2-1B")
        assertEquals(
            "<|begin_of_text|><|start_header_id|>assistant<|end_header_id|>\n\n",
            result
        )
    }

    @Test
    fun `empty turn list produces only model header for gemma`() {
        val result = ChatTemplateFormatter.format(emptyList(), "gemma-3-1b")
        assertEquals("<start_of_turn>model\n", result)
    }

    @Test
    fun `empty turn list produces only assistant header for chatml`() {
        val result = ChatTemplateFormatter.format(emptyList(), "qwen2-7b")
        assertEquals("<|im_start|>assistant\n", result)
    }
}
