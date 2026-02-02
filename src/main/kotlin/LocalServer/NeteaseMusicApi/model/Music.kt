package LocalServer.NeteaseMusicApi.model

import kotlinx.serialization.Serializable

@Serializable data class Music(val name: String, val artists: List<Artist>, val album: Album, val id: Long)
@Serializable data class Artist(val name: String, val id: Long)
@Serializable data class Album(val name: String, val id: Long, val picUrl: String)
@Serializable data class PlayList(val name: String, val coverImgUrl: String, val musics: List<Music>, val id: Long)

@Serializable
data class Lyric(
    val lrc: String, val tlyric: String, val romalrc: String,
    val yrc: String, val ytlrc: String, val yromalrc: String
)

@Serializable data class MusicUrl(val url: String, val level: String)

@Serializable
data class MusicDownloadRules(
    val isSaveLrc: Boolean, val isSaveTlLrc: Boolean, val isSaveRomaLrc: Boolean, val isSaveYrc: Boolean,
    val fileName: String, val delimiter: String,
    val encoding: String, val concurrentDownloads: Int = 1
)