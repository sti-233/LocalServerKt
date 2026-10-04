package localserver.types.bilibili

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// 评论区明细 /x/v2/reply/wbi/main（懒加载版，旧 /x/v2/reply 已废弃且无 SESSDATA 时恒返回空）
@Serializable
data class ReplyList(
    val code: Int,
    val message: String = "",
    val ttl: Int = 1,
    val data: ReplyListData? = null
)

@Serializable
data class ReplyListData(
    val cursor: ReplyCursor? = null,
    val replies: List<Reply>? = null,
    val hots: List<Reply>? = null,
    val top_replies: List<Reply>? = null,
    val upper: ReplyUpper? = null
)

@Serializable
data class ReplyCursor(
    val all_count: Int = 0,
    val is_begin: Boolean = true,
    val is_end: Boolean = false,
    val prev: Int = 0,
    val next: Int = 0,
    val mode: Int = 3
)

@Serializable
data class ReplyUpper(
    val mid: Long = 0,
    val top: Reply? = null
)

@Serializable
data class Reply(
    val rpid: Long = 0,
    val oid: Long = 0,
    val mid: Long = 0,
    val root: Long = 0,
    val parent: Long = 0,
    val count: Int = 0,
    val rcount: Int = 0,
    val ctime: Long = 0,
    val like: Long = 0,
    val member: ReplyMember? = null,
    val content: ReplyContent? = null,
    val replies: List<Reply>? = null
)

@Serializable
data class ReplyMember(
    val mid: String = "",
    val uname: String = "",
    val sex: String = "",
    val sign: String = "",
    val avatar: String = "",
    val level_info: LevelInfo? = null,
    val official_verify: OfficialVerify? = null,
    @SerialName("vip") val vip: ReplyVip? = null
)

@Serializable
data class LevelInfo(
    val current_level: Int = 0
)

@Serializable
data class OfficialVerify(
    val type: Int = -1,
    val desc: String = ""
)

@Serializable
data class ReplyVip(
    val vipType: Int = 0,
    val vipStatus: Int = 0
)

@Serializable
data class ReplyContent(
    val message: String = "",
    val members: List<ReplyContentMember> = emptyList()
)

@Serializable
data class ReplyContentMember(
    val mid: String = "",
    val uname: String = ""
)

// 用户名片信息 /x/web-interface/card（acc/info 被风控时的降级方案）
@Serializable
data class UserCard(
    val code: Int,
    val message: String = "",
    val data: UserCardData? = null
)

@Serializable
data class UserCardData(
    val card: UserCardInner? = null,
    val follower: Long = 0,
    val archive_count: Int = 0,
    val like_num: Long = 0
)

@Serializable
data class UserCardInner(
    val mid: String = "",
    val name: String = "",
    val sex: String = "",
    val face: String = "",
    val sign: String = "",
    val fans: Long = 0,
    val friend: Long = 0,
    val level_info: LevelInfo? = null,
    val spacesta: Int = 0,
    @SerialName("Official") val official: Official? = null,
    val official_verify: OfficialVerify? = null,
    @SerialName("vip") val vip: UserVip? = null
)

// 用户空间详细信息 /x/space/wbi/acc/info
@Serializable
data class UserInfo(
    val code: Int,
    val message: String = "",
    val ttl: Int = 1,
    val data: UserInfoData? = null
)

@Serializable
data class UserInfoData(
    val mid: Long = 0,
    val name: String = "",
    val sex: String = "",
    val face: String = "",
    val sign: String = "",
    val level: Int = 0,
    val birthday: String = "",
    val silence: Int = 0,
    val fans_badge: Boolean = false,
    val official: Official? = null,
    @SerialName("vip") val vip: UserVip? = null,
    val live_room: LiveRoom? = null,
    val is_senior_member: Int = 0
)

@Serializable
data class Official(
    val role: Int = 0,
    val title: String = "",
    val desc: String = "",
    val type: Int = -1
)

@Serializable
data class UserVip(
    // 注意：Web 端用 type/status，APP 端(space/card)用 vipType/vipStatus，
    // 这里同时声明两套别名，把 APP 的驼峰值映射到统一字段上。
    @SerialName("type") val type: Int = 0,
    @SerialName("status") val statusRaw: Int = 0,
    @SerialName("vipType") val typeAlt: Int = 0,
    @SerialName("vipStatus") val statusAlt: Int = 0,
    @SerialName("due_date") val due_date: Long = 0,
    @SerialName("vipDueDate") val dueDateAlt: Long = 0,
    val label: VipLabel? = null
) {
    /** 统一后的会员状态：1 表示有效 */
    val status: Int get() = if (statusRaw != 0) statusRaw else statusAlt
    val typeId: Int get() = if (type != 0) type else typeAlt
}

@Serializable
data class VipLabel(
    val text: String = "",
    val label_theme: String = ""
)

@Serializable
data class LiveRoom(
    val roomStatus: Int = 0,
    val liveStatus: Int = 0,
    val url: String = "",
    val title: String = "",
    val cover: String = "",
    val roomid: Long = 0
)

// 用户投稿视频 /x/space/wbi/arc/search
@Serializable
data class SpaceArchive(
    val code: Int,
    val message: String = "",
    val ttl: Int = 1,
    val data: SpaceArchiveData? = null
)

@Serializable
data class SpaceArchiveData(
    val list: SpaceArchiveList? = null,
    val page: SpaceArchivePage? = null
)

@Serializable
data class SpaceArchiveList(
    val vlist: List<SpaceVideo> = emptyList()
)

@Serializable
data class SpaceArchivePage(
    val pn: Int = 1,
    val ps: Int = 30,
    val count: Int = 0
)

@Serializable
data class SpaceVideo(
    val aid: Long = 0,
    val bvid: String = "",
    val title: String = "",
    val pic: String = "",
    val description: String = "",
    val created: Long = 0,
    val length: String = "",
    val play: Long = 0,
    val video_review: Long = 0,
    val comment: Long = 0,
    val is_union_video: Int = 0
)
