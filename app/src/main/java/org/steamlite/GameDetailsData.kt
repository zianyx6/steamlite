package org.steamlite

import org.json.JSONObject
import org.jsoup.Jsoup

internal data class PurchaseOption(
    val id: Int, val name: String, val price: String, val original: String,
    val discount: Int, val description: String, val subscription: Boolean,
) {
    val url get() = "https://store.steampowered.com/sub/$id/"
}

internal data class GameLanguage(val name: String, val ui: Boolean?, val subtitles: Boolean?, val audio: Boolean?)
internal data class GameReviewSummary(
    val recent: Boolean, val total: Long, val percent: Int, val verdict: String, val scope: String,
)
internal data class DetailSupplement(
    val languages: List<GameLanguage> = emptyList(), val reviews: List<GameReviewSummary> = emptyList(),
)

internal object GameDetailsData {
    fun plain(html: String): String = Jsoup.parse(html).text().trim()

    fun purchases(data: JSONObject): List<PurchaseOption> = buildList {
        val groups = data.optJSONArray("package_groups") ?: return@buildList
        for (groupIndex in 0 until groups.length()) {
            val group = groups.optJSONObject(groupIndex) ?: continue
            val subscription = group.optString("is_recurring_subscription").equals("true", true)
            val subs = group.optJSONArray("subs") ?: continue
            for (index in 0 until subs.length()) {
                val sub = subs.optJSONObject(index) ?: continue
                val id = sub.optInt("packageid").takeIf { it > 0 } ?: continue
                val document = Jsoup.parseBodyFragment(sub.optString("option_text"))
                val original = document.selectFirst(".discount_original_price")?.text().orEmpty()
                document.select(".discount_original_price").remove()
                val text = document.text().trim()
                val split = text.lastIndexOf(" - ")
                val free = sub.optBoolean("is_free_license") || sub.optString("can_get_free_license") == "1"
                val name = if (split > 0) text.substring(0, split).trim() else
                    plain(group.optString("title")).ifBlank { data.optString("name") }
                // Keep Steam's formatted amount and currency; cents are not a locale-independent display price.
                val price = if (free) "免费" else if (split > 0) text.substring(split + 3).trim() else text
                val discount = Regex("(\\d{1,3})\\s*%").find(sub.optString("percent_savings_text"))
                    ?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 100) ?: 0
                add(PurchaseOption(id, name, price.ifBlank { "价格暂不可用" }, original, discount,
                    plain(sub.optString("option_description")).ifBlank { plain(group.optString("description")) }, subscription))
            }
        }
    }.distinctBy { it.id }

    fun languageFallback(html: String): List<GameLanguage> {
        val doc = Jsoup.parseBodyFragment(html)
        doc.select("br").append("|break|")
        return doc.text().substringBefore("|break|").split(',', '，').mapNotNull { value ->
            val name = value.replace("*", "").trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            // appdetails only marks full audio; it does not describe the interface/subtitle columns.
            GameLanguage(name, null, null, value.contains('*'))
        }.distinctBy { it.name }
    }

    fun supplement(html: String): DetailSupplement {
        val doc = Jsoup.parse(html)
        val languages = doc.select(".game_language_options tr").mapNotNull { row ->
            val cells = row.select("td")
            if (cells.size < 4) return@mapNotNull null
            val name = cells[0].text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            fun checked(index: Int) = cells[index].text().any { it == '✔' || it == '✓' } ||
                cells[index].selectFirst("img[src*=ico_bluecheck], img[src*=check]") != null
            GameLanguage(name, checked(1), checked(3), checked(2))
        }.distinctBy { it.name }
        val reviews = doc.select(".user_reviews_summary_row[data-tooltip-html]").mapNotNull { row ->
            val tip = plain(row.attr("data-tooltip-html"))
            val percent = Regex("(\\d{1,3})\\s*%").find(tip)?.groupValues?.get(1)?.toIntOrNull()
                ?.takeIf { it in 0..100 } ?: return@mapNotNull null
            val total = Regex("([\\d,]+)\\s*(?:篇|条|user reviews)", RegexOption.IGNORE_CASE)
                .find(tip)?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull()
                ?.takeIf { it > 0 } ?: return@mapNotNull null
            val recent = row.id().contains("Recent") || row.selectFirst(".subtitle")?.text()
                ?.let { it.contains("最近") || it.contains("Recent", true) } == true
            val scope = if (recent) "最近 30 天" else if (tip.contains("您语言")) "简体中文评测" else "全部语言"
            GameReviewSummary(recent, total, percent, row.selectFirst(".game_review_summary")?.text().orEmpty(), scope)
        }.distinctBy { it.recent }.sortedByDescending { it.recent }
        return DetailSupplement(languages, reviews)
    }
}
