package com.stridetech.coreai.ml

import org.json.JSONObject

/**
 * Building blocks for an LLM prompt template.
 *
 * [format] assembles a turn list into a ready-to-send prompt string:
 *   bosToken  +  (prefix + content + suffix) per turn  +  assistantMessagePrefix (open turn)
 *
 * System turns are skipped when both [systemPromptPrefix] and [systemPromptSuffix] are empty
 * (e.g. Gemma does not support system instructions).
 *
 * [effectiveStopSequences] is the union of [stopToken] and any extra [stopSequences], filtered
 * to non-blank values. Pass this to the inference engine so it halts at the right boundary.
 */
data class ChatTemplate(
    val bosToken: String = "",
    val systemPromptPrefix: String = "",
    val systemPromptSuffix: String = "",
    val userMessagePrefix: String = "",
    val userMessageSuffix: String = "",
    val assistantMessagePrefix: String = "",
    val assistantMessageSuffix: String = "",
    val stopToken: String = "",
    val stopSequences: List<String> = emptyList()
) {

    val effectiveStopSequences: List<String>
        get() = (listOf(stopToken) + stopSequences).filter { it.isNotEmpty() }

    fun format(turns: List<Pair<String, String>>): String {
        val sb = StringBuilder()
        if (bosToken.isNotEmpty()) sb.append(bosToken)
        for ((role, content) in turns) {
            when (role) {
                "system" -> {
                    if (systemPromptPrefix.isEmpty() && systemPromptSuffix.isEmpty()) continue
                    sb.append(systemPromptPrefix).append(content).append(systemPromptSuffix)
                }
                "user" -> sb.append(userMessagePrefix).append(content).append(userMessageSuffix)
                "assistant" -> sb.append(assistantMessagePrefix).append(content).append(assistantMessageSuffix)
            }
        }
        sb.append(assistantMessagePrefix)
        return sb.toString()
    }

    companion object {
        fun fromJson(json: String): ChatTemplate {
            val obj = JSONObject(json)
            return ChatTemplate(
                bosToken = obj.optString("bosToken", ""),
                systemPromptPrefix = obj.optString("systemPromptPrefix", ""),
                systemPromptSuffix = obj.optString("systemPromptSuffix", ""),
                userMessagePrefix = obj.optString("userMessagePrefix", ""),
                userMessageSuffix = obj.optString("userMessageSuffix", ""),
                assistantMessagePrefix = obj.optString("assistantMessagePrefix", ""),
                assistantMessageSuffix = obj.optString("assistantMessageSuffix", ""),
                stopToken = obj.optString("stopToken", "")
            )
        }
    }
}
