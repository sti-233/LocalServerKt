package localserver.utils

import localserver.types.AiMessage
import localserver.types.AiToolCall

import kotlinx.serialization.json.*

/**
 * AI 消息在"模型格式"（JsonObject）与"存档格式"（AiMessage）之间的转换。
 *
 * 模型格式沿用上游 OpenAI 风格：
 *   { role, content, reasoning_content?, tool_calls?, tool_call_id?, interrupted?, time? }
 * 存档格式把 tool_calls 拍平成 AiToolCall 列表，便于 @Serializable 落盘。
 */

// 取 JSON 字符串字段
private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/**
 * 模型格式 → 存档格式。
 * 缺 time 时用 fallbackTime 补齐（例如从会话的 updatedAt 兜底）。
 */
fun JsonObject.toAiMessage(fallbackTime: String = ""): AiMessage {
    val calls = (this["tool_calls"] as? JsonArray)?.mapNotNull { raw ->
        val o = raw as? JsonObject ?: return@mapNotNull null
        val fn = o["function"]?.jsonObject
        AiToolCall(
            id = o.str("id").orEmpty(),
            type = o.str("type") ?: "function",
            name = fn?.str("name").orEmpty(),
            arguments = fn?.str("arguments").orEmpty()
        )
    }
    return AiMessage(
        role = str("role") ?: "user",
        content = str("content") ?: "",
        reasoning = str("reasoning_content"),
        toolCallId = str("tool_call_id"),
        toolCalls = calls?.takeIf { it.isNotEmpty() },
        interrupted = (this["interrupted"] as? JsonPrimitive)?.booleanOrNull ?: false,
        time = str("time")?.takeIf { it.isNotBlank() } ?: fallbackTime
    )
}

/**
 * 存档格式 → 模型格式（同时也是前端展示格式）。
 * 发往模型时由 toModelMessages() 剔除 reasoning_content / interrupted / time。
 */
fun AiMessage.toModelJson(): JsonObject = buildJsonObject {
    put("role", role)
    put("content", content)
    reasoning?.takeIf { it.isNotBlank() }?.let { put("reasoning_content", it) }
    toolCallId?.takeIf { it.isNotBlank() }?.let { put("tool_call_id", it) }
    toolCalls?.takeIf { it.isNotEmpty() }?.let { calls ->
        put("tool_calls", JsonArray(calls.map { c ->
            buildJsonObject {
                put("id", c.id)
                put("type", c.type)
                put("function", buildJsonObject {
                    put("name", c.name)
                    put("arguments", c.arguments)
                })
            }
        }))
    }
    if (interrupted) put("interrupted", JsonPrimitive(true))
    put("time", time)
}
