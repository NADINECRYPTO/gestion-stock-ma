package ma.gestion.stock.pdf

import ma.gestion.stock.data.DocType

/** Libellés des documents, indépendants de la langue de l'interface. */
class PdfLabels(val lang: String) {
    val rtl = lang == "ar"
    private fun t(fr: String, ar: String) = if (rtl) ar else fr

    fun title(type: DocType) = when (type) {
        DocType.DEVIS -> t("DEVIS", "عرض أثمان")
        DocType.BL -> t("BON DE LIVRAISON", "وصل التسليم")
        DocType.FACTURE -> t("FACTURE", "فاتورة")
        DocType.AVOIR -> t("AVOIR", "فاتورة دائنة")
    }
    val number = t("N°", "رقم")
    val date = t("Date", "التاريخ")
    val client = t("Client", "الزبون")
    val ref = t("Réf.", "المرجع")
    val designation = t("Désignation", "البيان")
    val qty = t("Qté", "الكمية")
    val unitPrice = t("P.U. HT", "ث.و")
    val discount = t("Rem.%", "تخفيض")
    val vat = t("TVA%", "ض.ق.م")
    val lineTotal = t("Total HT", "المجموع")
    val discountTotal = t("Remise", "التخفيض")
    val totalHt = t("Total HT", "المجموع دون رسوم")
    fun tvaAt(rate: String) = t("TVA $rate %", "ض.ق.م $rate %")
    val totalTtc = t("Total TTC", "المجموع مع الرسوم")
    val deposit = t("Acompte / réglé", "تسبيق / مؤدى")
    val due = t("Reste à payer", "الباقي للأداء")
    val signature = t("Cachet et signature", "الطابع والتوقيع")
    val page = t("Page", "صفحة")
    val of = t("/", "/")
    val ice = "ICE"
    val tel = t("Tél", "الهاتف")
    val draft = t("BROUILLON", "مسودة")
    val source = t("Réf. origine", "المرجع الأصلي")
    val bank = t("RIB", "RIB")
    val currency = t("DH", "درهم")
    val counter = t("Comptoir", "زبون عابر")
}
