package org.steamlite

import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/** Steam amounts use hundredths even for currencies displayed without decimal places. */
data class StoreRegion(val country: String, val currency: String) {
    init {
        require(country.matches(Regex("[A-Z]{2}")))
        require(currency.matches(Regex("[A-Z]{3}")))
        Currency.getInstance(currency)
    }
    val key get() = "$country:$currency"
    val label get() = "$country · $currency"
    fun format(cents: Long): String {
        val digits = Currency.getInstance(currency).defaultFractionDigits.coerceAtLeast(0)
        val number = NumberFormat.getNumberInstance(Locale.US).apply {
            minimumFractionDigits = digits
            maximumFractionDigits = digits
        }.format(BigDecimal.valueOf(cents, 2))
        return "$currency $number"
    }
    companion object {
        val Guest = StoreRegion("US", "USD")
        fun from(country: String, currency: String): StoreRegion? = runCatching {
            StoreRegion(country.trim().uppercase(Locale.ROOT), currency.trim().uppercase(Locale.ROOT))
        }.getOrNull()
    }
}
