package org.steamlite

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GameDetailsDataTest {
    @Test fun packageTitleDiscountAndCurrencyComeFromSteamNotFromAppPrice() {
        val data = JSONObject("""{"price_overview":{"currency":"USD","final_formatted":"$99.99"},"package_groups":[{"title":"购买游戏","is_recurring_subscription":"false","subs":[{"packageid":42,"option_text":"Game - Deluxe - <span class='discount_original_price'>¥5,000</span> ¥2,500","percent_savings_text":"-50% ","price_in_cents_with_discount":250000}]}]}""")
        val option = GameDetailsData.purchases(data).single()
        assertEquals("Game - Deluxe", option.name)
        assertEquals("¥2,500", option.price)
        assertEquals("¥5,000", option.original)
        assertEquals(50, option.discount)
        assertEquals("https://store.steampowered.com/sub/42/", option.url)
    }
    @Test fun subscriptionsPreserveBillingPeriodAndRecurringDescription() {
        val data = JSONObject("""{"package_groups":[{"title":"订阅服务","is_recurring_subscription":"true","subs":[{"packageid":4,"option_text":"$7.99 USD / 个月","option_description":"<p>今日支付 $7.99，之后每个月自动续费。</p>"}]}]}""")
        val option = GameDetailsData.purchases(data).single()
        assertTrue(option.subscription)
        assertEquals("$7.99 USD / 个月", option.price)
        assertTrue(option.description.contains("自动续费"))
    }
    @Test fun missingPackagesAndUnsupportedPriceRemainUnavailable() {
        assertTrue(GameDetailsData.purchases(JSONObject()).isEmpty())
        assertTrue(GameDetailsData.purchases(JSONObject("""{"package_groups":[{"subs":[{"packageid":0}]}]}""")).isEmpty())
    }
    @Test fun exactLanguageColumnsAreNotConfusedWithAudio() {
        val result = GameDetailsData.supplement("""<table class="game_language_options"><tr><td>简体中文</td><td>✔</td><td></td><td>✔</td></tr></table>""").languages.single()
        assertEquals(true, result.ui)
        assertEquals(true, result.subtitles)
        assertEquals(false, result.audio)
        val fallback = GameDetailsData.languageFallback("英语<strong>*</strong>, 简体中文<br>*具有完全音频支持的语言")
        assertNull(fallback.first().ui)
        assertEquals(true, fallback.first().audio)
        assertEquals(2, fallback.size)
    }
    @Test fun recentAndAllReviewsKeepTheirPeriodLanguageScopeAndCounts() {
        val html = """<a class="user_reviews_summary_row" data-tooltip-html="过去 30 天内的 11,463 篇用户评测中有 92% 为好评。"><div class="subtitle">最近评测：</div><span class="game_review_summary">特别好评</span></a><a class="user_reviews_summary_row" data-tooltip-html="您语言的 58,220 篇用户评测中有 81% 为好评。"><div class="subtitle">所有评测：</div><span class="game_review_summary">特别好评</span></a>"""
        val reviews = GameDetailsData.supplement(html).reviews
        assertEquals(2, reviews.size)
        assertTrue(reviews[0].recent)
        assertEquals(11463L, reviews[0].total)
        assertEquals(92, reviews[0].percent)
        assertEquals("简体中文评测", reviews[1].scope)
        assertFalse(reviews[1].recent)
    }
    @Test fun blockedPageAndNoReviewsDoNotBecomeZeroPercentRatings() {
        assertTrue(GameDetailsData.supplement("<html>Access Denied</html>").reviews.isEmpty())
        assertTrue(GameDetailsData.supplement("""<a class="user_reviews_summary_row" data-tooltip-html="0 篇中有 0% 为好评"></a>""").reviews.isEmpty())
    }
}
