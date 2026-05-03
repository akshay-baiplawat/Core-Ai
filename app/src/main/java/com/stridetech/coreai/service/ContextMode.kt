package com.stridetech.coreai.service

enum class ContextMode {
    /** Client sends full conversation history as the prompt each call. Service is stateless. */
    FULL_PROMPT,

    /** Service tracks history per caller UID. Client sends only the latest user turn. */
    PER_CLIENT;

    companion object {
        fun fromString(value: String): ContextMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: FULL_PROMPT
    }
}
