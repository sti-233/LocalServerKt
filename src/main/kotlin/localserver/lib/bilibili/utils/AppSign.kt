package localserver.lib.bilibili.utils

import localserver.utils.Hashs.md5Hex
import localserver.utils.Hashs.urlEncoded

/**
 * B 站 APP 端接口签名（对应 PiliPlus 的 lib/utils/app_sign.dart）。
 *
 * 算法：把所有参数（追加 appkey 与 ts 后）按 key 升序排列，
 * 拼成 `k1=v1&k2=v2...`（key/value 均做 URL 编码，空值只留 key），
 * 再在末尾拼接 appsec，取 MD5 即为 sign。
 *
 * 注意：APP 接口（app.bilibili.com）比 Web 接口风控宽松得多，
 * 实测同一 IP 下 Web 的 acc/info、arc/search 被 -352/-412 拦截时，
 * APP 的 /x/v2/space 与 /x/v2/space/archive/cursor 依然正常返回。
 */
object AppSign {
    // 与 PiliPlus 一致的 Android 端 appkey/appsec
    const val APP_KEY = "dfca71928277209b"
    const val APP_SEC = "b5475a8825547a4fc26c7d518eaaa02e"

    // APP 接口要求的固定参数
    const val BUILD = "8430300"
    const val VERSION = "8.43.0"
    const val MOBI_APP = "android"
    const val PLATFORM = "android"
    const val CHANNEL = "master"
    const val C_LOCALE = "zh_CN"
    const val S_LOCALE = "zh_CN"
    const val STATISTICS = """{"appId":1,"platform":3,"version":"8.43.0","abtest":""}"""

    const val USER_AGENT =
        "Mozilla/5.0 BiliDroid/8.43.0 (bbcallen@gmail.com) os/android model/android mobi_app/android build/8430300 channel/master innerVer/8430300 osVer/15 network/2"

    /** APP 接口的公共参数（不含 vmid 等业务参数） */
    fun baseParams(): MutableMap<String, Any> = mutableMapOf(
        "build" to BUILD,
        "version" to VERSION,
        "c_locale" to C_LOCALE,
        "channel" to CHANNEL,
        "mobi_app" to MOBI_APP,
        "platform" to PLATFORM,
        "s_locale" to S_LOCALE,
        "statistics" to STATISTICS
    )

    /**
     * 对参数表签名。会直接修改传入的 map（追加 appkey/ts/sign），并返回它。
     * 空值（null 或 ""）按文档只输出 key，不加 `=`。
     */
    fun sign(params: MutableMap<String, Any>): Map<String, Any> {
        params["appkey"] = APP_KEY
        params["ts"] = (System.currentTimeMillis() / 1000).toString()
        val query = params.entries
            .sortedBy { it.key }
            .joinToString("&") { (k, v) ->
                val value = v.toString()
                if (value.isEmpty()) k.urlEncoded else "$k=${value.urlEncoded}"
            }
        params["sign"] = (query + APP_SEC).toByteArray().md5Hex
        return params
    }
}
