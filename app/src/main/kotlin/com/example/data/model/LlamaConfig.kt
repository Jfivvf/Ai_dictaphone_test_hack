package com.example.data.model

data class LlamaConfig(
    val modelPath: String = "llama-3.2-3b-instruct-q4.gguf",
    val contextSize: Int = 2048,
    val threads: Int = 4,
    val batchSize: Int = 256,
    val microBatchSize: Int = 128,
    val maxTokens: Int = 512,
    val temperature: Float = 0.7f,
    val topK: Int = 20,
    val topP: Float = 0.8f,
    val systemPrompt: String = "Ты — умный ассистент диктофона. Анализируй стенограмму, выделяй суть, задачи и формируй четкие структурированные выводы на русском языке."
)

data class ActionItem(
    val id: String,
    val text: String,
    val isCompleted: Boolean = false,
    val assignee: String? = null
)

data class AiChatMessage(
    val id: String,
    val isUser: Boolean,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)
