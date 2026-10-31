package localserver.module

import localserver.utils.Logger
import localserver.utils.defaultDir

import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentHashMap

// 终端模块：/terminal 跳页到 terminal.html，WS /terminal 执行 PowerShell
// 每个客户端 IP 持有一个常驻 powershell 进程（cwd、变量等状态得以保留）；
// 帧协议：客户端发 {"cmd":"..."}，服务端回 {"out":"输出\n===EXIT <code>"}；
// 命令是 cls/clear 时返回特殊标记 "CLEAR" 让前端清屏；
// 命令把进程弄死（如 exit）时自动重启进程并告知；断连时销毁进程
object Terminal {
    private val sessions = ConcurrentHashMap<String, TerminalSession>()
    private const val MARKER = "===EXIT==="

    fun Route.terminalRoute() {
        page()
        terminal()
    }

    private fun Route.page() = authenticate("control") {
        get("/terminal") {
            call.respondRedirect("/resources/terminal.html")
        }
    }

    private fun Route.terminal() = authenticate("control") {
        webSocket("/terminal") {
            val clientId = call.request.local.remoteAddress
            val session = sessions.getOrPut(clientId) { TerminalSession() }
            Logger.debug("Terminal client $clientId connected. Sessions: ${sessions.size}")

            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val msg = Json.decodeFromString<Msg>(frame.readText())
                    val cmd = msg.cmd?.trim().orEmpty()
                    if (cmd.isEmpty()) continue
                    val out = if (cmd.equals("cls", true) || cmd.equals("clear", true)) "CLEAR"
                    else session.exec(cmd)
                    send(Json.encodeToString(Msg(out = out)))
                }
            } catch (e: ClosedReceiveChannelException) {
                Logger.debug("Terminal client $clientId disconnected")
            } catch (e: Exception) {
                Logger.error("Error with terminal client $clientId: ${e.message}")
            } finally {
                sessions.remove(clientId)?.destroy()
            }
        }
    }

    @kotlinx.serialization.Serializable
    data class Msg(val cmd: String? = null, val out: String? = null)

    private class TerminalSession {
        // 与 /defaultFileDir 相同：服务端运行 jar 所在目录；拿不到时退回用户目录
        private val dir = defaultDir().ifEmpty { System.getProperty("user.home") }
        // 编码必须在启动参数里设：控制台的格式化输出（ls/Format-Table 的表头与文件名）
        // 编码在进程启动时就定型，事后往 stdin 里写 [Console]::OutputEncoding 只影响字符串输出，
        // 格式化通道仍按启动时的活动代码页（中文系统 936）写出，前端按 UTF-8 解就是乱码。
        // InputEncoding 也要设，否则含中文的命令/路径会被按 GBK 解码成乱码。
        // Get-Content 的默认值另需指定：PowerShell 5.1 读无 BOM 文件按系统 ANSI(936) 解，
        // 现代编辑器存的 UTF-8 文件会变成"浣犲ソ"这类乱码；读 GBK 旧文件需显式 -Encoding Default。
        private val builder = ProcessBuilder(
            "powershell.exe", "-NoLogo", "-NonInteractive",
            "-NoExit", "-Command",
            "[Console]::InputEncoding = [System.Text.Encoding]::UTF8; " +
                "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; " +
                "\$PSDefaultParameterValues['Get-Content:Encoding'] = 'utf8'"
        )
            .redirectErrorStream(true)
            .directory(File(dir))
        private lateinit var process: Process
        // Java Process 流命名坑：写命令用 process.outputStream（进程的 stdin），读输出用 process.inputStream（进程的 stdout，已合并 stderr）
        private lateinit var input: BufferedWriter
        private lateinit var output: BufferedReader

        init { start() }

        // 每次（重）启动进程时的同步：编码已在启动参数里设好，这里只发一行标记把启动输出（PowerShell banner）丢干净
        // 标记必须发，drain 靠读到标记行终止，否则 init 会一直阻塞，后续命令全无输出
        private fun start() {
            val p = builder.start()
            process = p
            input = BufferedWriter(OutputStreamWriter(p.outputStream, Charsets.UTF_8))
            output = BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8))
            input.write("Write-Host('$MARKER' + \$LASTEXITCODE)\n")
            input.flush()
            drain()
        }

        // 命令后追加一行标记输出退出码，读到标记为止。
        // 正常退出（码为 0）时不返回 EXIT 行；非 0 才附带 "===EXIT <code>"。
        // ponytail: 无超时，交互式/挂起命令（notepad 等）会卡住本连接，需要时按行加超时
        fun exec(cmd: String): String {
            val suffix = "; Write-Host('$MARKER' + \$LASTEXITCODE)"
            input.write(cmd + suffix)
            input.write("\n")
            input.flush()
            val sb = StringBuilder()
            while (true) {
                val line = output.readLine() ?: break
                when {
                    line.startsWith(MARKER) -> {
                        val code = line.removePrefix(MARKER).trim().ifEmpty { "0" }
                        if (code != "0") sb.append('\n').append("===EXIT ").append(code)
                        return sb.toString()
                    }
                    // 丢弃 PowerShell 回显的 prompt 行（形如 "PS D:\...> <cmd>; Write-Host('===EXIT===' ...)"）
                    line.contains("Write-Host('$MARKER'") -> Unit
                    else -> sb.append(line).append('\n')
                }
            }
            // 输出流 EOF：进程被命令弄死（如 exit），重启一个新进程
            start()
            return sb.toString() + "\n[进程已退出，终端已重启，目录回到 $dir]"
        }

        // 读取并丢弃到标记（或 EOF）为止的输出
        private fun drain() {
            while (true) {
                val line = output.readLine() ?: return
                if (line.startsWith(MARKER)) return
            }
        }

        fun destroy() = runCatching { process.destroy() }
    }
}
