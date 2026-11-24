package org.steamlite

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** New accounts may not have a wallet. Read Steam's currency for the verified country. */
internal object StoreCurrencyResolver {
    private val http = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    suspend fun resolve(country: String): String = withContext(Dispatchers.IO) {
        require(country.matches(Regex("[A-Z]{2}")))
        val url = "https://store.steampowered.com/api/featuredcategories?cc=$country&l=schinese"
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful) { "商店币种查询暂不可用" }
            val json = JSONObject(response.body?.string().orEmpty())
            for (category in listOf("specials", "top_sellers", "new_releases")) {
                val items = json.optJSONObject(category)?.optJSONArray("items") ?: continue
                for (index in 0 until items.length()) {
                    val currency = items.optJSONObject(index)?.optString("currency").orEmpty()
                    if (StoreRegion.from(country, currency) != null) return@withContext currency
                }
            }
            error("Steam 尚未返回该地区币种")
        }
    }
}
