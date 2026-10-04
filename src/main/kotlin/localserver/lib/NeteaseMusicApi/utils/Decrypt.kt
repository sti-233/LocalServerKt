// Copyright (c) 2025 guang233
// This code is licensed under MIT license (see LICENSE for details)
package localserver.lib.NeteaseMusicApi.utils

import localserver.types.*
import kotlinx.serialization.json.*

object Decrypt {
    private val json = Json

    /** 取子字段为字符串，缺失或非字符串返回 null */
    private fun JsonObject?.str(key: String): String? =
        this?.get(key)?.jsonPrimitive?.contentOrNull

    /** 取子字段为数字（Long），缺失返回 0 */
    private fun JsonObject?.long(key: String): Long =
        this?.get(key)?.jsonPrimitive?.content?.toLongOrNull() ?: 0L

    private fun parseObject(body: String): JsonObject =
        json.parseToJsonElement(body).jsonObject

    fun decryptSearch(encryptedBody: String): List<Music> {
        val data = parseObject(AESECBHelper.decrypt(encryptedBody))

        if (data.str("code")?.toIntOrNull() != 200) {
            throw Exception("Invalid data: ${data.str("message") ?: "Unknown error"}")
        }

        val resources = data["data"]?.jsonObject?.get("resources")?.jsonArray
            ?: throw IllegalStateException("No resources found in response")
        return resources.indices.map { i ->
            val simpleSongData = resources[i].jsonObject
                .get("baseInfo")?.jsonObject?.get("simpleSongData")?.jsonObject
                ?: throw IllegalStateException("Missing song data at index $i")
            parseSong(simpleSongData)
        }
    }

    private fun parseSong(simpleSongData: JsonObject): Music {
        val albumObj = simpleSongData["al"]?.jsonObject
            ?: throw IllegalStateException("Missing album data")

        val album = Album(
            name = albumObj.str("name").orEmpty(),
            id = albumObj.long("id"),
            picUrl = albumObj.str("picUrl").orEmpty()
        )

        val artists = simpleSongData["ar"]?.jsonArray?.map { a ->
            val artist = a.jsonObject
            Artist(name = artist.str("name").orEmpty(), id = artist.long("id"))
        } ?: emptyList()

        return Music(
            name = simpleSongData.str("name").orEmpty(),
            artists = artists,
            album = album,
            id = simpleSongData.long("id")
        )
    }

    fun decryptLytic(encryptedBody: String): Lyric {
        val data = parseObject(AESECBHelper.decrypt(encryptedBody))
        val lrc = parseMixedLyrics(data["lrc"]?.jsonObject?.str("lyric").orEmpty())
        val tlyric = data["tlyric"]?.jsonObject?.str("lyric").orEmpty()
        val romalrc = data["romalrc"]?.jsonObject?.str("lyric").orEmpty()

        val yrc = convertMultiLineToLrc(parseMixedLyrics(data["yrc"]?.jsonObject?.str("lyric").orEmpty()))
        val ytlrc = convertMultiLineToLrc(data["ytlrc"]?.jsonObject?.str("lyric").orEmpty())
        val yromalrc = convertMultiLineToLrc(data["yromalrc"]?.jsonObject?.str("lyric").orEmpty())

        return Lyric(lrc, tlyric, romalrc, yrc, ytlrc, yromalrc)
    }

    private fun parseMixedLyrics(input: String): String {
        val lines = input.split("\n")
        val result = StringBuilder()
        lines.forEach { line ->
            when {
                line.startsWith("{") && line.endsWith("}") -> {
                    try {
                        val obj = json.parseToJsonElement(line).jsonObject
                        val timeMs = obj["t"]!!.jsonPrimitive.content.toLong()

                        val timeLabel = formatTime(timeMs)

                        val content = buildString {
                            val array = obj["c"]!!.jsonArray
                            for (item in array) {
                                append(item.jsonObject["tx"]!!.jsonPrimitive.content)
                            }
                        }

                        result.append("[$timeLabel]$content\n")
                    } catch (e: Exception) {
                        result.append("$line\n")
                    }
                }

                line.startsWith("[") && line.contains("]") -> {
                    result.append("$line\n")
                }

                else -> {
                    result.append("$line\n")
                }
            }
        }
        return result.toString().trim()
    }

    private fun convertMultiLineToLrc(input: String): String =
        input.lines()
            .joinToString("\n") { line -> convertSingleLine(line) }

    private fun convertSingleLine(line: String): String {
        val result = StringBuilder()

        if (line.matches(Regex("""\[(\d+),(\d+)].+"""))) {
            // 1. 提取 [开始,结束]
            val lineTimeRegex = Regex("""\[(\d+),(\d+)]""")
            val timeMatch = lineTimeRegex.find(line)
            val endTimeMs = timeMatch?.groupValues?.run { get(2).toLongOrNull()!! + get(1).toLongOrNull()!! }!!

            // 2. 提取逐字部分
            val wordRegex = Regex("""\((\d+),\d+,\d+\)([\s\S]*?)(?=(?:\(\d+|\[\d+)|$)""")
            val matches = wordRegex.findAll(line)

            for (match in matches) {
                val wordTime = match.groupValues[1].toLongOrNull() ?: 0L
                val word = match.groupValues[2]
                result.append("[${formatTime(wordTime)}]").append(word)
            }

            // 3. 最后追加结尾时间戳
            if (endTimeMs > 0) {
                result.append("[${formatTime(endTimeMs)}]")
            }
            return result.toString()
        } else
            return line
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val millis = ms % 1000
        return String.format("%02d:%02d.%03d", minutes, seconds, millis)
    }

    fun decryptMusicUrl(encryptedBody: String): MusicUrl {
        val data = parseObject(AESECBHelper.decrypt(encryptedBody))
            .get("data")?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw IllegalStateException("Missing music url data")

        val url = data.str("url").orEmpty()
        val level = data.str("level").orEmpty()

        return MusicUrl(url, level)
    }

    fun decryptPlayList(encryptedBody: String): PlayList {
        val data = parseObject(AESECBHelper.decrypt(encryptedBody))["playlist"]?.jsonObject
            ?: throw IllegalStateException("Missing playlist data")

        val name = data.str("name").orEmpty()
        val coverImgUrl = data.str("coverImgUrl").orEmpty()
        val id = data.long("id")

        val tracks = data["tracks"]?.jsonArray ?: JsonArray(emptyList())
        val musics = tracks.map { t ->
            t.jsonObject?.let { parseSong(it) }
                ?: throw IllegalStateException("Missing track data")
        }

        return PlayList(name, coverImgUrl, musics, id)
    }

    fun decryptAlbum(encryptedBody: String): PlayList {
        val data = parseObject(AESECBHelper.decrypt(encryptedBody))
        val album = data["album"]?.jsonObject
            ?: throw IllegalStateException("Missing album data")
        val name = album.str("name").orEmpty()
        val coverImgUrl = album.str("picUrl").orEmpty()
        val id = album.long("id")

        val songs = data["songs"]?.jsonArray ?: JsonArray(emptyList())
        val musics = songs.map { s ->
            s.jsonObject?.let { parseSong(it) }
                ?: throw IllegalStateException("Missing song data")
        }
        return PlayList(name, coverImgUrl, musics, id)
    }
}
