package localserver.types.bilibili

import kotlinx.serialization.Serializable

// ---------- 空间动态 /x/polymer/web-dynamic/desktop/v1/feed/space ----------
//
// 注意该接口（desktop 变体）的 modules 是**数组**（不是 Web 非 desktop 变体的对象），
// 每项带 module_type 作为判别字段，因此用 JsonElement 承接后按需解析。

@Serializable
data class DynFeed(
    val code: Int = 0,
    val message: String = "",
    val data: DynFeedData? = null
)

@Serializable
data class DynFeedData(
    val has_more: Boolean = false,
    val offset: String = "",
    val update_num: Long = 0,
    val items: List<DynItem> = emptyList()
)

@Serializable
data class DynItem(
    val id_str: String = "",
    val type: String = "",
    val basic: DynBasic? = null,
    val modules: List<DynModule> = emptyList()
)

@Serializable
data class DynBasic(
    val rid_str: String = "",
    val rtype: Int = 0
)

/**
 * 动态的模块。四个模块共用一个类：哪个字段非空就说明是哪一种模块，
 * 比按 module_type 做多态反序列化更省事，且接口字段变动时更耐操。
 */
@Serializable
data class DynModule(
    val module_type: String = "",
    val module_author: DynAuthor? = null,
    val module_desc: DynDesc? = null,
    val module_dynamic: DynDynamic? = null,
    val module_stat: DynStat? = null
)

@Serializable
data class DynAuthor(
    val pub_ts: Long = 0,
    val pub_text: String = "",
    val is_top: Boolean = false,
    val user: DynUser? = null
)

@Serializable
data class DynUser(
    val mid: Long = 0,
    val name: String = "",
    val face: String = "",
    val official: OfficialVerify? = null
)

@Serializable
data class DynDesc(
    // 该字段基本恒等于 rich_text_nodes 拼接结果，优先用它
    val text: String = "",
    val rich_text_nodes: List<DynRichText> = emptyList()
)

@Serializable
data class DynRichText(
    val type: String = "",
    val text: String = "",
    val orig_text: String = "",
    val jump_url: String = ""
) {
    /** 展示用文本：优先 text，空则用 orig_text */
    val display: String get() = text.ifBlank { orig_text }
}

@Serializable
data class DynDynamic(
    val type: String = "",
    // 带图动态（图文）
    val dyn_draw: DynDraw? = null,
    // 投稿视频动态
    val dyn_archive: DynArchive? = null,
    // 转发动态（内含被转发的原始动态）
    val dyn_forward: DynForward? = null
)

@Serializable
data class DynDraw(
    val id: Long = 0,
    val items: List<DynDrawItem> = emptyList()
)

@Serializable
data class DynDrawItem(
    val src: String = "",
    // 宽高在部分图片上可能缺失
    val width: Int? = null,
    val height: Int? = null
)

@Serializable
data class DynArchive(
    val aid: String = "",
    val bvid: String = "",
    val title: String = "",
    // B 站对无简介投稿返回 null 而不是空串，必须可空（默认值不救 null）
    val desc: String? = null,
    val cover: String = "",
    // 时长文案可能缺失（尤其是转发过来的）
    val duration_text: String = "",
    val stat: DynArchiveStat? = null
)

@Serializable
data class DynArchiveStat(
    // 这些统计值在接口里是字符串（如 "68.4万"），且可能为 null
    val play: String? = null,
    val danmaku: String? = null,
    val like: String? = null
)

@Serializable
data class DynForward(
    // 转发动态的 rtype 可能是 null，必须用可空类型
    val rtype: Int? = null,
    val item: DynItem? = null
)

@Serializable
data class DynStat(
    val like: DynCount? = null,
    val comment: DynCount? = null,
    val forward: DynCount? = null
)

@Serializable
data class DynCount(
    val count: Long = 0
)
