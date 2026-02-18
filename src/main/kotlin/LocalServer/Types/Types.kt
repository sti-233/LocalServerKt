package LocalServer.Types

import kotlinx.serialization.Serializable

@Serializable
data class User(
    val ip: String,
    var name: String? = null
)

@Serializable
data class Message(
    val name: String,
    val time: String,
    val text: String
)

@Serializable
data class Content(
    val type: String,
    val name: String,
    val time: String,
    val text: String,
    val sendTo: String? = null
)
