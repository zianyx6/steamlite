package org.steamlite

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
data class StoreResult(val games: List<Game>, val cached: Boolean, val time: Long, val recommendations: List<Game> = emptyList())
class StoreRepository(private val dao: CacheDao, private val steam: SteamConnection? = null) {
    suspend fun currentRegion(): StoreRegion = if (steam == null || !steam.hasSession.value) StoreRegion.Guest else steam.requireStoreRegion()
    val observations = PriceObservations(dao)
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).callTimeout(35, TimeUnit.SECONDS).build()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private suspend fun fetch(url: String, refresh: Boolean, json: Boolean = true): Triple<String, Boolean, Long> = locks.getOrPut(url) { Mutex() }.withLock { fetchInternal(url, refresh, json) }
    private suspend fun fetchInternal(url: String, refresh: Boolean, json: Boolean): Triple<String, Boolean, Long> = withContext(Dispatchers.IO) {
        val cached = dao.cached(url)
        if (!refresh && cached != null && System.currentTimeMillis() - cached.time < 300000) return@withContext Triple(cached.body, true, cached.time)
        try {
            http.newCall(Request.Builder().url(url).build()).execute().use {
                check(it.isSuccessful) { "HTTP ${it.code}" }
                val body = it.body?.string() ?: error("空响应")
                if (json) JSONObject(body) else check(org.jsoup.Jsoup.parse(body).selectFirst(".apphub_AppName") != null) { "Steam 附加详情暂不可用" }
                val now = System.currentTimeMillis(); dao.cache(CachedResponse(url, body, now)); Triple(body, false, now)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { cached?.let { Triple(it.body, true, it.time) } ?: throw e }
    }
    suspend fun featured(refresh: Boolean): StoreResult = withContext(Dispatchers.Default) {
        kotlinx.coroutines.coroutineScope {
            val region = currentRegion()
            val highlights = async {
                try {
                    val (body, _, _) = fetch("https://store.steampowered.com/api/featured?cc=${region.country}&l=schinese", refresh)
                    val json = JSONObject(body)
                    listOf("featured_win", "featured_mac", "featured_linux")
                        .flatMap { catalogGames(json.optJSONArray(it), region) }.distinctBy { it.id }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
            }
            val (body, cached, time) = fetch("https://store.steampowered.com/api/featuredcategories?cc=${region.country}&l=schinese", refresh)
            val categories = JSONObject(body)
            val games = listOf("specials", "top_sellers", "new_releases")
                .flatMap { catalogGames(categories.optJSONObject(it)?.optJSONArray("items"), region) }
                .filter { it.discount > 0 }.distinctBy { it.id }
            if (!cached) games.forEach { game ->
                game.cents?.let { observations.record(game.id, it, time, "Steam featuredcategories · ${region.label}", region) }
            }
            StoreResult(games, cached, time, highlights.await())
        }
    }
    private fun catalogGames(items: org.json.JSONArray?, region: StoreRegion): List<Game> {
        if (items == null) return emptyList()
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optInt("id").takeIf { it > 0 } ?: return@mapNotNull null
            val name = item.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (item.optInt("type") != 0) return@mapNotNull null
            val currency = item.optString("currency")
            val cents = item.optLong("final_price", -1).takeIf { it >= 0 && currency.isNotBlank() }
            val original = item.optLong("original_price", -1)
            val image = item.optString("large_capsule_image").ifBlank { item.optString("header_image") }
            Game(id, name, image, cents?.let { StoreRegion.from(region.country, currency)?.format(it) }.orEmpty(),
                item.optInt("discount_percent").coerceIn(0, 100),
                if (cents != null && original > cents) StoreRegion.from(region.country, currency)?.format(original).orEmpty() else "",
                cents.takeIf { currency == region.currency })
        }
    }
    suspend fun search(query: String, refresh: Boolean): StoreResult {
        val region = currentRegion()
        val (body, cached, time) = fetch("https://store.steampowered.com/api/storesearch/?term=${URLEncoder.encode(query, "UTF-8")}&l=schinese&cc=${region.country}", refresh)
        val items = JSONObject(body).optJSONArray("items") ?: error("商店返回格式不受支持")
        return StoreResult((0 until items.length()).map { index ->
            val item = items.getJSONObject(index); val price = item.optJSONObject("price"); val initial = price?.optInt("initial") ?: 0; val final = price?.optInt("final") ?: 0
            val currency = price?.optString("currency").orEmpty()
            Game(item.getInt("id"), item.getString("name"), item.optString("tiny_image"), if (price == null) "价格不可用" else StoreRegion.from(region.country, currency)?.format(final.toLong()).orEmpty(), if (initial > 0) ((initial - final) * 100 / initial) else 0, if (initial > final) StoreRegion.from(region.country, currency)?.format(initial.toLong()).orEmpty() else "", if(currency == region.currency) final.toLong() else null)
        }, cached, time)
    }
    suspend fun searchPage(query: String, start: Int, options: StoreSearchOptions, refresh: Boolean): StorePage = withContext(Dispatchers.Default) {
        require(start >= 0)
        val region = currentRegion()
        val term = URLEncoder.encode(query, "UTF-8")
        val url = "https://store.steampowered.com/search/results/?term=$term&start=$start&count=20&infinite=1&cc=${region.country}&l=schinese&sort_by=${URLEncoder.encode(options.order,"UTF-8")}&category1=${options.category}&specials=${if(options.discountOnly) "1" else ""}"
        val (body, cached, time) = fetch(url, refresh)
        val json = JSONObject(body)
        check(json.optInt("success") == 1 || json.optBoolean("success")) { "Steam 搜索返回失败" }
        val games = StoreSearchParser.games(json.optString("results_html"))
        if(!cached) games.forEach { game -> game.cents?.let { observations.record(game.id, it, time, "Steam search · ${region.label}", region) } }
        StorePage(games, json.optInt("total_count"), start + 20, cached, time)
    }
    suspend fun details(id: Int): JSONObject = withContext(Dispatchers.Default) {
        val region = currentRegion()
        val (body, cached, time) = fetch("https://store.steampowered.com/api/appdetails?appids=$id&cc=${region.country}&l=schinese", false)
        val root = JSONObject(body).getJSONObject(id.toString()); check(root.optBoolean("success")) { "详情不可用" }
        val data = root.getJSONObject("data"); val price = data.optJSONObject("price_overview")
        if(!cached && price?.optString("currency") == region.currency && price.has("final")) observations.record(id, price.getLong("final"), time, "Steam appdetails · ${region.label}", region)
        data
    }
    internal suspend fun detailSupplement(id: Int): DetailSupplement = withContext(Dispatchers.Default) {
        require(id > 0)
        val region = currentRegion()
        val (html) = fetch("https://store.steampowered.com/app/$id/?cc=${region.country}&l=schinese", false, json = false)
        GameDetailsData.supplement(html)
    }
    suspend fun wishlist(steamId: String): List<Int> {
        require(steamId.matches(Regex("[0-9]{17}"))) { "请输入 SteamID64" }
        val (body) = fetch("https://api.steampowered.com/IWishlistService/GetWishlist/v1/?steamid=$steamId", true)
        val response = JSONObject(body).optJSONObject("response") ?: error("愿望单不可用")
        val items = response.optJSONArray("items") ?: error("愿望单为空或不可公开访问")
        return (0 until items.length()).map { items.getJSONObject(it).getInt("appid") }
    }
}
