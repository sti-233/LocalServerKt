package localserver.types

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * AI 对话消息（一条历史记录里的单条消息）。
 *
 * 与上游模型交互时只保留 role/content/tool_calls/tool_call_id，
 * reasoning（思考过程）与 interrupted 仅用于前端展示与存档。
 */
@Serializable
data class AiMessage(
    /** user / assistant / tool */
    val role: String,
    /** 正文 */
    val content: String = "",
    /** 思考过程（上游 reasoning_content） */
    val reasoning: String? = null,
    /** role=tool 时对应的 tool_call_id */
    val toolCallId: String? = null,
    /** assistant 的工具调用列表 */
    val toolCalls: List<AiToolCall>? = null,
    /** 回答是否被中断（客户端断开/上游异常） */
    val interrupted: Boolean = false,
    /** 消息发出时间，格式 yyyy-MM-dd HH:mm:ss */
    val time: String = ""
)

/** 一次工具调用（上游 delta.tool_calls 聚合后的结果） */
@Serializable
data class AiToolCall(
    val id: String = "",
    val type: String = "function",
    /** 工具名 */
    val name: String = "",
    /** 工具参数（JSON 字符串） */
    val arguments: String = ""
)

/**
 * 一个会话（一条历史记录）的完整存档，落盘为
 * cache/ai/<ip 对应的用户名>/<id>.json
 *
 * 匿名会话（anonymous=true）不落盘，只活在服务端内存里，前端离开页面即销毁。
 */
@Serializable
data class AiConversation(
    /** 会话 id，同时是文件名 */
    val id: String,
    /** 会话名：默认"新对话"，用户可自定义 */
    var name: String,
    /** 用户是否自定义过名称（自定义后不再自动改名） */
    var custom: Boolean = false,
    /** 创建时间 */
    val createdAt: String = "",
    /** 最后更新时间 */
    val updatedAt: String = "",
    /** 消息列表 */
    var messages: List<AiMessage> = emptyList(),
    /** 匿名会话：只在内存里，不落盘；离开页面销毁 */
    var anonymous: Boolean = false
)

/** 会话列表项（侧边栏用）：不含消息正文 */
@Serializable
data class AiConversationSummary(
    val id: String,
    val name: String,
    /** 最后一条消息发出时间（列表排序依据） */
    val lastTime: String,
    val createdAt: String,
    val updatedAt: String,
    /** 消息条数 */
    val count: Int,
    /** 最后一条消息摘要 */
    val preview: String,
    /** 匿名会话（不落盘，仅存在于内存） */
    val anonymous: Boolean = false
)

/** 会话列表响应：侧边栏数据 + 当前打开的会话 id */
@Serializable
data class AiConversationListResponse(
    val items: List<AiConversationSummary> = emptyList(),
    /** 当前打开的会话 id；空串表示无 */
    val active: String = ""
)

/** 单个会话详情响应（含消息正文） */
@Serializable
data class AiConversationResponse(
    val id: String,
    val name: String,
    val custom: Boolean = false,
    val createdAt: String = "",
    val updatedAt: String = "",
    val messages: List<JsonObject> = emptyList(),
    /** 匿名会话（不落盘，离开页面销毁） */
    val anonymous: Boolean = false,
    /** 只读查看（通过分享链接打开时为 true，前端隐藏输入框与全部写操作） */
    val readonly: Boolean = false
)

/** 通用错误响应 */
@Serializable
data class AiErrorResponse(
    val error: String
)

/**
 * 一条分享记录：把某个会话只读外链出去。
 *
 * 令牌随机生成、不可猜测；会话内容在打开链接时实时读取，
 * 因此原会话继续更新、或所有者撤销分享，链接立即反映变化。
 */
@Serializable
data class AiShare(
    /** 分享令牌（URL 里的 t 参数） */
    val token: String,
    /** 会话所属用户名（cache/ai 下的目录名） */
    val owner: String,
    /** 会话 id */
    val convId: String,
    /** 创建时间 */
    val createdAt: String = ""
)

/** 全部分享记录的落盘容器 */
@Serializable
data class AiShareStore(
    val items: List<AiShare> = emptyList()
)

/** 分享创建/查询响应 */
@Serializable
data class AiShareResponse(
    val token: String,
    /** 可分享到局域网的完整地址 */
    val url: String,
    val convId: String
)
