package org.steamlite

import org.jsoup.Jsoup

data class StoreSearchOptions(val order: String = "_ASC", val category: String = "", val discountOnly: Boolean = false, val minPositive: Int = 0)
data class StorePage(val games: List<Game>, val total: Int, val nextStart: Int, val cached: Boolean, val time: Long)
object StoreSearchParser {
    fun games(html: String): List<Game> = Jsoup.parse(html).select("a.search_result_row[data-ds-appid]").mapNotNull { row ->
        val id = row.attr("data-ds-appid").toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
        val name = row.selectFirst(".title")?.text()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val price = row.selectFirst(".discount_final_price")?.text().orEmpty().ifBlank { "价格不可用" }
        val final = row.selectFirst("[data-price-final]")?.attr("data-price-final")?.toLongOrNull()?.takeIf { it >= 0 }
        val cents = if(price == "免费" || price.equals("Free", true)) 0L else final
        val percent = row.selectFirst(".search_review_summary")?.attr("data-tooltip-html")?.let { Regex("(\\d{1,3})%").find(it)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..100 } }
        Game(id, name, row.selectFirst("img")?.attr("src").orEmpty(), price,
            row.selectFirst(".discount_pct")?.text()?.filter(Char::isDigit)?.toIntOrNull()?.coerceIn(0,100) ?: 0,
            row.selectFirst(".discount_original_price")?.text().orEmpty(), cents,
            row.selectFirst(".search_released")?.text().orEmpty(), percent)
    }.distinctBy { it.id }
}
