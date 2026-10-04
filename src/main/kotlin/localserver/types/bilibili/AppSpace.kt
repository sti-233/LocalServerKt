package localserver.types.bilibili

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---------- APP 端空间资料 /x/v2/space （app.bilibili.com） ----------

@Serializable
data class AppSpace(
    val code: Int = 0,
    val message: String = "",
    val data: AppSpaceData? = null
)

@Serializable
data class AppSpaceData(
    val card: AppSpaceCard? = null,
    val relation: Int = 0,
    val archive: AppSpaceArchive? = null,
    val live: AppSpaceLive? = null,
    val elec: AppSpaceElec? = null,
    val guard: AppSpaceGuard? = null,
    val tab2: List<AppSpaceTab> = emptyList()
)

@Serializable
data class AppSpaceCard(
    val mid: String = "",
    val name: String = "",
    val sex: String = "",
    val face: String = "",
    val sign: String = "",
    // 注意 APP 端把粉丝数放在 card 上，而不是顶层的 follower
    val fans: Long = 0,
    val friend: Long = 0,
    val attention: Long = 0,
    val level_info: LevelInfo? = null,
    // 0 正常 / -2 被封禁
    val spacesta: Int = 0,
    val birthday: String = "",
    val vip: UserVip? = null,
    @SerialName("Official") val official: Official? = null,
    val official_verify: OfficialVerify? = null,
    val is_senior_member: Int = 0,
    val nameplate: AppNameplate? = null,
    val fans_medal: AppFansMedal? = null
)

@Serializable
data class AppNameplate(
    val nid: Long = 0,
    val name: String = "",
    val image: String = "",
    val image_small: String = "",
    val level: String = ""
)

@Serializable
data class AppFansMedal(
    val show: Boolean = false,
    val wear: Boolean = false,
    val medal: AppMedal? = null
)

@Serializable
data class AppMedal(
    val level: Int = 0,
    val name: String = ""
)

@Serializable
data class AppSpaceArchive(
    val count: Long = 0
)

@Serializable
data class AppSpaceLive(
    val roomStatus: Int = 0,
    val liveStatus: Int = 0,
    val url: String = "",
    val title: String = "",
    val cover: String = "",
    val roomid: Long = 0
)

@Serializable
data class AppSpaceElec(
    val show_info: AppElecShowInfo? = null
)

@Serializable
data class AppElecShowInfo(
    val total: Long = 0,
    val count: Long = 0
)

@Serializable
data class AppSpaceGuard(
    val count: Long = 0
)

@Serializable
data class AppSpaceTab(
    val name: String = "",
    val param: String = "",
    val uri: String = ""
)

// ---------- APP 端空间投稿 /x/v2/space/archive/cursor ----------

@Serializable
data class AppSpaceArchives(
    val code: Int = 0,
    val message: String = "",
    val data: AppSpaceArchivesData? = null
)

@Serializable
data class AppSpaceArchivesData(
    val item: List<AppSpaceArchiveItem> = emptyList(),
    val count: Long = 0,
    val has_next: Boolean = false,
    val has_prev: Boolean = false,
    val order: List<AppSpaceOrder> = emptyList()
)

@Serializable
data class AppSpaceOrder(
    val title: String = "",
    val value: String = ""
)

/**
 * APP 投稿条目。接口返回的内容很"肥"（每项内嵌了完整的 playurl、三连按钮配置等），
 * 这里只声明我们真正要用的字段，配合 ignoreUnknownKeys 丢弃其余内容。
 */
@Serializable
data class AppSpaceArchiveItem(
    val title: String = "",
    val cover: String = "",
    val bvid: String = "",
    // param 是 aid（字符串），翻页需要把它当作游标回传
    val param: String = "",
    val duration: Long = 0,
    val play: Long = 0,
    val danmaku: Long = 0,
    val ctime: Long = 0,
    val author: String = "",
    val tname: String = "",
    // 首P 的 cid，可直接用于取流
    val first_cid: Long = 0,
    val videos: Int = 1,
    // 可用于展示的"103万"这类文案
    val view_content: String = ""
)
