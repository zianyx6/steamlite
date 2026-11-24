package org.steamlite

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

data class WishlistSnapshot(val ids: List<Int>, val time: Long, val cached: Boolean)
data class WishGame(val id: Int, val name: String, val cents: Long?, val price: String, val discount: Int, val release: Long, val coming: Boolean, val loaded: Boolean, val region: String = "")
class WishlistRepository(private val steam: SteamConnection, private val dao: CacheDao) {
    val snapshot = MutableStateFlow<WishlistSnapshot?>(null)
    val loading = MutableStateFlow(false)
    val mutating = MutableStateFlow(false)
    val error = MutableStateFlow("")
    val notice = MutableStateFlow("")
    val games = MutableStateFlow<Map<Int, WishGame>>(emptyMap())
    val pricesUpdatedAt = MutableStateFlow(0L)
    val pricesCached = MutableStateFlow(true)
    private val mutex = Mutex()
    private val refreshMutex = Mutex()
    private var snapshotOwner = ""
    private var snapshotRegion = ""
    private val priceTimes = mutableMapOf<Int, Long>()
    private var retryAt = 0L
    private var lastAttempt = 0L
    fun clearVisible() { snapshot.value = null; games.value = emptyMap(); notice.value = ""; error.value = ""; snapshotOwner = ""; pricesUpdatedAt.value = 0; pricesCached.value = true; priceTimes.clear(); retryAt = 0; lastAttempt = 0 }
    private suspend fun save(owner: String, ids: List<Int>) {
        check(steam.owner == owner) { "账号会话已变化" }
        val now = System.currentTimeMillis()
        dao.cache(CachedResponse("wishlist:$owner", JSONArray(ids).toString(), now))
        snapshot.value = WishlistSnapshot(ids, now, false)
        snapshotOwner = owner
    }
    suspend fun refresh(force: Boolean = false) {
        // A second page joins the existing state instead of queuing another full refresh.
        if (!refreshMutex.tryLock()) return
        try { refreshLocked(force) } finally { refreshMutex.unlock() }
    }
    suspend fun syncNow() {
        if (refreshMutex.isLocked) refreshMutex.withLock { } else refresh(true)
    }
    private suspend fun persistPrices(owner: String, ids: List<Int>, region: StoreRegion) {
        check(steam.owner == owner && steam.storeRegion.value == region) { "账号或商店地区已变化" }
        val saved = games.value.values.filter { it.id in ids }
        val json = JSONArray(saved.map { g -> JSONObject().put("id",g.id).put("name",g.name).put("cents",g.cents ?: JSONObject.NULL).put("price",g.price).put("discount",g.discount).put("release",g.release).put("coming",g.coming).put("loaded",g.loaded).put("updated", priceTimes[g.id] ?: 0L) })
        val oldest = saved.minOfOrNull { priceTimes[it.id] ?: 0L } ?: 0L
        dao.cache(CachedResponse("wishgames:$owner:${region.key}", json.toString(), oldest))
        pricesUpdatedAt.value = oldest
    }
    private suspend fun refreshLocked(force: Boolean) {
        val owner = steam.owner
        if (owner.isBlank() || !steam.online.value) return
        val region = try { steam.requireStoreRegion() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error.value = "账号商店地区尚未获取，请稍后重试"; return }
        val now = System.currentTimeMillis()
        val current = snapshot.value
        if (snapshotOwner == owner && snapshotRegion == region.key) {
            if (now < retryAt || now - lastAttempt < 30_000L) return
            if (!force && current != null && now - current.time < 300_000L && !pricesCached.value) return
        }
        loading.value = true; if(!error.value.startsWith("修改")) error.value = ""; if(!mutating.value) notice.value = ""
        try {
          if (snapshotOwner != owner || snapshotRegion != region.key || current == null) {
            games.value = emptyMap(); priceTimes.clear()
            pricesUpdatedAt.value = 0L; pricesCached.value = true
            val cached = dao.cached("wishlist:$owner")
            snapshot.value = cached?.let { val items = JSONArray(it.body); WishlistSnapshot((0 until items.length()).map(items::getInt), it.time, true) }
            snapshotOwner = owner; snapshotRegion = region.key; retryAt = 0; lastAttempt = 0
            dao.cached("wishgames:$owner:${region.key}")?.let { cachedGames ->
                pricesUpdatedAt.value = cachedGames.time
                val array = JSONArray(cachedGames.body)
                games.value = (0 until array.length()).map { index -> val j = array.getJSONObject(index); priceTimes[j.getInt("id")] = j.optLong("updated", cachedGames.time); WishGame(j.getInt("id"), j.getString("name"), if(j.isNull("cents")) null else j.getLong("cents"), j.getString("price"), j.getInt("discount"), j.getLong("release"), j.getBoolean("coming"), j.getBoolean("loaded"), region.key) }.associateBy { it.id }
            }
          }
            lastAttempt = now
            val ids = mutex.withLock { steam.readWishlist().map { it.first }.also { save(owner, it) } }
            val refreshAll = force && !pricesCached.value
            val pending = ids.filter { refreshAll || System.currentTimeMillis() - (priceTimes[it] ?: 0L) >= 300_000L }
            pricesCached.value = pending.isNotEmpty()
            pending.chunked(80).forEach { batch ->
                if(!mutating.value && !notice.value.contains("回读")) notice.value = "同步价格 ${ids.count { System.currentTimeMillis() - (priceTimes[it] ?: 0L) < 300_000L }}/${ids.size}"
                val result = steam.wishlistGames(batch)
                check(steam.owner == owner && steam.storeRegion.value == region) { "账号或商店地区已变化" }
                games.value = games.value + result.associateBy { it.id }
                val fetchedAt = System.currentTimeMillis()
                result.forEach { priceTimes[it.id] = fetchedAt }
                // Save each completed batch so cancellation or throttling can resume it.
                persistPrices(owner, ids, region)
            }
            pricesCached.value = ids.any { System.currentTimeMillis() - (priceTimes[it] ?: 0L) >= 300_000L }
            if (ids.isEmpty()) pricesUpdatedAt.value = now
            if(!mutating.value && !notice.value.contains("回读")) notice.value = "已从 Steam 同步"
        } catch(e: CancellationException) { throw e }
        catch(e: PriceRateLimited) {
            if (steam.owner == owner) {
                retryAt = System.currentTimeMillis() + e.retryAfterMillis
                pricesCached.value = true
                notice.value = ""
                error.value = "Steam 暂时限制价格查询，已保留已加载价格，请约 ${(e.retryAfterMillis + 999) / 1000} 秒后重试"
            }
        }
        catch(e: Exception) { if (steam.owner == owner) error.value = "同步失败：${e.message ?: e.javaClass.simpleName}" }
        finally { loading.value = false }
    }
    suspend fun change(appId: Int, add: Boolean) {
        if(mutating.value) return
        mutating.value = true; error.value = ""; notice.value = if(add) "正在请求 Steam 添加，请稍候" else "正在请求 Steam 移除，请稍候"
        val owner = steam.owner
        try {
            mutex.withLock {
                val actual = steam.changeWishlist(appId, add)
                save(owner, actual.map { it.first })
            }
            notice.value = if(add) "Steam 已回读确认添加" else "Steam 已回读确认移除"
        } catch(e: Exception) { notice.value = ""; error.value = "修改未确认：${e.message ?: e.javaClass.simpleName}。请刷新核实，避免重复操作。" }
        finally { mutating.value = false }
    }
}
