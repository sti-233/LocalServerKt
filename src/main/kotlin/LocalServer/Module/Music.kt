package LocalServer.Module

import LocalServer.NeteaseMusicApi.network.MusicNetwork

import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import kotlinx.serialization.json.*

object Music {
    private val cookie = ""

    public fun Route.musicRoute() {
        musicPage()
        searchMusic()
        getLyrics()
        getMusicUrl()
    }

    private fun Route.musicPage() = authenticate("auth") {
        get("/music") {
            call.respondRedirect("/resources/music.html")
        }
    }

    private fun Route.searchMusic() = get("/searchMusic") {
        val keyword = call.parameters["keyword"]!!
        val offset = call.parameters["offset"]?.toIntOrNull() ?: 0
        val limit = call.parameters["limit"]?.toIntOrNull() ?: 10
        val result = MusicNetwork.searchMusic(keyword, offset, limit, cookie)
        call.respondText(Json.encodeToString(result))
    }

    private fun Route.getLyrics() = get("/getLyrics") {
        val id = call.parameters["id"]!!
        val result = MusicNetwork.getLyrics(id, cookie)
        call.respondText(Json.encodeToString(result))
    }

    private fun Route.getMusicUrl() = get("/getMusicUrl") {
        val id = call.parameters["id"]!!
        val level = call.parameters["level"]!!
        val result = MusicNetwork.getMusicUrl(id, level, cookie)
        call.respondText(Json.encodeToString(result))
    }
}