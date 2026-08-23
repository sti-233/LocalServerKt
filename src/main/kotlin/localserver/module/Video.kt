package localserver.module

import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import kotlinx.serialization.json.*

import localserver.lib.bilibili.Search

object Video {
    private val prettyJson = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun Route.videoRoute() {
        searchByType()
    }

    private fun Route.searchByType() = get("/searchByType") {
        val keyword = call.parameters["keyword"] ?: return@get call.respondText("No parameter \"keyword\" was given.")
        val json = Search.searchByType(keyword)
        call.respondText(prettyJson.encodeToString(json))
    }
}