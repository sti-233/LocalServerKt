package localserver.module

import localserver.serverIp
import localserver.types.User
import localserver.utils.Logger
import localserver.utils.Util

import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.http.*
import kotlinx.serialization.json.*

object Control {
    var state: Boolean = true

    // control realm 的 IP 白名单，/canControl 与 Authentication 共用，避免前缀列表两份
    fun allowed(remoteAddress: String) =
        remoteAddress.startsWith("192.168.20.1") ||
        remoteAddress.startsWith("192.168.100") ||
        remoteAddress.startsWith("192.168.3.") ||
        remoteAddress.startsWith("127.0.0.1")

    fun Route.controlRoute() {
        log()
        control()
        user()
        link()
        userList()
        canControl()
        help()
    }

    // 前端（如 browser.html）据此隐藏需要 control 鉴权的按钮
    private fun Route.canControl() = get("/canControl") {
        call.respondText(allowed(call.request.local.remoteAddress).toString())
    }

    private fun Route.user() = authenticate("control") {
        addUser()
        removeUser()
        resetName()
    }

    private fun Route.link() = authenticate("control") {
        get("/vnc") { call.respondRedirect("http://${serverIp}:5901") }
    }

    // 端点清单：control 鉴权后跳转 resources/help.html（页面 fetch /resources/help.md 用 marked.js 渲染，与 ai.html 一致）
    private fun Route.help() = authenticate("control") {
        get("/help") { call.respondRedirect("/resources/help.html") }
    }

    private fun Route.control() = authenticate("control") {
        get("/start") {
            if (!state) {
                state = true
                call.respondText("Server started")
            } else {
                call.respondText("Server already started")
            }
        }
        get("/exit") {
            if (state) {
                state = false
                call.respondText("Server stopped")
            } else {
                call.respondText("Server already stopped")
            }
        }
    }

    private fun Route.log() = get("/log") {
        call.respondText(Logger.export())
    }

    private fun Route.userList() = get("/userList") {
        call.respondText(Util.prettyJson.encodeToString(Util.getUserList()))
    }

    private fun Route.addUser() = get("/addUser") {
        val ip = call.parameters["ip"] ?: return@get call.respondText("No parameter \"ip\" was given.")
        val name = call.parameters["username"] ?: ""
        val userList = Util.getUserList()
        val user = User(ip, name)
        userList.add(user)
        Util.setUserList(userList)
        call.respondText(Json.encodeToString(userList))
    }

    private fun Route.removeUser() = get("/removeUser") {
        val ip = call.parameters["ip"] ?: return@get call.respondText("No parameter \"ip\" was given.")
        val userList = Util.getUserList()
        userList.removeIf { it.ip == ip }
        Util.setUserList(userList)
        call.respondText(Json.encodeToString(userList))
    }

    private fun Route.resetName() = get("/resetName") {
        val ip = call.parameters["ip"] ?: return@get call.respondText("No parameter \"ip\" was given.")
        val name = call.parameters["username"] ?: ""
        val userList = Util.getUserList()
        userList.firstOrNull { (it.ip == ip).and(!it.name.isNullOrEmpty()) }?.also { it.name = name }
            ?: return@get call.respondText("No user was found.")
        Util.setUserList(userList)
        call.respondText(Json.encodeToString(userList))
    }
}
