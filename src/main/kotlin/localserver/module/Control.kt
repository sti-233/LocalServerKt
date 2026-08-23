package localserver.module

import localserver.types.User
import localserver.utils.Logger
import localserver.utils.Util

import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import kotlinx.serialization.json.*

object Control {
    var state: Boolean = true

    fun Route.controlRoute() {
        log()
        control()
        user()
        link()
    }

    private fun Route.user() = authenticate("control") {
        userList()
        addUser()
        removeUser()
        resetName()
    }
    
    private fun Route.link() = authenticate("control") {
        get("/vnc") { call.respondRedirect("http://192.168.125.200:5901") }
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
        call.respondText(Json.encodeToString(Util.getUserList()))
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