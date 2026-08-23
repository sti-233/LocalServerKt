package localserver.types.bilibili

import kotlinx.serialization.Serializable

@Serializable
data class Buvid3(
    val code: Int,
    val data: Buvid3Data
)

@Serializable
data class Buvid3Data(
    val buvid: String
)
