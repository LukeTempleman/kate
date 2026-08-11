package tech.gonxt.kate.core

enum class Role { USER, KATE }

data class ChatMessage(
    val id: Long,
    val role: Role,
    val text: String,
    val streaming: Boolean = false,
    val modelUsed: String? = null,
    val latencyMs: Long? = null,
)
