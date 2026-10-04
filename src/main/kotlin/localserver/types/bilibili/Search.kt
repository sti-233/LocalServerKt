package localserver.types.bilibili

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// 搜索根对象。/x/web-interface/wbi/search/type 的 data 里 video 与 bili_user 结构不同，
// result 用 JsonElement 承接，由各 lib 层分别解析，避免为一个字段写两套根类型。
@Serializable
data class SearchByType(
    val code: Int = 0,
    val message: String = "",
    val ttl: Int = 1,
    val data: SearchByTypeData? = null
)

@Serializable
data class SearchByTypeData(
    val seid: String = "",
    val page: Int = 1,
    val pagesize: Int = 20,
    val numResults: Int = 0,
    val numPages: Int = 1,
    val result: JsonElement? = null,
    val next: Int = 0
)

// 视频搜索结果条目
@Serializable
data class SearchVideoResult(
    val type: String = "",
    val author: String = "",
    val mid: Long = 0,
    val aid: Long = 0,
    val bvid: String = "",
    val title: String = "",
    val pic: String = "", // 封面
    // 播放/弹幕等用 Long：部分热门视频播放量超过 Int 上限
    val play: Long = 0,
    val video_review: Long = 0,
    val favorites: Long = 0,
    val review: Long = 0,
    val duration: String = "",
    val like: Long = 0,
    val upic: String = "" // up 头像
)

// 用户（UP主）搜索结果条目，字段取自 PiliPlus 的 SearchUserItemModel
@Serializable
data class SearchUserResult(
    val type: String = "",
    val mid: Long = 0,
    val uname: String = "",
    val usign: String = "",
    val fans: Long = 0,
    val videos: Int = 0,
    val upic: String = "",
    val verify_info: String = "",
    val level: Int = 0,
    val gender: Int = 0,
    val is_upuser: Int = 0,
    val is_live: Int = 0,
    val room_id: Long = 0,
    val official_verify: OfficialVerify? = null,
    val is_senior_member: Int = 0,
    // 该用户的部分投稿预览（每项与视频搜索结果同构）
    val res: List<SearchVideoResult> = emptyList()
)
