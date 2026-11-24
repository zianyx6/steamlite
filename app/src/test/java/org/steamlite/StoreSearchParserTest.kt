package org.steamlite
import org.junit.Assert.*
import org.junit.Test
class StoreSearchParserTest {
    @Test fun parsesCapturedSteamMarkupWithoutNetwork() {
        val html = javaClass.getResource("/store-search-live.html")!!.readText()
        val games = StoreSearchParser.games(html)
        assertEquals(2,games.size); assertEquals(601360,games.first().id)
        assertEquals(0L,games.first().cents); assertEquals(98,games.first().positivePercent)
        assertEquals(1255980,games.last().id)
    }
    @Test fun parsesRealSearchMarkupFieldsAndEscapedTitles() {
        val html = """<a class="search_result_row" data-ds-appid="400"><img src="https://example.com/image.jpg"><span class="title">Portal &amp; Test</span><div class="search_released">2026 年 1 月 1 日</div><span class="search_review_summary" data-tooltip-html="100 篇评测中有 98% 为好评"></span><div data-price-final="199"><div class="discount_pct">-80%</div><div class="discount_original_price">$9.99</div><div class="discount_final_price">$1.99</div></div></a>"""
        val game = StoreSearchParser.games(html).single()
        assertEquals(400,game.id); assertEquals("Portal & Test", game.name); assertEquals(199L,game.cents)
        assertEquals(80,game.discount); assertEquals(98,game.positivePercent); assertEquals("$9.99",game.original)
    }
    @Test fun skipsNonAppRowsAndDeduplicates() {
        val html = """<a class="search_result_row" data-ds-appid="1,2"><span class="title">Bundle</span></a><a class="search_result_row" data-ds-appid="400"><span class="title">Portal</span></a>"""
        assertEquals(1,StoreSearchParser.games(html+html).size)
    }
    @Test fun keepsRegionalPriceUnitsWithoutAssumingDollarsOrPositiveReviews() {
        val html = """<a class="search_result_row" data-ds-appid="400"><span class="title">Portal</span><div data-price-final="100"><span class="discount_final_price">¥1.00</span></div></a>"""
        val game = StoreSearchParser.games(html).single(); assertEquals(100L, game.cents); assertEquals("¥1.00", game.price); assertNull(game.positivePercent)
    }
}
