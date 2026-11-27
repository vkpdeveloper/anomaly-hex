package ly.anoma.hex.keyboard

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/** Sums and conversions for what was just typed, as a chip on the strip ([StripAction.Insert]):
 * "45*12" offers "= 540" and puts the answer in its place; "250 usd" offers "≈ ₹20,850" and adds it
 * after the amount in brackets once a space follows, as do "8 km", "70 kg" and "30°c". */
internal object QuickMath {
    /** The longest text before the caret worth reading. */
    const val READ = 64

    fun offer(before: String, locale: Locale = Locale.getDefault()): StripAction.Insert? {
        val spaces = before.length - before.trimEnd(' ').length
        if (spaces > 1) return null
        val text = before.dropLast(spaces)
        return sum(text, spaces, locale) ?: convert(text, spaces, locale)
    }

    // A sum ends the text: numbers joined by + - * / × ÷ x (and a trailing %), optionally "=" after it.
    private val SUM = Regex("""(?:^|(?<=[\s(]))(\(?\s*\d[\d.,]*%?(?:\s*[-+*/×÷x]\s*\(?\s*\d[\d.,]*%?\s*\)?)+)(\s*=)?$""")

    private fun sum(text: String, spaces: Int, locale: Locale): StripAction.Insert? {
        val match = SUM.find(text) ?: return null
        val expression = match.groupValues[1]
        val operators = expression.filter { it in "+*×÷x" || it == '-' || it == '/' }
        // "23/11", "2024-11-23" and "98-76" are dates and numbers, not sums: those need spaces around the sign.
        if (operators.all { it == '-' || it == '/' } && !Regex("""\d\s+[-/]\s+\d""").containsMatchIn(expression)) return null
        val value = Expression(expression).value() ?: return null
        val answer = format(value, locale, 6)
        val equals = match.groupValues[2].isNotEmpty()
        val tail = " ".repeat(spaces)
        // "45*12=" keeps the sum and adds the answer; "45*12" becomes it.
        return if (equals) StripAction.Insert("= $answer", spaces, (if (spaces > 0) " " else "") + answer + tail)
            else StripAction.Insert("= $answer", match.value.length + spaces, answer + tail)
    }

    private class Unit(val names: List<String>, val to: String, val factor: (BigDecimal) -> BigDecimal)

    private val UNITS = listOf(
        Unit(listOf("km", "kms", "kilometers", "kilometres"), "mi") { it.multiply(BigDecimal("0.621371")) },
        Unit(listOf("mi", "mile", "miles"), "km") { it.multiply(BigDecimal("1.609344")) },
        Unit(listOf("kg", "kgs", "kilos", "kilograms"), "lb") { it.multiply(BigDecimal("2.204623")) },
        Unit(listOf("lb", "lbs"), "kg") { it.multiply(BigDecimal("0.453592")) },
        Unit(listOf("°c", "celsius"), "°F") { it.multiply(BigDecimal("1.8")).add(BigDecimal(32)) },
        Unit(listOf("°f", "fahrenheit"), "°C") { it.subtract(BigDecimal(32)).divide(BigDecimal("1.8"), MathContext.DECIMAL64) },
        Unit(listOf("ft", "feet", "foot"), "cm") { it.multiply(BigDecimal("30.48")) },
        Unit(listOf("cm"), "in") { it.divide(BigDecimal("2.54"), MathContext.DECIMAL64) },
    )

    /** Units of a US dollar, for now: a rates service replaces these later. */
    private val RATES = mapOf(
        "USD" to "1", "INR" to "84", "EUR" to "0.92", "GBP" to "0.79", "AED" to "3.67", "JPY" to "150",
        "CAD" to "1.37", "AUD" to "1.52", "SGD" to "1.35",
    ).mapValues { BigDecimal(it.value) }

    private val CURRENCY_NAMES = mapOf(
        "usd" to "USD", "$" to "USD", "dollar" to "USD", "dollars" to "USD", "bucks" to "USD",
        "inr" to "INR", "₹" to "INR", "rs" to "INR", "rs." to "INR", "rupee" to "INR", "rupees" to "INR",
        "eur" to "EUR", "€" to "EUR", "euro" to "EUR", "euros" to "EUR",
        "gbp" to "GBP", "£" to "GBP", "aed" to "AED", "dirham" to "AED", "dirhams" to "AED",
        "jpy" to "JPY", "¥" to "JPY", "yen" to "JPY", "cad" to "CAD", "aud" to "AUD", "sgd" to "SGD",
    )

    private const val NUMBER = """\d[\d,]*(?:\.\d+)?"""
    // "250 usd", "250usd", "30°c"; "$250", "rs 500", "₹500".
    private val AFTER = Regex("""(?:^|(?<=[\s(]))($NUMBER)\s?(°?[\p{L}$€£¥₹.]+)$""")
    private val BEFORE = Regex("""(?:^|(?<=[\s(]))([$€£¥₹]|rs\.?\s?)($NUMBER)$""", RegexOption.IGNORE_CASE)

    /** Whether [before] ends in an amount's unit or currency ("250 usd"), which autocorrect leaves alone. */
    fun endsInUnit(before: String): Boolean {
        val name = AFTER.find(before)?.groupValues?.get(2)?.lowercase(Locale.ROOT) ?: return false
        return name in CURRENCY_NAMES || UNITS.any { name in it.names }
    }

    // Only once the amount is finished with a space, so the chip never hides the words for "mi" or "eu".
    private fun convert(text: String, spaces: Int, locale: Locale): StripAction.Insert? {
        if (spaces == 0) return null
        val after = AFTER.find(text)
        val (number, name) = when {
            after != null -> after.groupValues[1] to after.groupValues[2]
            else -> BEFORE.find(text)?.let { it.groupValues[2] to it.groupValues[1].trim() } ?: return null
        }
        val amount = number.replace(",", "").toBigDecimalOrNull() ?: return null
        val key = name.lowercase(Locale.ROOT)
        val shown = CURRENCY_NAMES[key]?.let { money(amount, it, locale) }
            ?: UNITS.firstOrNull { key in it.names }?.let { "${format(it.factor(amount), locale, 1)} ${it.to}" }
            ?: return null
        return StripAction.Insert("≈ $shown", 0, "(≈ $shown) ")
    }

    /** [amount] of [from] in the phone's own currency, or in dollars when it already is that. */
    private fun money(amount: BigDecimal, from: String, locale: Locale): String? {
        val home = runCatching { Currency.getInstance(locale).currencyCode }.getOrNull()?.takeIf { it in RATES } ?: "USD"
        val to = if (from != home) home else if (home == "USD") "EUR" else "USD"
        val value = amount.divide(RATES.getValue(from), MathContext.DECIMAL64).multiply(RATES.getValue(to))
        val format = NumberFormat.getCurrencyInstance(locale).apply {
            currency = Currency.getInstance(to)
            maximumFractionDigits = if (value >= BigDecimal(100)) 0 else 2
            minimumFractionDigits = maximumFractionDigits
        }
        return format.format(value)
    }

    private fun format(value: BigDecimal, locale: Locale, decimals: Int): String =
        NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = decimals; isGroupingUsed = true }
            .format(value.setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros())

    /** + - * / × ÷ x with brackets, the usual precedence, and n% as n/100. Null for what isn't a sum. */
    private class Expression(text: String) {
        private val s = text.replace(" ", "").replace(",", "")
        private var at = 0

        fun value(): BigDecimal? = runCatching { val v = sum(); if (at == s.length) v else null }.getOrNull()

        private fun sum(): BigDecimal {
            var v = product()
            while (at < s.length && s[at] in "+-") {
                val op = s[at++]; val r = product()
                v = if (op == '+') v.add(r) else v.subtract(r)
            }
            return v
        }
        private fun product(): BigDecimal {
            var v = factor()
            while (at < s.length && s[at] in "*/×÷x") {
                val op = s[at++]; val r = factor()
                v = if (op in "/÷") v.divide(r, MathContext.DECIMAL64) else v.multiply(r)
            }
            return v
        }
        private fun factor(): BigDecimal {
            if (at < s.length && s[at] == '(') {
                at++; val v = sum()
                require(at < s.length && s[at] == ')'); at++
                return v
            }
            val start = at
            while (at < s.length && (s[at].isDigit() || s[at] == '.')) at++
            var v = BigDecimal(s.substring(start, at))
            if (at < s.length && s[at] == '%') { at++; v = v.divide(BigDecimal(100)) }
            return v
        }
    }
}
