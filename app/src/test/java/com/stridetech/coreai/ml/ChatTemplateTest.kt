package com.stridetech.coreai.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTemplateTest {

    // ─── effectiveStopSequences ───────────────────────────────────────────────

    @Test
    fun `effectiveStopSequences returns stopToken only when no extra sequences`() {
        val template = ChatTemplate(stopToken = "<|eot_id|>")
        assertEquals(listOf("<|eot_id|>"), template.effectiveStopSequences)
    }

    @Test
    fun `effectiveStopSequences returns extra sequences only when stopToken is blank`() {
        val template = ChatTemplate(stopSequences = listOf("<|end_of_text|>", "<eos>"))
        assertEquals(listOf("<|end_of_text|>", "<eos>"), template.effectiveStopSequences)
    }

    @Test
    fun `effectiveStopSequences returns union when both are set`() {
        val template = ChatTemplate(stopToken = "<|eot_id|>", stopSequences = listOf("<|end_of_text|>"))
        assertEquals(listOf("<|eot_id|>", "<|end_of_text|>"), template.effectiveStopSequences)
    }

    @Test
    fun `effectiveStopSequences is empty when nothing is set`() {
        assertTrue(ChatTemplate().effectiveStopSequences.isEmpty())
    }

    @Test
    fun `effectiveStopSequences filters out blank strings`() {
        val template = ChatTemplate(stopToken = "", stopSequences = listOf("", "<eos>"))
        assertEquals(listOf("<eos>"), template.effectiveStopSequences)
    }

    // ─── format — single turn ─────────────────────────────────────────────────

    @Test
    fun `format single user turn wraps with user prefix and suffix and opens assistant turn`() {
        val template = ChatTemplate(
            userMessagePrefix = "[USER] ",
            userMessageSuffix = " [/USER]\n",
            assistantMessagePrefix = "[ASST] "
        )
        val result = template.format(listOf("user" to "hello"))
        assertEquals("[USER] hello [/USER]\n[ASST] ", result)
    }

    @Test
    fun `format prepends bosToken when set`() {
        val template = ChatTemplate(
            bosToken = "<BOS>",
            userMessagePrefix = "U:",
            userMessageSuffix = "\n",
            assistantMessagePrefix = "A:"
        )
        val result = template.format(listOf("user" to "hi"))
        assertEquals("<BOS>U:hi\nA:", result)
    }

    @Test
    fun `format does not prepend bosToken when empty`() {
        val template = ChatTemplate(userMessagePrefix = "U:", assistantMessagePrefix = "A:")
        assertEquals("U:hiA:", template.format(listOf("user" to "hi")))
    }

    // ─── format — multi-turn ─────────────────────────────────────────────────

    @Test
    fun `format multi-turn interleaves user and assistant correctly`() {
        val template = ChatTemplate(
            userMessagePrefix = "[U]",
            userMessageSuffix = "[/U]",
            assistantMessagePrefix = "[A]",
            assistantMessageSuffix = "[/A]"
        )
        val turns = listOf("user" to "hello", "assistant" to "world")
        assertEquals("[U]hello[/U][A]world[/A][A]", template.format(turns))
    }

    @Test
    fun `format ends with open assistant prefix to solicit next response`() {
        val template = ChatTemplate(userMessagePrefix = "Q:", assistantMessagePrefix = "A:")
        assertTrue(template.format(listOf("user" to "hi")).endsWith("A:"))
    }

    // ─── format — system turn ────────────────────────────────────────────────

    @Test
    fun `format includes system turn when both system prefixes are set`() {
        val template = ChatTemplate(
            systemPromptPrefix = "[SYS]",
            systemPromptSuffix = "[/SYS]",
            userMessagePrefix = "[U]",
            assistantMessagePrefix = "[A]"
        )
        val result = template.format(listOf("system" to "Be helpful", "user" to "hi"))
        assertEquals("[SYS]Be helpful[/SYS][U]hi[A]", result)
    }

    @Test
    fun `format skips system turn when both system prefixes are empty`() {
        val template = ChatTemplate(userMessagePrefix = "[U]", assistantMessagePrefix = "[A]")
        val result = template.format(listOf("system" to "ignored", "user" to "hi"))
        assertEquals("[U]hi[A]", result)
    }

    @Test
    fun `format includes system turn when only systemPromptPrefix is set`() {
        val template = ChatTemplate(
            systemPromptPrefix = "[SYS]",
            userMessagePrefix = "[U]",
            assistantMessagePrefix = "[A]"
        )
        val result = template.format(listOf("system" to "tip", "user" to "go"))
        assertEquals("[SYS]tip[U]go[A]", result)
    }

    // ─── format — edge cases ─────────────────────────────────────────────────

    @Test
    fun `format empty turn list produces bosToken and open assistant prefix only`() {
        val template = ChatTemplate(bosToken = "<BOS>", assistantMessagePrefix = "A:")
        assertEquals("<BOS>A:", template.format(emptyList()))
    }

    @Test
    fun `format empty turn list on bare template produces empty string`() {
        assertEquals("", ChatTemplate().format(emptyList()))
    }

    @Test
    fun `format ignores unknown role silently`() {
        val template = ChatTemplate(userMessagePrefix = "U:", assistantMessagePrefix = "A:")
        assertEquals("U:hiA:", template.format(listOf("user" to "hi", "tool" to "data")))
    }

    // ─── fromJson ────────────────────────────────────────────────────────────

    @Test
    fun `fromJson parses all eight fields correctly`() {
        val json = """
            {
              "bosToken": "<bos>",
              "systemPromptPrefix": "[SYS]",
              "systemPromptSuffix": "[/SYS]",
              "userMessagePrefix": "[U]",
              "userMessageSuffix": "[/U]",
              "assistantMessagePrefix": "[A]",
              "assistantMessageSuffix": "[/A]",
              "stopToken": "<stop>"
            }
        """.trimIndent()
        val t = ChatTemplate.fromJson(json)
        assertEquals("<bos>", t.bosToken)
        assertEquals("[SYS]", t.systemPromptPrefix)
        assertEquals("[/SYS]", t.systemPromptSuffix)
        assertEquals("[U]", t.userMessagePrefix)
        assertEquals("[/U]", t.userMessageSuffix)
        assertEquals("[A]", t.assistantMessagePrefix)
        assertEquals("[/A]", t.assistantMessageSuffix)
        assertEquals("<stop>", t.stopToken)
    }

    @Test
    fun `fromJson sets all missing fields to empty string`() {
        val t = ChatTemplate.fromJson("{}")
        assertEquals("", t.bosToken)
        assertEquals("", t.systemPromptPrefix)
        assertEquals("", t.systemPromptSuffix)
        assertEquals("", t.userMessagePrefix)
        assertEquals("", t.userMessageSuffix)
        assertEquals("", t.assistantMessagePrefix)
        assertEquals("", t.assistantMessageSuffix)
        assertEquals("", t.stopToken)
    }

    @Test
    fun `fromJson partial JSON leaves unprovided fields as empty string`() {
        val json = """{"userMessagePrefix":"U:","stopToken":"<stop>"}"""
        val t = ChatTemplate.fromJson(json)
        assertEquals("U:", t.userMessagePrefix)
        assertEquals("<stop>", t.stopToken)
        assertEquals("", t.bosToken)
        assertEquals("", t.assistantMessagePrefix)
    }

    @Test
    fun `fromJson result used in format produces correct output`() {
        val json = """
            {
              "userMessagePrefix": "Q: ",
              "userMessageSuffix": "\n",
              "assistantMessagePrefix": "A: "
            }
        """.trimIndent()
        val t = ChatTemplate.fromJson(json)
        assertEquals("Q: hello\nA: ", t.format(listOf("user" to "hello")))
    }
}
