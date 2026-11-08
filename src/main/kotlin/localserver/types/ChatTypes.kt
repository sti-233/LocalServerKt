package localserver.types

import kotlinx.serialization.Serializable

@Serializable
data class User(
    val ip: String,
    var name: String? = null
)

/**
 * 被回复的那条消息的快照。
 *
 * 存的是**内容副本**而不是指针：原消息可能被撤回、可能年头太久被清理，
 * 那时回复块仍然要能显示"当时回复的是什么"。所以 name/time/text 三样都留一份，
 * [text] 只存前 [TEXT_LIMIT] 个字符（回复块本来就只显示一两行摘要）。
 */
@Serializable
data class ReplyRef(
    val name: String,
    val time: String,
    val text: String
) {
    companion object {
        /** 引用摘要的最大长度：够认出来是哪条，又不至于把存档撑大 */
        const val TEXT_LIMIT = 200
    }
}

/**
 * 一条聊天消息（存档格式）。
 *
 * [reply] 是后加的字段且有默认值：旧存档里没有它，读出来就是 null
 * （[localserver.utils.Util.historyJson] 也开了 ignoreUnknownKeys，双向都不炸）。
 * 序列化时 encodeDefaults 为 false，reply 为空就**不写进文件**，
 * 所以不回复的消息存档体积与改造前完全一致。
 */
@Serializable
data class Message(
    val name: String,
    val time: String,
    val text: String,
    val reply: ReplyRef? = null
)

@Serializable
data class Content(
    val type: String,
    val name: String,
    val time: String,
    val text: String,
    val sendTo: String? = null,
    val reply: ReplyRef? = null
)
