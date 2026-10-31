package localserver.utils

import localserver.types.AiMessage
import localserver.types.AiToolCall
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AiMessages 的单测：模型格式（JsonObject）与存档格式（AiMessage）的互转。
 */
class AiMessagesTest {

    @Test
    fun modelJsonToAiMessageKeepsCoreFields() {
        val json = buildJsonObject {
            put("role", "assistant")
            put("content", "正文")
            put("reasoning_content", "思考")
            put("interrupted", JsonPrimitive(true))
            put("time", "2026-01-02 03:04:05")
        }
        val msg = json.toAiMessage()
        assertEquals("assistant", msg.role)
        assertEquals("正文", msg.content)
        assertEquals("思考", msg.reasoning)
        assertTrue(msg.interrupted)
        assertEquals("2026-01-02 03:04:05", msg.time)
    }

    @Test
    fun modelJsonToAiMessageUsesFallbackTime() {
        val json = buildJsonObject {
            put("role", "user")
            put("content", "你好")
        }
        assertEquals("2026-01-02 03:04:05", json.toAiMessage("2026-01-02 03:04:05").time)
    }

    @Test
    fun toolCallsAreFlattenedAndRestored() {
        val json = buildJsonObject {
            put("role", "assistant")
            put("content", "")
            put("tool_calls", buildJsonArray {
                add(buildJsonObject {
                    put("id", "call_1")
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", "get_time")
                        put("arguments", "{}")
                    })
                })
            })
        }
        val msg = json.toAiMessage()
        assertNotNull(msg.toolCalls)
        assertEquals(1, msg.toolCalls!!.size)
        assertEquals("get_time", msg.toolCalls!!.first().name)
        assertEquals("call_1", msg.toolCalls!!.first().id)

        // 再转回模型格式，结构应保持
        val back = msg.toModelJson()
        val calls = back["tool_calls"]!!.jsonArray
        assertEquals(1, calls.size)
        assertEquals("get_time", calls[0].jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun toolRoleKeepsToolCallId() {
        val json = buildJsonObject {
            put("role", "tool")
            put("content", "结果")
            put("tool_call_id", "call_1")
        }
        val msg = json.toAiMessage()
        assertEquals("call_1", msg.toolCallId)
        assertEquals("结果", msg.content)
        assertEquals("call_1", msg.toModelJson()["tool_call_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun aiMessageToModelJsonOmitsBlankOptionalFields() {
        val msg = AiMessage(role = "user", content = "你好")
        val json = msg.toModelJson()
        assertEquals("user", json["role"]!!.jsonPrimitive.content)
        assertEquals("你好", json["content"]!!.jsonPrimitive.content)
        assertNull(json["reasoning_content"])
        assertNull(json["tool_calls"])
        assertNull(json["interrupted"])
    }

    @Test
    fun emptyToolCallListIsOmitted() {
        val msg = AiMessage(role = "assistant", content = "x", toolCalls = emptyList())
        assertNull(msg.toModelJson()["tool_calls"])
    }

    @Test
    fun roundTripKeepsContent() {
        val original = AiMessage(
            role = "assistant",
            content = "回答",
            reasoning = "思考过程",
            toolCalls = listOf(AiToolCall(id = "c1", name = "web_search", arguments = "{\"q\":\"x\"}")),
            time = "2026-01-02 03:04:05"
        )
        val back = original.toModelJson().toAiMessage()
        assertEquals(original.role, back.role)
        assertEquals(original.content, back.content)
        assertEquals(original.reasoning, back.reasoning)
        assertEquals(original.toolCalls!!.first().name, back.toolCalls!!.first().name)
        assertEquals(original.time, back.time)
    }
}
