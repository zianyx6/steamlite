package org.steamlite

import org.junit.Assert.*
import org.junit.Test

class StoreRegionTest {
    @Test fun formatsSteamUnitsInAccountCurrencyIncludingZeroDecimalCurrencies() {
        assertEquals("CNY 128.00", StoreRegion("CN", "CNY").format(12800))
        assertEquals("JPY 1,980", StoreRegion("JP", "JPY").format(198000))
        assertEquals("USD 12.99", StoreRegion("PK", "USD").format(1299))
        assertEquals("KRW 12,000", StoreRegion("KR", "KRW").format(1200000))
        assertEquals("EUR 0.00", StoreRegion("DE", "EUR").format(0))
    }
    @Test fun sameCurrencyStillKeepsDifferentCountriesInSeparateCaches() {
        assertNotEquals(StoreRegion("US", "USD").key, StoreRegion("PK", "USD").key)
        assertNotEquals(StoreRegion("DE", "EUR").key, StoreRegion("FR", "EUR").key)
        assertEquals("CN:CNY", StoreRegion.from("cn", "cny")?.key)
    }
    @Test fun invalidOrMissingSteamRegionNeverBecomesAnAccountDefault() {
        assertNull(StoreRegion.from("", "USD"))
        assertNull(StoreRegion.from("CN", "Invalid"))
        assertNull(StoreRegion.from("CN&cc=US", "CNY"))
    }
}
