package localserver.lib.bilibili

import localserver.lib.bilibili.utils.Accounts
import localserver.lib.bilibili.utils.WbiSign
import localserver.types.bilibili.SearchByType
import localserver.types.bilibili.SearchByTypeData
import localserver.utils.Hashs.urlEncoded
import localserver.utils.HttpClient

object Search {
    suspend fun searchByType(keyword: String): SearchByTypeData? {
        val buvid3 = Accounts.buvid3
        val parameters = WbiSign.sign(mapOf(
                            "search_type" to "video",
                            "keyword" to keyword,
                        ))
        val header = mapOf(
                        "cookie" to "buvid3=$buvid3",
                        "origin" to "https://search.bilibili.com",
                        "referer" to "https://search.bilibili.com/video?keyword=${keyword.urlEncoded}"
                    )
        val json = HttpClient.getAs<SearchByType>("https://api.bilibili.com/x/web-interface/search/type", parameters, header).data
        return json
    }
}