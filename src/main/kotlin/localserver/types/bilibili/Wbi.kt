package localserver.types.bilibili

import kotlinx.serialization.Serializable

@Serializable
data class Wbi(
    val code: Int,
    val message: String,
    val ttl: Int,
    val data: WbiData
)

@Serializable
data class WbiData(
    val isLogin: Boolean,
    val wbi_img: WbiImg?,
    val ip_region: String
)

@Serializable
data class WbiImg(
    val img_url: String,
    val sub_url: String
)
