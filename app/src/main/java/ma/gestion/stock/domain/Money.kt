package ma.gestion.stock.domain

import ma.gestion.stock.data.DocLine
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.roundToLong

object Money {
    private val sym = DecimalFormatSymbols(Locale.FRANCE).apply { groupingSeparator = '\u00A0' /* classe bidi CS : les montants restent groupés en arabe */; decimalSeparator = ',' }
    private val fmt = DecimalFormat("#,##0.00", sym)
    private val qtyFmt = DecimalFormat("#,##0.###", sym)

    /** 12345 -> "123,45" ; (chiffres latins, y compris en arabe, pour la lisibilité des montants) */
    fun format(cents: Long): String = fmt.format(cents / 100.0)
    fun dh(cents: Long): String = format(cents) + " DH"
    fun qty(q: Double): String = qtyFmt.format(q)

    fun parse(s: String): Long? =
        clean(s).toDoubleOrNull()?.let { (it * 100).roundToLong() }

    fun parseQty(s: String): Double? = clean(s).toDoubleOrNull()

    private fun clean(s: String) = s.filterNot { it.isWhitespace() || it == ' ' || it == ' ' }.replace(',', '.')
}

data class Totals(
    val ht: Long, val tva: Long, val ttc: Long,
    val discountAmount: Long,
    val byRate: Map<Double, Pair<Long, Long>>, // taux -> (base HT, TVA)
)

object Calc {
    fun lineHt(l: DocLine): Long = (l.quantity * l.unitPrice * (1 - l.discountPct / 100.0)).roundToLong()

    fun totals(lines: List<DocLine>, globalDiscountPct: Double): Totals {
        val factor = 1 - globalDiscountPct / 100.0
        val byRate = linkedMapOf<Double, Pair<Long, Long>>()
        var gross = 0L
        lines.groupBy { it.vatRate }.toSortedMap().forEach { (rate, ls) ->
            val raw = ls.sumOf { lineHt(it) }
            gross += raw
            val base = (raw * factor).roundToLong()
            val tva = (base * rate / 100.0).roundToLong()
            byRate[rate] = base to tva
        }
        val ht = byRate.values.sumOf { it.first }
        val tva = byRate.values.sumOf { it.second }
        return Totals(ht, tva, ht + tva, gross - ht, byRate)
    }
}
