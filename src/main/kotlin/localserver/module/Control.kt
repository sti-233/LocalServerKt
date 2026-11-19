package localserver.module

import localserver.serverIp
import localserver.types.User
import localserver.utils.Logger
import localserver.utils.Time
import localserver.utils.Timetable
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
        page()
        status()
        report()
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

    /**
     * control 快捷操作面板。
     *
     * 这些操作本来各自是裸端点（`/start`、`/addUser`…），要用就得记路径、手拼查询串，
     * 在手机上一个都不好用。这里给一个把它们摆成按钮的页面，页面自身与它调用的接口
     * 都走 control 鉴权，非白名单 IP 连页面都打不开。
     */
    private fun Route.page() = authenticate("control") {
        get("/control") { call.respondRedirect("/resources/control.html") }
    }

    /**
     * 面板的状态汇总。
     *
     * 一次性把页面要显示的东西都给了，而不是让前端去拼 `/log`、`/userList`、`/videoStatus`
     * 再加几个读不到的（`/start` 的当前值根本没有读的端点）：拼端点既慢，又拼不出
     * 「现在是不是正课」「视频模块是被课表关的还是被手动关的」这些真正想知道的信息。
     *
     * **不能加 `no-store` 之外的缓存**：这些值随时在变，缓存住页面就会显示上一分钟的状态。
     */
    private fun Route.status() = authenticate("control") {
        get("/control/status") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            val body = buildJsonObject {
                put("serverIp", JsonPrimitive(serverIp))
                put("state", JsonPrimitive(state))
                put("video", buildJsonObject {
                    put("open", JsonPrimitive(VideoGate.open))
                    put("manualOpen", JsonPrimitive(VideoGate.manualOpen))
                })
                put("timetable", buildJsonObject {
                    put("inClass", JsonPrimitive(Timetable.inClass()))
                    // 0 表示节次未知/无课，前端据此决定显不显示
                    put("period", JsonPrimitive(Timetable.currentPeriod(Time.now()) ?: 0))
                    put("week", JsonPrimitive(Timetable.weekOf()))
                })
                put("online", JsonPrimitive(Chat.onlineClients()))
                put("users", Json.encodeToJsonElement(Util.getUserList()))
                put("banned", Json.encodeToJsonElement(ReportStore.banned()))
                put("pending", buildJsonArray {
                    ReportStore.pending().forEach { p ->
                        add(buildJsonObject {
                            put("name", JsonPrimitive(p.name))
                            put("reporters", Json.encodeToJsonElement(p.reporters))
                            put("updatedAt", JsonPrimitive(p.updatedAt))
                        })
                    }
                })
            }
            call.respondText(
                Json.encodeToString(JsonObject.serializer(), body),
                contentType = ContentType.Application.Json
            )
        }
    }

    /**
     * 撤销图片封禁。
     *
     * 封禁是三个用户（或一个 control）就能触发的事，误封一定会发生，所以必须有个出口。
     * 只从黑名单里移除；被封禁时已经删掉的图片文件与消息**不会**回来 —— 那一步不可逆。
     */
    private fun Route.report() = authenticate("control") {
        get("/control/unban") {
            val name = call.parameters["name"].orEmpty()
            if (!PictureStore.IMAGE_NAME.matches(name)) {
                return@get call.respondText(
                    "图片文件名不合法（应为 <sha256>.<ext>）",
                    status = HttpStatusCode.BadRequest
                )
            }
            if (ReportStore.unban(name)) {
                Logger.debug("control: 撤销图片封禁 $name")
                // 手搓 JSON 而不是 encodeToString(mapOf("ok" to true, ...))：
                // Boolean 与 String 混在一个 Map 里，kotlinx 找不到 Any 的序列化器，运行时 500
                call.respondText(
                    buildJsonObject {
                        put("ok", JsonPrimitive(true))
                        put("name", JsonPrimitive(name))
                    }.toString(),
                    contentType = ContentType.Application.Json
                )
            } else {
                call.respondText(
                    buildJsonObject {
                        put("ok", JsonPrimitive(false))
                        put("error", JsonPrimitive("这张图不在封禁名单里"))
                    }.toString(),
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.NotFound
                )
            }
        }
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
