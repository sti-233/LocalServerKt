package localserver.lib.bilibili.utils

import java.util.TreeMap

import localserver.utils.Hashs.md5Hex
import localserver.utils.Hashs.urlEncoded

object WbiSign {
    private val mixinKeyEncTab = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
        33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
        61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
        36, 20, 34, 44, 52
    )

    suspend fun sign(query: Map<String, Any>): Map<String, Any> {
        val now = System.currentTimeMillis() / 1000
        val param = TreeMap(query).apply {
            put("wts", now)
        }.asSequence().joinToString(separator = "&") { (k, v) ->
            "$k=${v.toString().urlEncoded}"
        }
        val (imgKey, subKey) = fetchWbiInfo() ?: return query
        val mixinKey = getMixinKey(imgKey, subKey)
        val wbiSign = "$param$mixinKey".toByteArray().md5Hex
        return query.toMutableMap().apply {
            put("wts", now)
            put("w_rid", wbiSign)
        }
    }

    private suspend fun fetchWbiInfo(): Pair<String, String>? {
        val wbiImg = Accounts.wbi ?: return null
        val imgUrl = wbiImg.img_url
        val subUrl = wbiImg.sub_url
        val imgKey = imgUrl.substringAfterLast('/').substringBefore('.')
        val subKey = subUrl.substringAfterLast('/').substringBefore('.')
        return imgKey to subKey
    }

    private fun getMixinKey(imgKey: String, subKey: String): String {
        val s = imgKey + subKey
        val key = StringBuilder()
        for (i in 0 until 32)
            key.append(s[mixinKeyEncTab[i]])
        return key.toString()
    }
}