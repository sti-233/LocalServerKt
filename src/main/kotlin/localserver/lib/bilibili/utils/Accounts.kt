package localserver.lib.bilibili.utils

import kotlinx.coroutines.runBlocking

import localserver.types.bilibili.Buvid3
import localserver.types.bilibili.Wbi
import localserver.utils.HttpClient

object Accounts {
    val buvid3 by lazy {
        runBlocking {
            HttpClient.getAs<Buvid3>("https://api.bilibili.com/x/web-frontend/getbuvid").data.buvid
        }
    }

    val wbi by lazy {
        runBlocking {
            HttpClient.getAs<Wbi>("https://api.bilibili.com/x/web-interface/nav").data.wbi_img
        }
    }
}