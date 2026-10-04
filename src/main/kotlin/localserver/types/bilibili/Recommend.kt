package localserver.types.bilibili

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// 首页推荐流 /x/web-interface/wbi/index/top/feed/rcmd
@Serializable
data class Recommend(
    val code: Int,
    val message: String = "",
    val ttl: Int = 1,
    val data: RecommendData? = null
)

@Serializable
data class RecommendData(
    val item: List<RecommendItem> = emptyList()
)

@Serializable
data class RecommendItem(
    val id: Long = 0,
    val bvid: String = "",
    val cid: Long = 0,
    // goto: av 为普通视频；其余（live/广告等）前端会过滤掉
    val goto: String = "",
    val uri: String = "",
    val pic: String = "",
    val title: String = "",
    val duration: Long = 0,
    val pubdate: Long = 0,
    val owner: Owner? = null,
    val stat: RecommendStat? = null,
    // 推荐理由文案，如"因为你关注了xxx"
    val rcmd_reason: RcmdReason? = null
)

@Serializable
data class RecommendStat(
    val view: Long = 0,
    val like: Long = 0,
    val danmaku: Long = 0
)

@Serializable
data class RcmdReason(
    val content: String = "",
    val reason_type: Int = 0
)

// 热门视频 /x/web-interface/popular（推荐流取不到时的兜底）
@Serializable
data class Popular(
    val code: Int,
    val message: String = "",
    val data: PopularData? = null
)

@Serializable
data class PopularData(
    val list: List<PopularItem> = emptyList(),
    @SerialName("no_more") val noMore: Boolean = false
)

@Serializable
data class PopularItem(
    val aid: Long = 0,
    val bvid: String = "",
    val cid: Long = 0,
    val pic: String = "",
    val title: String = "",
    val duration: Long = 0,
    val pubdate: Long = 0,
    val owner: Owner? = null,
    val stat: PopularStat? = null
)

@Serializable
data class PopularStat(
    val view: Long = 0,
    val like: Long = 0,
    val danmaku: Long = 0,
    val reply: Long = 0,
    val favorite: Long = 0
)
