# 由 LocalServerKt /update 在运行时渲染并执行的自更新 helper。
#
# 为什么需要它：Windows 上运行中的 JVM 锁着自身 jar，进程内既无法覆盖也无法重命名，
# 且新代码必须由新进程加载，所以替换+重启必须交给这个进程外的脚本来做。
#
# 本文件源自仓库内的模板 src/main/resources/update-helper.ps1，运行时由
# Kotlin 侧 Network.buildUpdateHelper() 替换下面的占位符后落盘执行：
#   日志/旧 jar/新 jar/备份/工作目录 → 已做 PowerShell 单引号转义的路径字面量
#   重启可执行文件 + 参数            → 原命令行
#   旧进程号 + 新 jar 期望字节数     → 用于等待退出与落地校验
#
# 脚本流程：等旧进程退出（释放 jar 锁）→ 备份旧 jar → 用同名落地新 jar → 重启并确认存活；
# 新 jar 启动即崩则回滚旧 jar 并重启，坏 jar 留作 .failed 取证。

$ErrorActionPreference = 'Stop'

$log = __LOG__
function Write-Log($m) {
    "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') $m" | Add-Content -Path $log -Encoding UTF8
}

$jar      = __JAR__
$new      = __NEW__
$bak      = __BAK__
$exe      = __EXE__
$argLine  = __ARGLINE__
$cwd      = __CWD__
$oldPid   = __PID__
$wantSize = __SIZE__

Write-Log "helper start: jar=$jar size=$wantSize pid=$oldPid"

# 1) 等旧进程退出（最多 60s），否则 jar 文件锁不会释放
$deadline = (Get-Date).AddSeconds(60)
while ((Get-Date) -lt $deadline) {
    if (-not (Get-Process -Id $oldPid -ErrorAction SilentlyContinue)) { break }
    Start-Sleep -Milliseconds 500
}
if (Get-Process -Id $oldPid -ErrorAction SilentlyContinue) {
    Write-Log "old process $oldPid still alive after 60s, abort"
    exit 1
}
Write-Log "old process exited"
Start-Sleep -Milliseconds 800

# 2) 备份旧 jar，再用同名落地新 jar；落地后校验字节数，避免半个文件变成"砖"
try {
    if (Test-Path $bak) { Remove-Item $bak -Force }
    Move-Item -LiteralPath $jar -Destination $bak -Force
    Copy-Item -LiteralPath $new -Destination $jar -Force
    $got = (Get-Item -LiteralPath $jar).Length
    if ($got -ne $wantSize) { throw "size mismatch: $got != $wantSize" }
    Write-Log "replaced jar with same name ($got bytes)"
} catch {
    Write-Log "replace failed: $($_.Exception.Message)"
    if (Test-Path $bak) {
        Move-Item -LiteralPath $bak -Destination $jar -Force -ErrorAction SilentlyContinue
    }
    exit 1
}

# 3) 按原命令行 + 原工作目录重启，并确认新进程存活；起不来则回滚
try {
    $proc = Start-Process -FilePath $exe -ArgumentList $argLine `
        -WorkingDirectory $cwd -WindowStyle Hidden -PassThru
    Start-Sleep -Seconds 8
    if ($proc.HasExited) {
        Write-Log "restart exited early (code $($proc.ExitCode)), rolling back"
        Move-Item -LiteralPath $jar -Destination "${jar}.failed" -Force -ErrorAction SilentlyContinue
        Move-Item -LiteralPath $bak -Destination $jar -Force
        $rb = Start-Process -FilePath $exe -ArgumentList $argLine `
            -WorkingDirectory $cwd -WindowStyle Hidden -PassThru
        Write-Log "rolled back to old jar, restart pid=$($rb.Id)"
        exit 1
    }
    Write-Log "restarted ok, new pid=$($proc.Id)"
} catch {
    Write-Log "restart failed: $($_.Exception.Message)"
    exit 1
}

# 4) 成功：清理临时文件与旧备份
Remove-Item -LiteralPath $new -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $bak -Force -ErrorAction SilentlyContinue
Write-Log "helper done"
