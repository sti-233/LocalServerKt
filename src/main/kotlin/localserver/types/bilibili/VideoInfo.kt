package localserver.types.bilibili

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// 视频详细信息 /x/web-interface/view
@Serializable
data class VideoInfo(
    val code: Int,
    val message: String = "",
    val ttl: Int = 1,
    val data: VideoInfoData? = null
)

@Serializable
data class VideoInfoData(
    val bvid: String = "",
    val aid: Long = 0,
    val videos: Int = 1,
    val tid: Int = 0,
    val tname: String = "",
    val copyright: Int = 0,
    val pic: String = "",
    val title: String = "",
    val pubdate: Long = 0,
    val ctime: Long = 0,
    val desc: String = "",
    val state: Int = 0,
    val duration: Long = 0,
    val owner: Owner? = null,
    val stat: Stat? = null,
    val cid: Long = 0,
    val dimension: Dimension? = null,
    val pages: List<Page> = emptyList()
)

@Serializable
data class Owner(
    val mid: Long = 0,
    val name: String = "",
    val face: String = ""
)

@Serializable
data class Stat(
    val aid: Long = 0,
    val view: Long = 0,
    val danmaku: Long = 0,
    val reply: Long = 0,
    val favorite: Long = 0,
    val coin: Long = 0,
    val share: Long = 0,
    val like: Long = 0,
    // 新接口该字段可能是 "--"（隐藏播放量）或数字，用 JsonElement 兼容
    val now_rank: Int = 0,
    val his_rank: Int = 0
)

@Serializable
data class Dimension(
    val width: Int = 0,
    val height: Int = 0,
    val rotate: Int = 0
)

@Serializable
data class Page(
    val cid: Long = 0,
    // 注意：B 站对第一个分P 会返回 page:null，用可空类型接收；
    // 展示时用 displayPage（按数组下标兜底），否则首页会显示成 "Pundefined"
    val page: Int? = null,
    val from: String = "",
    val part: String = "",
    val duration: Long = 0,
    val dimension: Dimension? = null
)

// 视频取流 /x/player/wbi/playurl
@Serializable
data class PlayUrl(
    val code: Int,
    val message: String = "",
    val ttl: Int = 1,
    val data: PlayUrlData? = null
)

@Serializable
data class PlayUrlData(
    val quality: Int = 0,
    val format: String = "",
    val timelength: Long = 0,
    val accept_quality: List<Int> = emptyList(),
    val accept_description: List<String> = emptyList(),
    val durl: List<Durl> = emptyList(),
    val dash: Dash? = null
)

@Serializable
data class Durl(
    val order: Int = 0,
    val length: Long = 0,
    val size: Long = 0,
    val url: String = "",
    val backup_url: List<String> = emptyList()
)

@Serializable
data class Dash(
    val duration: Long = 0,
    val video: List<DashVideo> = emptyList(),
    val audio: List<DashAudio> = emptyList()
)

@Serializable
data class DashVideo(
    val id: Int = 0,
    // 接口同时返回 baseUrl 与 base_url，这里只取 base_url（内容相同）
    @SerialName("base_url") val baseUrl: String = "",
    @SerialName("backup_url") val backupUrl: List<String> = emptyList(),
    val bandwidth: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val codecid: Int = 0
)

@Serializable
data class DashAudio(
    val id: Int = 0,
    @SerialName("base_url") val baseUrl: String = "",
    @SerialName("backup_url") val backupUrl: List<String> = emptyList(),
    val bandwidth: Long = 0
)
