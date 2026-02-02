package LocalServer.NeteaseMusicApi.network

import retrofit2.Call
import retrofit2.http.*

interface MusicService {
    @FormUrlEncoded
    @POST("eapi/search/song/list/page")
    fun searchMusic(
        @Field("params") body: String,
        @Header("Cookie") cookie: String
    ): Call<String>

    @FormUrlEncoded
    @POST("eapi/song/enhance/player/url/v1")
    fun getMusic(
        @Field("params") body: String,
        @Header("Cookie") cookie: String
    ): Call<String>

    @FormUrlEncoded
    @POST("eapi/song/lyric/v1")
    fun getLyric(
        @Field("params") body: String,
        @Header("Cookie") cookie: String
    ): Call<String>

    @FormUrlEncoded
    @POST("eapi/v6/playlist/detail")
    fun getPlayList(
        @Field("params") body: String,
        @Header("Cookie") cookie: String
    ): Call<String>

    @FormUrlEncoded
    @POST("eapi/album/v3/detail")
    fun getAlbum(
        @Field("params") body: String,
        @Header("Cookie") cookie: String
    ): Call<String>
}

