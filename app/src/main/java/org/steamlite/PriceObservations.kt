package org.steamlite

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

enum class ObservedPriceChange { FIRST, LOWER, EQUAL, HIGHER }
fun compareObservedPrice(current: Long, previousLowest: Long?): ObservedPriceChange {
    require(current >= 0)
    return when { previousLowest == null -> ObservedPriceChange.FIRST; current < previousLowest -> ObservedPriceChange.LOWER; current == previousLowest -> ObservedPriceChange.EQUAL; else -> ObservedPriceChange.HIGHER }
}
data class PriceSample(val cents: Long, val time: Long)
data class PriceObservation(val lowest: Long, val count: Int, val first: Long, val last: Long, val source: String, val samples: List<PriceSample>, val change: ObservedPriceChange) {
    val label get() = when(change) { ObservedPriceChange.FIRST -> "本机首次记录"; ObservedPriceChange.LOWER -> "本机观察新低"; ObservedPriceChange.EQUAL -> "持平本机观察低点"; ObservedPriceChange.HIGHER -> "高于本机观察低点" }
}
/** Observations are isolated by country and currency, not global Steam price history. */
class PriceObservations(private val dao: CacheDao) {
    private val mutex = Mutex()
    private fun key(id: Int, region: StoreRegion) = "price-observations:${region.key}:$id"
    private fun decode(value: CachedResponse): PriceObservation {
        val j = JSONObject(value.body); val a = j.getJSONArray("samples")
        return PriceObservation(j.getLong("lowest"), j.getInt("count"), j.getLong("first"), j.getLong("last"), j.getString("source"), (0 until a.length()).map { val s = a.getJSONObject(it); PriceSample(s.getLong("cents"), s.getLong("time")) }, ObservedPriceChange.valueOf(j.getString("change")))
    }
    suspend fun summary(id: Int, region: StoreRegion = StoreRegion.Guest): PriceObservation? = dao.cached(key(id, region))?.let(::decode)
    suspend fun record(id: Int, cents: Long, time: Long, source: String, region: StoreRegion = StoreRegion.Guest): PriceObservation = mutex.withLock {
        require(id > 0 && cents >= 0)
        val previous = summary(id, region)
        if(previous != null && time <= previous.last) return@withLock previous
        val next = PriceObservation(minOf(cents, previous?.lowest ?: cents), (previous?.count ?: 0) + 1, previous?.first ?: time, time, source,
            (previous?.samples.orEmpty() + PriceSample(cents, time)).takeLast(60), compareObservedPrice(cents, previous?.lowest))
        val json = JSONObject().put("lowest", next.lowest).put("count", next.count).put("first", next.first).put("last", time).put("source", source).put("change", next.change.name)
            .put("samples", JSONArray(next.samples.map { JSONObject().put("cents", it.cents).put("time", it.time) }))
        dao.cache(CachedResponse(key(id, region), json.toString(), time)); next
    }
}
