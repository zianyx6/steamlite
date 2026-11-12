package org.steamlite

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException

data class Game(val id: Int, val name: String, val image: String, val price: String, val discount: Int, val original: String = "", val cents: Long? = null, val release: String = "", val positivePercent: Int? = null)
data class StoreResult(val games: List<Game>, val cached: Boolean, val time: Long)
class StoreRepository(private val dao: CacheDao) {
    val observations = PriceObservations(dao)
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).callTimeout(35, TimeUnit.SECONDS).build()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private suspend fun fetch(url: String, refresh: Boolean): Triple<String, Boolean, Long> = locks.getOrPut(url) { Mutex() }.withLock { fetchInternal(url, refresh) }
    private suspend fun fetchInternal(url: String, refresh: Boolean): Triple<String, Boolean, Long> = withContext(Dispatchers.IO) {
        val cached = dao.cached(url)
        if (!refresh && cached != null && System.currentTimeMillis() - cached.time < 300000) return@withContext Triple(cached.body, true, cached.time)
        try {
            http.newCall(Request.Builder().url(url).build()).execute().use {
                check(it.isSuccessful) { "HTTP ${it.code}" }
                val body = it.body?.string() ?: error("空响应")
                JSONObject(body)
                val now = System.currentTimeMillis(); dao.cache(CachedResponse(url, body, now)); Triple(body, false, now)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { cached?.let { Triple(it.body, true, it.time) } ?: throw e }
    }
    suspend fun featured(refresh: Boolean): StoreResult = withContext(Dispatchers.Default) {
        val (body, cached, time) = fetch("https://store.steampowered.com/api/featuredcategories?cc=pk&l=schinese", refresh)
        val items = JSONObject(body).optJSONObject("specials")?.optJSONArray("items") ?: error("Steam 折扣数据不可用")
        val games = (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            val cents = item.optLong("final_price"); val original = item.optLong("original_price")
            val currency = item.optString("currency")
            val image = item.optString("large_capsule_image").ifBlank { item.optString("header_image") }
                .ifBlank { "https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/${item.getInt("id")}/header.jpg" }
            Game(item.getInt("id"), item.getString("name"), image, "$currency %.2f".format(cents / 100.0), item.optInt("discount_percent"), if(original > cents) "$currency %.2f".format(original / 100.0) else "", if(currency == "USD") cents else null)
        }.filter { it.discount > 0 }.distinctBy { it.id }
        if(!cached) games.forEach { game -> game.cents?.let { observations.record(game.id, it, time, "Steam featuredcategories · PK · USD") } }
        StoreResult(games, cached, time)
    }
    suspend fun search(query: String, refresh: Boolean): StoreResult {
        val (body, cached, time) = fetch("https://store.steampowered.com/api/storesearch/?term=${URLEncoder.encode(query, "UTF-8")}&l=schinese&cc=pk", refresh)
        val items = JSONObject(body).optJSONArray("items") ?: error("商店返回格式不受支持")
        return StoreResult((0 until items.length()).map { index ->
            val item = items.getJSONObject(index); val price = item.optJSONObject("price"); val initial = price?.optInt("initial") ?: 0; val final = price?.optInt("final") ?: 0
            val currency = price?.optString("currency").orEmpty()
            Game(item.getInt("id"), item.getString("name"), item.optString("tiny_image"), if (price == null) "价格不可用" else "$currency %.2f".format(final / 100.0), if (initial > 0) ((initial - final) * 100 / initial) else 0, if (initial > final) "$currency %.2f".format(initial / 100.0) else "", if(currency == "USD") final.toLong() else null)
        }, cached, time)
    }
    suspend fun searchPage(query: String, start: Int, options: StoreSearchOptions, refresh: Boolean): StorePage = withContext(Dispatchers.Default) {
        require(start >= 0)
        val term = URLEncoder.encode(query, "UTF-8")
        val url = "https://store.steampowered.com/search/results/?term=$term&start=$start&count=20&infinite=1&cc=pk&l=schinese&sort_by=${URLEncoder.encode(options.order,"UTF-8")}&category1=${options.category}&specials=${if(options.discountOnly) "1" else ""}"
        val (body, cached, time) = fetch(url, refresh)
        val json = JSONObject(body)
        check(json.optInt("success") == 1 || json.optBoolean("success")) { "Steam 搜索返回失败" }
        val games = StoreSearchParser.games(json.optString("results_html"))
        if(!cached) games.forEach { game -> game.cents?.let { observations.record(game.id, it, time, "Steam search · PK · USD") } }
        StorePage(games, json.optInt("total_count"), start + 20, cached, time)
    }
    suspend fun details(id: Int): JSONObject = withContext(Dispatchers.Default) {
        val (body, cached, time) = fetch("https://store.steampowered.com/api/appdetails?appids=$id&cc=pk&l=schinese", false)
        val root = JSONObject(body).getJSONObject(id.toString()); check(root.optBoolean("success")) { "详情不可用" }
        val data = root.getJSONObject("data"); val price = data.optJSONObject("price_overview")
        if(!cached && price?.optString("currency") == "USD" && price.has("final")) observations.record(id, price.getLong("final"), time, "Steam appdetails · PK · USD")
        data
    }
    suspend fun wishlist(steamId: String): List<Int> {
        require(steamId.matches(Regex("[0-9]{17}"))) { "请输入 SteamID64" }
        val (body) = fetch("https://api.steampowered.com/IWishlistService/GetWishlist/v1/?steamid=$steamId", true)
        val response = JSONObject(body).optJSONObject("response") ?: error("愿望单不可用")
        val items = response.optJSONArray("items") ?: error("愿望单为空或不可公开访问")
        return (0 until items.length()).map { items.getJSONObject(it).getInt("appid") }
    }
}
