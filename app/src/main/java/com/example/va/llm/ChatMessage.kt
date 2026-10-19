package com.example.va.llm

data class ChatMessage(
    val role: Role,
    val content: String,
)

enum class Role {
    System,
    User,
    Assistant,
}
