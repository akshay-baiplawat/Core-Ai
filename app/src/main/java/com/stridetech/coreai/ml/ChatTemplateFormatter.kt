package com.stridetech.coreai.ml

private val LLAMA3_TEMPLATE = ChatTemplate(
    bosToken = "<|begin_of_text|>",
    systemPromptPrefix = "<|start_header_id|>system<|end_header_id|>\n\n",
    systemPromptSuffix = "<|eot_id|>",
    userMessagePrefix = "<|start_header_id|>user<|end_header_id|>\n\n",
    userMessageSuffix = "<|eot_id|>",
    assistantMessagePrefix = "<|start_header_id|>assistant<|end_header_id|>\n\n",
    assistantMessageSuffix = "<|eot_id|>",
    stopToken = "<|eot_id|>",
    stopSequences = listOf("<|end_of_text|>")
)

private val GEMMA_TEMPLATE = ChatTemplate(
    userMessagePrefix = "<start_of_turn>user\n",
    userMessageSuffix = "<end_of_turn>\n",
    assistantMessagePrefix = "<start_of_turn>model\n",
    assistantMessageSuffix = "<end_of_turn>\n",
    stopToken = "<end_of_turn>"
)

private val CHATML_TEMPLATE = ChatTemplate(
    systemPromptPrefix = "<|im_start|>system\n",
    systemPromptSuffix = "<|im_end|>\n",
    userMessagePrefix = "<|im_start|>user\n",
    userMessageSuffix = "<|im_end|>\n",
    assistantMessagePrefix = "<|im_start|>assistant\n",
    assistantMessageSuffix = "<|im_end|>\n",
    stopToken = "<|im_end|>"
)

// Explicit catalog ID → template assignments. Checked before name heuristics so non-descriptive
// or future catalog IDs always get the correct template regardless of naming convention.
private val CATALOG_TEMPLATES: Map<String, ChatTemplate> = mapOf(
    "gemma-3-1b-q4"         to GEMMA_TEMPLATE,
    "gemma-3-4b-q4"         to GEMMA_TEMPLATE,
    "llama-3.2-1b-instruct" to LLAMA3_TEMPLATE,
    "phi-3.5-mini-q4"       to CHATML_TEMPLATE,
)

internal object ChatTemplateFormatter {

    fun isLlama3(modelName: String): Boolean =
        modelName.contains("llama", ignoreCase = true)

    fun isGemma(modelName: String): Boolean =
        modelName.contains("gemma", ignoreCase = true)

    fun templateFor(modelName: String): ChatTemplate =
        CATALOG_TEMPLATES[modelName] ?: when {
            isLlama3(modelName) -> LLAMA3_TEMPLATE
            isGemma(modelName) -> GEMMA_TEMPLATE
            else -> CHATML_TEMPLATE
        }

    // Backward-compat delegators — existing call sites require no changes.
    fun stopSequences(modelName: String): List<String> =
        templateFor(modelName).effectiveStopSequences

    fun format(turns: List<Pair<String, String>>, modelName: String): String =
        templateFor(modelName).format(turns)
}
