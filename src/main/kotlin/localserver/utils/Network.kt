package localserver.utils

import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.management.ManagementFactory
import java.net.URLEncoder
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.format.DateTimeFormatter
import java.util.zip.ZipFile
import kotlin.system.exitProcess
import localserver.serverIp

// 运行中 jar 所在的目录（gradlew run 时为 classes 目录），供 /defaultFileDir 与 Terminal 默认目录使用；失败返回空串
fun defaultDir(): String = runCatching {
    val f = File(Network::class.java.protectionDomain.codeSource.location.toURI())
    if (f.isFile) f.parentFile else f
}.getOrNull()?.absolutePath ?: ""

object Network {
    fun Route.network() {
        configJs()
        download()
        browser()
        timetable()
        fileDir()
        update()
    }

    // 零代码快传分享页基址（/update 抓分享页取短时效下载签名）
    private const val LDMKC_SHARE = "https://www.ldmkc.cn"

    /**
     * 动态下发 `/resources/config.js`，把写死的 SERVER_IP 换成启动时探测到的内网地址。
     *
     * 为什么放在服务端：静态资源里没法知道本机 IP。前端页面（chat 的 ws 地址、browser 的跳转、
     * music 的代理前缀…）都读全局 `SERVER_IP`，这里按请求实时生成，DHCP 换地址后刷新页面就生效。
     *
     * 注意顺序：`staticResources("/resources", "")` 用的是 Ktor 的静态资源路由，
     * 会先尝试命中文件；src/main/resources/config.js 已删除，所以请求会落到这里。
     */
    private fun Route.configJs() = get("/resources/config.js") {
        val ip = serverIp
        // 同源访问时前端其实用 location.origin 就够了，SERVER_IP 只在 file:// 打开时兜底；
        // 但保留它能让"复制链接给手机"这类用法拿到可用的局域网地址。
        val body = buildString {
            append("// 由服务端动态生成（utils/Network.kt 的 /resources/config.js），勿手工改。\n")
            append("// 地址来源：utils/LocalIp.kt 探测本机内网地址，可用 -Dlocalserver.ip= 或\n")
            append("// 环境变量 LOCALSERVER_IP 覆盖；探测不准时优先看这个文件里的值对不对。\n")
            append("const SERVER_IP = \"").append(ip).append("\";\n")
            append("const VNC_URL = \"http://\" + SERVER_IP + \":5901\"; // VNC 查看器地址\n")
        }
        // no-store：地址会变，别让浏览器把旧 IP 缓存下来
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondText(body, contentType = ContentType.Application.JavaScript)
    }

    private fun Route.fileDir() = authenticate("control") {
        get("/defaultFileDir") {
            val dir = defaultDir()
            if (dir.isEmpty()) call.respondText("", status = HttpStatusCode.InternalServerError)
            else call.respondText(dir)
        }
    }

    /**
     * /update?code=<分享码>[&restart=0]（control 验证）：从零代码快传下载文件，实现一键自更新。
     *
     * 1) 抓分享页，从隐藏 div 提取 kouling/e/n/s/down1/down2（e/n/s 为短时效签名，必须每次从页面取）；
     * 2) 拼下载链接 `{base}/api/o/{kouling}?e=&n=&s=`，先普通域名(down1)，失败回退备用域名(down2)；
     * 3) 下载到运行目录的 update_*.part 临时文件并校验（zip 合法 + 含 localserver/ServerKt.class）；
     * 4) 自更新：当前若以 jar 方式运行，把临时文件交给进程外的 helper（PowerShell）——
     *    等本进程退出后用**同名**覆盖原 jar、再按原命令行重启，启动失败则回滚 .bak；
     *    本进程回完响应后退出，全程无需人工干预（restart=0 只下载不重启）。
     *    gradle run（classes 目录，非 jar）或校验不通过时退化为只保存文件。
     */
    private fun Route.update() = authenticate("control") {
        get("/update") {
            val code = call.request.queryParameters["code"]
                ?: return@get call.respondText("Please provide code parameter", status = HttpStatusCode.BadRequest)
            if (!code.matches(Regex("[A-Za-z0-9_-]{1,64}"))) {
                return@get call.respondText("Invalid code", status = HttpStatusCode.BadRequest)
            }
            val restart = call.request.queryParameters["restart"] != "0"
            val client = HttpClient.client

            // 1) 抓分享页，提取隐藏 div 里的下载参数
            val page = runCatching { client.get(LDMKC_SHARE + "/" + code) }.getOrNull()
                ?: return@get call.respondText("Failed to fetch share page", status = HttpStatusCode.InternalServerError)
            if (!page.status.isSuccess()) {
                return@get call.respondText("Share code not found or page unavailable: ${page.status}", status = HttpStatusCode.NotFound)
            }
            val html = page.bodyAsText()
            val grab = { id: String -> Regex("id=\"$id\"[^>]*>([^<]+)<").find(html)?.groupValues?.get(1)?.trim() }
            val k = grab("kouling"); val e = grab("signE"); val n = grab("signN"); val s = grab("signS")
            if (k.isNullOrBlank() || e.isNullOrBlank() || n.isNullOrBlank() || s.isNullOrBlank()) {
                // 上游对"不存在/已删除"的口令同样返回 200，只是页面里没有 kouling div
                if (html.contains("文件不存在") || html.contains("文件已经删除") || html.contains("口令错误")) {
                    return@get call.respondText("File not found: code=$code (deleted or wrong code)", status = HttpStatusCode.NotFound)
                }
                return@get call.respondText("Cannot extract download params from share page (page layout may have changed)", status = HttpStatusCode.NotFound)
            }
            val bases = listOfNotNull(grab("down1"), grab("down2")).filter { it.isNotBlank() }
            if (bases.isEmpty()) {
                return@get call.respondText("Cannot extract download base from share page", status = HttpStatusCode.NotFound)
            }
            // 优先用分享页展示的文件名（下载接口的 Content-Disposition 可能缺扩展名）
            val pageName = Regex("""class="file-name"[^>]*>\s*<span>([^<]+)</span>""")
                .find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }

            // 2) 下载文件：普通域名 → 备用域名（同一份 e/n/s 签名在两个域名都有效）
            // 用 Http.openUpstream（JDK HttpClient，无请求级超时、流式 body）而非 CIO：
            // CIO 默认 15s 请求超时，而 17MB 按站方限速要 15~20s，必被掐断（浏览器无此限制）。
            val enc = { v: String -> URLEncoder.encode(v, "UTF-8") }
            var upstream: Http.Upstream? = null
            var dlUrl = ""
            var lastError = ""
            for (base in bases) {
                val url = "${base.trimEnd('/')}/api/o/${enc(k)}?e=${enc(e)}&n=${enc(n)}&s=${enc(s)}"
                val up = Http.openUpstream(url, ua = "Mozilla/5.0")
                if (up != null && up.status == 200) {
                    upstream = up
                    dlUrl = url
                    break
                }
                up?.body?.close()
                lastError = "(${base}) HTTP ${up?.status ?: "连接失败"}"
            }
            val up = upstream
                ?: return@get call.respondText("Download failed: $lastError", status = HttpStatusCode.BadGateway)

            // 3) 流式落盘到运行目录的临时 .part，先校验再决定如何落地
            val dir = defaultDir()
            if (dir.isEmpty()) {
                up.body.close()
                return@get call.respondText("Cannot determine running directory", status = HttpStatusCode.InternalServerError)
            }
            val temp = Files.createTempFile(File(dir).toPath(), "update_", ".part")
            val size = try {
                withContext(Dispatchers.IO) {
                    up.body.use { input ->
                        Files.newOutputStream(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { output ->
                            input.copyTo(output)
                        }
                    }
                    Files.size(temp)
                }
            } catch (ex: Exception) {
                Files.deleteIfExists(temp)
                return@get call.respondText("Failed to read download stream: ${ex.message}", status = HttpStatusCode.InternalServerError)
            }
            // 校验下载物：必须是合法 zip 且含本服务主类（避免把 HTML 错误页/半个文件当成 jar 自更新）
            val looksLikeOwnJar = isOwnJar(temp)

            // 4) 自更新：仅在"以 jar 方式运行 + 下载物是本服务 jar + 允许重启"时接管
            val runningJar = ownJarPath()
            if (restart && runningJar != null && looksLikeOwnJar) {
                val helper = try {
                    buildUpdateHelper(runningJar, temp, size)
                } catch (ex: Exception) {
                    null
                }
                if (helper != null) {
                    val started = try {
                        withContext(Dispatchers.IO) { startDetached(helper) }
                    } catch (ex: Exception) {
                        false
                    }
                    if (started) {
                        call.respondText(
                            "ok: updating ${runningJar.name} ($size bytes) code=$code; " +
                                "server will restart automatically in a few seconds"
                        )
                        // 让响应真正发出去后再退出，交给 helper 覆盖同名 jar 并重启
                        CoroutineScope(Dispatchers.Default).launch {
                            delay(1500)
                            Logger.debug("self-update: exiting for ${runningJar.absolutePath}")
                            exitProcess(0)
                        }
                        return@get
                    }
                }
            }

            // 退化路径：非 jar 运行、校验不通过、未允许重启或 helper 启动失败时，只把文件落地
            var fileName = (pageName ?: fileNameFromHeaders(dlUrl, up)).replace('\\', '_').replace('/', '_')
            if (fileName.isEmpty() || fileName in setOf(".", "..", "downloaded_file")) {
                fileName = "update.bin"
            }
            val target = File(dir, fileName)
            var saved = target
            var note = ""
            try {
                Files.move(temp, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (ex: Exception) {
                // 目标被占用（如运行中的 fat jar），另存 .update
                val fallback = File(dir, fileName + ".update")
                try {
                    Files.move(temp, fallback.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    saved = fallback
                    note = " (original file in use, saved as .update)"
                } catch (ex2: Exception) {
                    Files.deleteIfExists(temp)
                    return@get call.respondText("Save failed: ${ex.message}", status = HttpStatusCode.InternalServerError)
                }
            }
            call.respondText("ok: ${saved.absolutePath} ($size bytes) code=$code$note")
        }
    }

    /** 当前进程是否以 jar 方式运行：是则返回该 jar 路径，否则（如 gradle run 的 classes 目录）返回 null */
    private fun ownJarPath(): File? = runCatching {
        val f = File(Network::class.java.protectionDomain.codeSource.location.toURI())
        if (f.isFile && f.name.endsWith(".jar", ignoreCase = true)) f.absoluteFile else null
    }.getOrNull()

    /** 校验下载物是一份可用的服务 jar：合法 zip + 含 localserver/ServerKt.class */
    private fun isOwnJar(file: Path): Boolean = runCatching {
        ZipFile(file.toFile()).use { zip ->
            zip.getEntry("localserver/ServerKt.class") != null
        }
    }.getOrDefault(false)

    /**
     * 重建本进程的启动命令。Windows 上 ProcessHandle.arguments() 为空，
     * 故用 sun.java.command 与 java.home / java.class.path 还原。
     */
    private fun restartCommand(): List<String>? = runCatching {
        val javaExe = File(System.getProperty("java.home"), "bin/java.exe").let {
            if (it.exists()) it.absolutePath else File(System.getProperty("java.home"), "bin/java").absolutePath
        }
        val cmdProp = System.getProperty("sun.java.command") ?: return null
        // sun.java.command 首段是 jar 路径或主类名，其后是 main() 参数
        val parts = cmdProp.split(" ")
        if (parts.isEmpty()) return null
        val head = parts[0]
        val args = parts.drop(1)
        if (head.endsWith(".jar", ignoreCase = true)) {
            listOf(javaExe, "-jar", head) + args
        } else {
            val cp = System.getProperty("java.class.path") ?: return null
            listOf(javaExe, "-cp", cp, head) + args
        }
    }.getOrNull()

    /**
     * 生成自更新 helper 脚本（.ps1），返回其路径；脚本自带校验与失败回滚。
     * 脚本体存放在 resources/update-helper.ps1（可在编辑器里正常高亮/校对），
     * 这里只负责读入模板并把 __XXX__ 占位符替换成运行时值。
     */
    private fun buildUpdateHelper(jar: File, newFile: Path, size: Long): File {
        val jarDir = jar.parentFile
        val bak = File(jarDir, jar.name + ".bak")
        val log = File(jarDir, "update-helper.log")
        val pid = ProcessHandle.current().pid()
        val cmd = restartCommand() ?: throw IllegalStateException("cannot rebuild restart command")
        // 重启要沿用原工作目录（状态文件 userList.json / message / logs.txt 都相对 CWD）
        val workDir = File(System.getProperty("user.dir") ?: jarDir.absolutePath)
        // PowerShell 单引号字符串里，单引号需双写
        fun ps(s: String) = "'" + s.replace("'", "''") + "'"
        // Windows 命令行引号：含空格/引号才加双引号（Start-Process -ArgumentList 传单个字符串不会被二次拆分）
        fun winQuote(s: String) = if (s.any { it == ' ' || it == '\t' || it == '"' }) "\"" + s.replace("\"", "\\\"") + "\"" else s

        val template = Network::class.java.getResourceAsStream("/update-helper.ps1")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IllegalStateException("resource update-helper.ps1 not found")

        val script = template
            .replace("__LOG__", ps(log.absolutePath))
            .replace("__JAR__", ps(jar.absolutePath))
            .replace("__NEW__", ps(newFile.toAbsolutePath().toString()))
            .replace("__BAK__", ps(bak.absolutePath))
            .replace("__CWD__", ps(workDir.absolutePath))
            .replace("__EXE__", ps(cmd[0]))
            .replace("__ARGLINE__", ps(cmd.drop(1).joinToString(" ") { winQuote(it) }))
            .replace("__PID__", pid.toString())
            .replace("__SIZE__", size.toString())

        val scriptFile = File(jarDir, "update-helper.ps1")
        // 必须带 UTF-8 BOM：powershell.exe 是 Windows PowerShell 5.1，无 BOM 时按系统 ANSI(GBK)
        // 解码，中文注释末字节会吞掉换行符，导致脚本语法错误（实测踩过）
        scriptFile.writeText("\uFEFF" + script, Charsets.UTF_8)
        return scriptFile
    }

    /**
     * 以脱离父进程的方式启动 helper（独立进程 + 丢弃输出），返回 helper 是否健康存活。
     * helper 的第一件事是等本进程退出，健康的话此刻必然还活着；若它因语法/权限问题秒退，
     * 则返回 false 放弃自更新——否则本进程退出后就没人重启了。
     */
    private suspend fun startDetached(script: File): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val proc = ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-File", script.absolutePath
            )
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
            Thread.sleep(1200)
            proc.isAlive
        }.getOrDefault(false)
    }

    private fun Route.browser() = authenticate("auth") {
        get("/") {
            call.respondRedirect("http://$serverIp/resources/browser.html")
        }
        get("/question/{...}") {
            call.respondRedirect("http://$serverIp/resources/browser.html")
        }
    }

    // 课表页：页面只读，作息硬编码在 timetable.html 的 BELL 数组里，科目内容来自 resources/timetable.json
    private fun Route.timetable() {
        authenticate("auth") {
            get("/timetable") {
                call.respondRedirect("/resources/timetable.html")
            }
            get("/timetable/now") {
                call.respondText(timetableNowJson(), ContentType.Application.Json)
            }
        }
    }

    /**
     * 课表高亮用的"现在"：星期与时分由服务端给，避免依赖客户端时钟。
     *
     * 为什么要服务端出时间：笔记本休眠唤醒后系统时钟常偏、浏览器时区也可能被改，
     * 页面自己 `new Date()` 会高亮到错的那一列/那一节。页面加载后调这个接口对一次时，
     * 之后本地按"服务端时间 + 已流逝时长"推进，所以不必轮询，也能在隔夜打开时保持正确。
     *
     * 字段：`day` 周序（周一=0 … 周日=6，与 timetable.json 的格子键一致）、
     * `hour`/`minute` 本地时分、`minutes` 当天已过分钟数、`date` `yyyy-MM-dd`、
     * `epochMs` 服务端当前毫秒时间戳（客户端据此算出与本机时钟的差，不要用 `offsetMs`
     * 那种"客户端减服务端"的字段 —— 服务端拿不到客户端时钟，只能给出自己的绝对时间）。
     */
    private fun timetableNowJson(): String {
        val now = Time.now()
        // java.time.DayOfWeek 是周一=1 … 周日=7，减 1 正好对上页面/JSON 的周序
        val day = now.dayOfWeek.value - 1
        val date = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val minutes = now.hour * 60 + now.minute
        return buildJsonObject {
            put("day", day)
            put("dayName", DAY_NAMES[day])
            put("hour", now.hour)
            put("minute", now.minute)
            put("minutes", minutes)
            put("date", date)
            put("time", now.format(DateTimeFormatter.ofPattern("HH:mm")))
            put("epochMs", now.toInstant().toEpochMilli())
            // 教学周：第 1 周从 2026-08-31（周一）算，单周周六上午 A 课表、双周 B 课表
            put("week", Timetable.weekOf(now))
            put("satTrack", if (Timetable.isEvenWeek(now)) "B" else "A")
        }.toString()
    }

    // 周序与名称的对应（周一=0），与前端 DAY 常量一致
    private val DAY_NAMES = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    private fun Route.download() = get("/download") {
        val fileUrl = call.request.queryParameters["url"]
            ?: return@get call.respondText("Please provide URL parameter", status = HttpStatusCode.BadRequest)
        val inline = call.request.queryParameters["inline"] == "1"
        val client = HttpClient.client
        val tempFile: Path = Files.createTempFile("download_", ".tmp")
        try {
            val response = client.get(fileUrl)
            if (response.status.isSuccess()) {
                val bytes: ByteArray = response.bodyAsBytes()
                val originalFileName = extractFileName(fileUrl, response)
                val contentType = response.headers[HttpHeaders.ContentType]
                    ?: when {
                        originalFileName.endsWith(".css") -> "text/css"
                        originalFileName.endsWith(".js") -> "application/javascript"
                        originalFileName.endsWith(".html") -> "text/html"
                        else -> "application/octet-stream"
                    }
                var body = bytes
                // Rewrite relative URLs in CSS/HTML to absolute CDN URLs
                if (inline && (contentType == "text/css" || contentType == "text/html" || contentType.contains("css") || contentType.contains("html"))) {
                    val text = bytes.toString(Charsets.UTF_8)
                    // Handle url() patterns in CSS: url(path), url('path'), url("path")
                    val rewritten = text.replace(Regex("""url\(\s*['"]?([^)'"\s]+)['"]?\s*\)""")) { match ->
                        val relPath = match.groupValues[1]
                        if (relPath.startsWith("http://") || relPath.startsWith("https://") || relPath.startsWith("data:")) {
                            match.value // Already absolute, skip
                        } else {
                            val absUrl = resolveRelativeUrl(fileUrl, relPath)
                            "url($absUrl)"
                        }
                    }
                    // Handle src="" and href="" attributes (only relative paths)
                    val fullyRewritten = rewritten.replace(Regex("""(\s(?:src|href)\s*=\s*['"])(\.\.?/[^'"]+)['"]""")) { match ->
                        val relPath = match.groupValues[2]
                        if (relPath.startsWith("http://") || relPath.startsWith("https://") || relPath.startsWith("data:")) {
                            match.value
                        } else {
                            val absUrl = resolveRelativeUrl(fileUrl, relPath)
                            "${match.groupValues[1]}$absUrl'"
                        }
                    }
                    body = fullyRewritten.toByteArray(Charsets.UTF_8)
                }
                Files.write(tempFile, body, StandardOpenOption.WRITE)
                call.response.headers.apply {
                    append(HttpHeaders.ContentDisposition,
                        if (inline) "inline; filename=\"$originalFileName\""
                        else "attachment; filename=\"$originalFileName\"")
                    // 图片（music 页的封面走这里）URL 稳定、内容不变，给一天缓存，避免每次
                    // 进页面都重新回源下载；音乐/其它文件保持 no-store，避免用户点"下载"
                    // 时拿到磁盘缓存里的旧文件。
                    if (contentType.startsWith("image/")) {
                        append(HttpHeaders.CacheControl, "public, max-age=86400")
                    } else {
                        append(HttpHeaders.CacheControl, "no-cache, no-store, must-revalidate")
                    }
                    append(HttpHeaders.ContentType, contentType)
                }
                call.respondFile(tempFile.toFile())
            } else {
                call.respondText("Failed to download: ${response.status}",
                                status = HttpStatusCode.InternalServerError)
            }
        } catch (e: Exception) {
            call.respondText("Error: ${e.message}", status = HttpStatusCode.InternalServerError)
        } finally {
            // client 为 HttpClient.client 共享实例，不可 close；仅清理临时文件
            Files.deleteIfExists(tempFile)
        }
    }

    /** 从 JDK HttpClient 的上游响应头/URL 推断文件名（/update 用；解析不到时由调用方兜底） */
    private fun fileNameFromHeaders(url: String, up: Http.Upstream): String {
        val cd = up.header("content-disposition")
        if (cd != null) {
            Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)"?""", RegexOption.IGNORE_CASE).find(cd)?.let {
                return java.net.URLDecoder.decode(it.groupValues[1].trim(), "UTF-8")
            }
        }
        val fromUrl = url.substringAfterLast("/").substringBefore("?")
        return fromUrl.ifBlank { "downloaded_file" }
    }

    private fun resolveRelativeUrl(base: String, relative: String): String {
        val baseUri = java.net.URI(base)
        val resolved = baseUri.resolve(relative)
        return resolved.toString()
    }

    private fun extractFileName(url: String, response: HttpResponse): String {
        val contentDisposition = response.headers[HttpHeaders.ContentDisposition]
        if (contentDisposition != null) {
            val regex = "filename=\"?(.*?)\"?[;\\s]".toRegex()
            regex.find(contentDisposition)?.let {
                return it.groupValues[1]
            }
        }
        val fromUrl = url.substringAfterLast("/").substringBefore("?")
        if (fromUrl.isNotBlank()) {
            return fromUrl
        }
        return "downloaded_file"
    }
}