// Copyright (c) 2025 guang233
// This code is licensed under MIT license (see LICENSE for details)
package localserver.lib.NeteaseMusicApi.network

import localserver.types.*
import localserver.lib.NeteaseMusicApi.utils.AESECBHelper
import localserver.lib.NeteaseMusicApi.utils.Decrypt
import localserver.utils.Hashs.md5Hex
import localserver.utils.Hashs.toHexString
import localserver.utils.HttpClient

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import java.util.zip.GZIPInputStream

object MusicNetwork {
    private const val BASE_URL = "https://interfacepc.music.163.com/"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Safari/537.36 Chrome/91.0.4472.164 NeteaseMusicDesktop/3.1.23.204750"

    suspend fun searchMusic(keyword: String, offset: Int, limit: Int, cookie: String): List<Music> {
        val bodyJson =
            "{\"keyword\":\"$keyword\",\"scene\":\"NORMAL\",\"limit\":\"$limit\",\"offset\":\"$offset\",\"needCorrect\":\"true\",\"e_r\":true,\"checkToken\":\"\",\"header\":\"\"}"
        return Decrypt.decryptSearch(
            eapi("eapi/search/song/list/page", "api/search/song/list/page", bodyJson, cookie)
        )
    }

    suspend fun getLyrics(id: String, cookie: String): Lyric {
        val bodyJSON =
            "{\"id\":\"$id\",\"lv\":\"-1\",\"tv\":\"-1\",\"rv\":\"-1\",\"yv\":\"-1\",\"e_r\":true,\"header\":\"\"}"
        return Decrypt.decryptLytic(
            eapi("eapi/song/lyric/v1", "api/song/lyric/v1", bodyJSON, cookie)
        )
    }

    suspend fun getMusicUrl(id: String, level: String, cookie: String): MusicUrl {
        val bodyJSON =
            "{\"ids\":\"[\\\"$id\\\"]\",\"level\":\"$level\",\"immerseType\":\"c51\",\"encodeType\":\"aac\",\"trialMode\":\"-1\",\"e_r\":true,\"header\":\"\"}"
        return Decrypt.decryptMusicUrl(
            eapi("eapi/song/enhance/player/url/v1", "api/song/enhance/player/url/v1", bodyJSON, cookie)
        )
    }

    // 后经过测试发现，如要获取完整歌单，必须传 cookie
    suspend fun getPlayList(id: String, cookie: String): PlayList {
        val bodyJSON =
            "{\"id\":\"$id\",\"n\":\"10000\",\"s\":\"0\",\"newStyle\":\"true\",\"e_r\":true,\"checkToken\":\"\",\"header\":\"\"}"
        return Decrypt.decryptPlayList(
            eapi("eapi/v6/playlist/detail", "api/v6/playlist/detail", bodyJSON, cookie)
        )
    }

    suspend fun getAlbum(id: String, cookie: String): PlayList {
        val cacheKey = AESECBHelper.encrypt(
            input = "e_r=true&id=$id",
            outputFormat = AESECBHelper.Format.BASE64,
            secretKey = ")(13daqP@ssw0rd~".toByteArray(Charsets.UTF_8)
        )
        val bodyJSON = "{\"id\":\"$id\",\"e_r\":true,\"cache_key\":\"$cacheKey\",\"header\":\"\"}"
        return Decrypt.decryptAlbum(
            eapi("eapi/album/v3/detail", "api/album/v3/detail", bodyJSON, cookie)
        )
    }

    /**
     * eapi 协议公共请求：拼 eapi 协议串 → AES-ECB 加密 → 表单 POST。
     * 响应是加密后的字节流，不能当 UTF-8 文本读（会出现 EF BF BD 乱码），
     * 故直接取原始字节转 hex 返回（原 OkHttp 版的 HexResponseInterceptor 即此用途）。
     */
    private suspend fun eapi(path: String, apiPath: String, bodyJson: String, cookie: String): String {
        val md5 = "nobody/${apiPath}use${bodyJson}md5forencrypt".encodeToByteArray().md5Hex
        val query = "/$apiPath-36cd479b6b5-$bodyJson-36cd479b6b5-$md5"
        val response = HttpClient.client.post("$BASE_URL$path") {
            header(HttpHeaders.Cookie, cookie)
            header(HttpHeaders.UserAgent, UA)
            // 共享 client 没装 ContentNegotiation，表单体直接 urlencode 成字符串发
            header(HttpHeaders.ContentType, "application/x-www-form-urlencoded")
            setBody("params=${java.net.URLEncoder.encode(AESECBHelper.encrypt(query), "UTF-8")}")
        }
        var bytes = response.bodyAsBytes()
        // ponytail: 不主动声明 br（未引入 brotli 解码库），最多回 gzip，按魔数识别后用标准库解压
        if (bytes.size >= 2 && bytes[0].toInt() == 0x1F && bytes[1].toInt() == 0x8B) {
            bytes = GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
        }
        return bytes.toHexString()
    }
}
