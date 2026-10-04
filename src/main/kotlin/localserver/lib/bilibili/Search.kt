package localserver.lib.bilibili

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray

import localserver.lib.bilibili.utils.WbiSign
import localserver.types.bilibili.SearchByTypeData
import localserver.types.bilibili.SearchUserResult
import localserver.types.bilibili.SearchVideoResult
import localserver.utils.Hashs.urlEncoded
import localserver.utils.Logger

/**
 * 分类搜索。
 *
 * 用 WBI 端点 /x/web-interface/wbi/search/type（参考 PiliPlus 的 searchByType）：
 * 非 WBI 的 /x/web-interface/search/type 在当前网络环境下会返回 HTML 风控页。
 * 同一端点靠 search_type 区分搜视频(search_type=video)与搜用户(search_type=bili_user)。
 */
object Search {
    // 该接口每页条数固定 20，numPages 上限 50
    const val PAGE_SIZE = 20
    const val MAX_PAGE = 50

    // B 站接口的 search_type 取值
    const val TYPE_VIDEO = "video"
    const val TYPE_USER = "bili_user"

    // 对外（URL 参数）使用的简短名称，与接口取值解耦
    const val PUBLIC_VIDEO = "video"
    const val PUBLIC_USER = "user"

    /** 把对外名称翻译成 B 站接口的 search_type；未知返回 null */
    fun apiTypeOf(publicName: String): String? = when (publicName) {
        PUBLIC_VIDEO -> TYPE_VIDEO
        PUBLIC_USER -> TYPE_USER
        else -> null
    }

    private suspend fun raw(
        searchType: String,
        keyword: String,
        page: Int,
        extra: Map<String, Any> = emptyMap()
    ): SearchByTypeData? {
        val signed = WbiSign.sign(
            mutableMapOf<String, Any>(
                "search_type" to searchType,
                "keyword" to keyword,
                "page" to page,
                "page_size" to PAGE_SIZE,
                "platform" to "pc",
                "web_location" to 1430654
            ).apply { putAll(extra) }
        )
        val json = safeGetAs<localserver.types.bilibili.SearchByType>(
            label = "search/$searchType",
            url = "https://api.bilibili.com/x/web-interface/wbi/search/type",
            parameters = signed,
            headers = biliHeaderWithFinger("https://search.bilibili.com/$searchType?keyword=${keyword.urlEncoded}") +
                ("origin" to "https://search.bilibili.com")
        )
        if (json == null || json.code != 0) {
            if (json != null) Logger.debug("bilibili search/$searchType code=${json.code} ${json.message}")
            return null
        }
        return json.data
    }

    /** 搜视频 */
    suspend fun searchVideo(
        keyword: String,
        page: Int = 1,
        order: String? = null,
        duration: Int? = null,
        tids: Int? = null
    ): SearchByTypeData? {
        val extra = mutableMapOf<String, Any>()
        if (!order.isNullOrBlank()) extra["order"] = order
        if (duration != null && duration > 0) extra["duration"] = duration
        if (tids != null && tids > 0) extra["tids"] = tids
        return raw(TYPE_VIDEO, keyword, page.coerceIn(1, MAX_PAGE), extra)
    }

    /** 搜用户 / UP主 */
    suspend fun searchUser(
        keyword: String,
        page: Int = 1,
        userType: Int? = null
    ): SearchByTypeData? {
        val extra = mutableMapOf<String, Any>()
        // user_type: 0 全部 / 1 up主 / 2 普通用户 / 3 认证用户
        if (userType != null && userType in 1..3) extra["user_type"] = userType
        return raw(TYPE_USER, keyword, page.coerceIn(1, MAX_PAGE), extra)
    }

    /** 把 result(JsonElement) 解析成视频条目列表 */
    fun parseVideos(data: SearchByTypeData?): List<SearchVideoResult> {
        val arr: JsonElement = data?.result ?: return emptyList()
        return runCatching { arr.jsonArray.map { json.decodeFromJsonElement<SearchVideoResult>(it) } }
            .onFailure { Logger.error("parse search videos failed: ${it.message}") }
            .getOrDefault(emptyList())
    }

    /** 把 result(JsonElement) 解析成用户条目列表 */
    fun parseUsers(data: SearchByTypeData?): List<SearchUserResult> {
        val arr: JsonElement = data?.result ?: return emptyList()
        return runCatching { arr.jsonArray.map { json.decodeFromJsonElement<SearchUserResult>(it) } }
            .onFailure { Logger.error("parse search users failed: ${it.message}") }
            .getOrDefault(emptyList())
    }

    // 共用一个宽松的 Json 实例（接口会多返回大量字段）
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
}
