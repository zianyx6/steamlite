package org.steamlite

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PriceObservationRegionTest {
    @Test fun lowerPriceElsewhereNeverChangesTheCurrentRegionsObservedLow() = runBlocking {
        val observations = PriceObservations(MemoryDao())
        val us = StoreRegion("US", "USD")
        val pk = StoreRegion("PK", "USD")
        val cn = StoreRegion("CN", "CNY")
        observations.record(620, 999, 1000, "test", us)
        observations.record(620, 199, 2000, "test", pk)
        observations.record(620, 500, 3000, "test", cn)
        assertEquals(999L, observations.summary(620, us)!!.lowest)
        assertEquals(199L, observations.summary(620, pk)!!.lowest)
        assertEquals(500L, observations.summary(620, cn)!!.lowest)
        assertEquals(ObservedPriceChange.LOWER, observations.record(620, 899, 4000, "test", us).change)
        assertEquals(1, observations.summary(620, pk)!!.count)
        assertNull(observations.summary(620, StoreRegion("JP", "JPY")))
    }
    private class MemoryDao : CacheDao {
        private val rows = mutableMapOf<String, CachedResponse>()
        override suspend fun cached(url: String) = rows[url]
        override suspend fun cache(value: CachedResponse) { rows[value.url] = value }
        override suspend fun deleteCache(url: String) { rows.remove(url) }
        override fun conversations(owner: String): Flow<List<Message>> = emptyFlow()
        override fun messages(owner: String, peer: Long, limit: Int): Flow<List<Message>> = emptyFlow()
        override suspend fun put(message: Message) = Unit
        override suspend fun messageText(id: String): String? = null
        override suspend fun deleteMessage(id: String) = Unit
        override suspend fun deleteEmptyMessages() = Unit
        override suspend fun clearMessages() = Unit
    }
}
