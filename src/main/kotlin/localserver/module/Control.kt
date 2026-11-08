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
    /**
     * 全局开关，只由手动 `/start`、`/exit` 改动。
     *
     * 正课时段不再动这里 —— 那会把聊天、课表、文件一起关掉。上课要挡的只有视频模块，
     * 见 [VideoGate]。
     */
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

        // 临时放行视频模块：正课期间让普通用户也能看，**只到这节课结束**
        // （阶段变化时 VideoGate.followTimetable 会自动收回，见 VideoGate.manualOpen）
        get("/openVideo") {
            if (VideoGate.open) {
                // 本来就开着，不必留一个会过期的标记
                call.respondText("Video is already open")
            } else {
                val changed = VideoGate.setManualOpen(true)
                call.respondText(
                    if (changed) "Video opened for this period" else "Video already open for this period"
                )
            }
        }
        get("/closeVideo") {
            val changed = VideoGate.setManualOpen(false)
            call.respondText(if (changed) "Video closed" else "Video already closed")
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
        if (!Util.isValidUserName(name)) {
            return@get call.respondText(
                "用户名 '$name' 不可用：不能含 '-'（私聊归档名用 '-' 分隔两人，"
                    + "带 '-' 的名字会让文件名解析出歧义），也不能含控制字符",
                status = HttpStatusCode.BadRequest
            )
        }
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

    /**
     * 改名。用户名同时是「私聊归档文件名的一半」「消息体里的发送者」「图片/AI/配置的目录名」，
     * 光改 userList.json 会留下对不上的旧数据（详见 [Util.migrateRename] 的注释），
     * 所以这里改完名单后立刻迁移各类存档，并把迁移统计一并回给调用方。
     *
     * 顺序是「先改名单、再迁移」：迁移只依赖传进来的两个名字，与名单状态无关；
     * 万一迁移半途失败，新名字已经生效，用户能正常聊天，旧数据留在原地不会更糟。
     * 每一步都单独 try：图片迁移炸了不该连累聊天记录。
     */
    private fun Route.resetName() = get("/resetName") {
        val ip = call.parameters["ip"] ?: return@get call.respondText("No parameter \"ip\" was given.")
        val name = call.parameters["username"] ?: ""
        if (!Util.isValidUserName(name)) {
            return@get call.respondText(
                "用户名 '$name' 不可用：不能含 '-'（私聊归档名用 '-' 分隔两人，"
                    + "带 '-' 的名字会让文件名解析出歧义），也不能含控制字符",
                status = HttpStatusCode.BadRequest
            )
        }
        val userList = Util.getUserList()
        val user = userList.firstOrNull { (it.ip == ip).and(!it.name.isNullOrEmpty()) }
            ?: return@get call.respondText("No user was found.")
        val oldName = user.name
        // 重名保护：改名迁移是按名字合并的，改到别人正在用的名字上就等于把两份存档搅在一起，
        // 而且之后 getUserIp 只会返回其中一个，另一个人的私聊静默丢。宁可拒绝。
        val taken = userList.firstOrNull {
            it.ip != ip && !it.name.isNullOrEmpty() && it.name == name
        }
        if (taken != null) {
            return@get call.respondText(
                "用户名 '$name' 已被 ${taken.ip} 占用，改名会把两个人的记录混在一起，请换一个名字",
                status = HttpStatusCode.BadRequest
            )
        }
        user.name = name
        Util.setUserList(userList)
        val migrated = mutableMapOf<String, Map<String, Int>>()
        if (!oldName.isNullOrEmpty() && oldName != name) {
            fun step(label: String, block: () -> Map<String, Int>) {
                migrated[label] = try {
                    block()
                } catch (e: Exception) {
                    Logger.error("resetName: migrate $label '$oldName' -> '$name' failed: ${e.message}")
                    mapOf("failed" to 1)
                }
            }
            step("history") { Util.migrateRename(oldName, name) }
            step("pictures") { PictureStore.renameUser(oldName, name) }
            step("avatars") { AvatarStore.renameUser(oldName, name) }
            step("ai") { localserver.utils.AiHistory.renameUser(oldName, name) }
            step("prefs") { Prefs.renameUser(oldName, name) }
        }
        // 手搓 JsonObject：migrated 是「步骤 → 统计」的两层 Map，用 Map<String, Any> 交给
        // kotlinx 会被拒（Any 没有序列化器）。这里每层都显式转成 JsonElement。
        val body = buildJsonObject {
            put("users", Json.encodeToJsonElement(userList))
            put("migrated", buildJsonObject {
                migrated.forEach { (label, stats) ->
                    put(label, buildJsonObject {
                        stats.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
                    })
                }
            })
        }
        call.respondText(Json.encodeToString(JsonObject.serializer(), body))
    }
}
