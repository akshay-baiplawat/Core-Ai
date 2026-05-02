package com.stridetech.coreai.ui.playground

import java.util.UUID

enum class MessageRole { USER, MODEL, SYSTEM }

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val content: String,
    val latencyMs: Long = 0L
)
