package localserver.types.bilibili

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class SearchByType(
    val code: Int,
    val message: String,
    val ttl: Int,
    val data: SearchByTypeData
)

@Serializable
data class SearchByTypeData(
    val seid: String,
    val page: Int,
    val pagesize: Int,
    val numResults: Int,
    val numPages: Int,
    val result: List<SearchByTypeResult>,
    val next: Int
)

@Serializable
data class SearchByTypeResult(
    val type: String,
    val author: String,
    val mid: Long,
    val aid: Long,
    val bvid: String,
    val title: String,
    val pic: String, // 封面
    val play: Int,
    val video_review: Int,
    val favorites: Int,
    val review: Int,
    val duration: String,
    val like: Int,
    val upic: String, // up 头像
)
