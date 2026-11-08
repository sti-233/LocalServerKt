package localserver.module

import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * WS 测试的读帧辅助：跳过 `presence`，只返回业务帧。
 *
 * 为什么要单独抽出来：服务端在**每次连接建立/断开时**都会广播一帧在线名单，
 * 于是"连上之后收到的第一帧"不再必然是 send/err —— 直接在测试里 `incoming.receive()`
 * 会读到 presence，断言莫名其妙地变成「expected send but was presence」。
 *
 * 这类帧与绝大多数用例想验证的东西无关（消息落盘、错误提示、撤回窗口），
 * 所以统一在这里滤掉；**专门测 presence 的用例不要用它**，那里要原样看帧。
 */
suspend fun DefaultClientWebSocketSession.nextBusinessFrame(timeoutMs: Long = 5000): String {
    while (true) {
        val raw = withTimeout(timeoutMs) { (incoming.receive() as Frame.Text).readText() }
        val type = runCatching {
            Json.parseToJsonElement(raw).jsonObject["type"]?.jsonPrimitive?.content
        }.getOrNull()
        if (type == "presence") continue
        return raw
    }
}
