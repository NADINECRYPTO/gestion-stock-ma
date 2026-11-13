package ma.gestion.stock.pdf

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.text.*
import ma.gestion.stock.data.*
import ma.gestion.stock.domain.Calc
import ma.gestion.stock.domain.Money
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Génération locale de PDF A4 portrait (595 x 842 pt) avec android.graphics.pdf.PdfDocument.
 * - Police intégrée (assets/fonts/DejaVuSans*.ttf) qui couvre l'arabe ; StaticLayout gère
 *   le bidi, les lettres liées et les textes mixtes (chiffres, références latines).
 * - Mise en page miroir en arabe (RTL).
 * - Multipage : en-têtes de colonnes répétés, pagination « Page x / y ».
 * - Les totaux et la zone cachet sont dans un bloc qui n'est jamais coupé ; le cachet est
 *   dessiné uniquement dans son cadre, à côté (jamais par-dessus) des totaux et des lignes.
 */
class PdfGenerator(private val ctx: Context) {
    private val W = 595f; private val H = 842f; private val M = 36f
    private val regular by lazy { runCatching { Typeface.createFromAsset(ctx.assets, "fonts/DejaVuSans.ttf") }.getOrDefault(Typeface.DEFAULT) }
    private val bold by lazy { runCatching { Typeface.createFromAsset(ctx.assets, "fonts/DejaVuSans-Bold.ttf") }.getOrDefault(Typeface.DEFAULT_BOLD) }
    private val green = Color.rgb(11, 93, 59)
    private val grey = Color.rgb(235, 240, 237)

    data class Input(
        val doc: Document, val lines: List<DocLine>, val client: Client, val company: Company,
        val paid: Long, val sourceNumber: String?,
    )

    fun generate(input: Input, out: File) {
        // Passe 1 : compter les pages ; passe 2 : dessiner avec « Page x / y »
        val pages = render(input, null, 0)
        val pdf = PdfDocument()
        render(input, pdf, pages)
        out.parentFile?.mkdirs()
        val tmp = File(out.parentFile, out.name + ".tmp")
        tmp.outputStream().use { pdf.writeTo(it) }
        pdf.close()
        if (out.exists()) out.delete()
        tmp.renameTo(out)
    }

    private inner class Ctx(val pdf: PdfDocument?, val totalPages: Int, val L: PdfLabels) {
        var page: PdfDocument.Page? = null
        var canvas: Canvas = Canvas() // canvas factice en passe 1
        var pageNo = 0
        fun x(left: Float, width: Float) = if (L.rtl) W - left - width else left
    }

    private fun paint(size: Float, isBold: Boolean = false, color: Int = Color.BLACK) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = if (isBold) bold else regular; textSize = size; this.color = color
    }

    /** Dessine un texte dans une boîte ; retourne la hauteur utilisée. align: 0 début, 1 centre, 2 fin (relatif au sens de lecture). */
    private fun text(c: Ctx, s: String, left: Float, top: Float, width: Float, p: TextPaint, align: Int = 0, userText: Boolean = false, draw: Boolean = true): Float {
        if (width <= 0f) return 0f
        val dir = when {
            userText -> TextDirectionHeuristics.FIRSTSTRONG_LTR.let { if (c.L.rtl) TextDirectionHeuristics.FIRSTSTRONG_RTL else it }
            c.L.rtl -> TextDirectionHeuristics.RTL
            else -> TextDirectionHeuristics.LTR
        }
        val al = when (align) { 1 -> Layout.Alignment.ALIGN_CENTER; 2 -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_NORMAL }
        val layout = StaticLayout.Builder.obtain(s, 0, s.length, p, width.toInt())
            .setAlignment(al).setTextDirection(dir).setIncludePad(false).build()
        if (draw && c.pdf != null) {
            c.canvas.save(); c.canvas.translate(c.x(left, width), top); layout.draw(c.canvas); c.canvas.restore()
        }
        return layout.height.toFloat()
    }

    private fun loadBitmap(path: String?): Bitmap? = path?.let { p ->
        val f = File(ctx.filesDir, p); if (!f.exists()) null else BitmapFactory.decodeFile(f.absolutePath) // PNG: alpha conservé
    }

    private fun drawFit(c: Ctx, bmp: Bitmap, left: Float, top: Float, w: Float, h: Float, alignEnd: Boolean = false) {
        if (c.pdf == null) return
        val s = minOf(w / bmp.width, h / bmp.height)
        val dw = bmp.width * s; val dh = bmp.height * s
        val bx = c.x(left, w)
        val dx = if (alignEnd xor c.L.rtl) bx + w - dw else bx
        val dy = top + (h - dh) / 2
        c.canvas.drawBitmap(bmp, null, RectF(dx, dy, dx + dw, dy + dh), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    }

    // Colonnes (en coordonnées « début de ligne ») : réf, désignation, qté, PU, remise, TVA, total
    private val colW = floatArrayOf(62f, 0f, 42f, 66f, 38f, 38f, 76f)
    private fun cols(): FloatArray { val w = colW.copyOf(); w[1] = (W - 2 * M) - w.sum(); return w }

    private fun render(input: Input, pdf: PdfDocument?, totalPages: Int): Int {
        val c = Ctx(pdf, totalPages, PdfLabels(input.doc.language))
        val L = c.L
        val logo = loadBitmap(input.company.logoPath)
        val stamp = loadBitmap(input.company.stampPath)
        val cw = cols()
        val footerTop = H - M - 26f
        val small = paint(8f); val normal = paint(9f); val boldP = paint(9f, true)

        fun newPage(): Float {
            c.page?.let { pdf?.finishPage(it) }
            c.pageNo++
            if (pdf != null) {
                val pg = pdf.startPage(PdfDocument.PageInfo.Builder(W.toInt(), H.toInt(), c.pageNo).create())
                c.page = pg; c.canvas = pg.canvas
            }
            var y = M
            if (c.pageNo == 1) y = drawHeader(c, input, logo) else {
                // En-tête réduit sur les pages suivantes
                text(c, "${L.title(input.doc.type)} ${input.doc.number ?: L.draft}", M, y, W - 2 * M, paint(10f, true, green), 2, true)
                y += 18f
            }
            drawFooter(c, input, footerTop)
            return y
        }

        fun tableHeader(y0: Float): Float {
            val h = 18f
            if (pdf != null) {
                val p = Paint().apply { color = green }
                c.canvas.drawRect(M, y0, W - M, y0 + h, p)
            }
            val heads = listOf(L.ref, L.designation, L.qty, L.unitPrice, L.discount, L.vat, L.lineTotal)
            var x = M
            heads.forEachIndexed { i, s ->
                text(c, s, x + 3, y0 + 4, cw[i] - 6, paint(8f, true, Color.WHITE), if (i <= 1) 0 else 2); x += cw[i]
            }
            return y0 + h
        }

        var y = newPage()
        y = tableHeader(y)
        input.lines.forEachIndexed { idx, l ->
            val desH = text(c, l.designation, 0f, 0f, cw[1] - 6, normal, userText = true, draw = false)
            val rowH = maxOf(16f, desH + 6f)
            if (y + rowH > footerTop - 6f) { y = newPage(); y = tableHeader(y) }
            if (pdf != null && idx % 2 == 1) c.canvas.drawRect(M, y, W - M, y + rowH, Paint().apply { color = grey })
            val vals = listOf(l.ref, l.designation, Money.qty(l.quantity), Money.format(l.unitPrice),
                if (l.discountPct == 0.0) "" else Money.qty(l.discountPct), Money.qty(l.vatRate), Money.format(Calc.lineHt(l)))
            var x = M
            vals.forEachIndexed { i, s ->
                text(c, s, x + 3, y + 3, cw[i] - 6, if (i == 6) boldP else normal, if (i <= 1) 0 else 2, userText = i <= 1); x += cw[i]
            }
            y += rowH
        }
        if (pdf != null) c.canvas.drawLine(M, y, W - M, y, Paint().apply { color = green; strokeWidth = 1f })

        // ---- Bloc totaux + signature (jamais coupé) ----
        val t = Calc.totals(input.lines, input.doc.globalDiscountPct)
        val rows = mutableListOf<Pair<String, String>>()
        if (t.discountAmount > 0) rows += L.discountTotal + " (${Money.qty(input.doc.globalDiscountPct)} %)" to "-" + Money.format(t.discountAmount)
        rows += L.totalHt to Money.format(t.ht)
        t.byRate.forEach { (r, v) -> rows += L.tvaAt(Money.qty(r)) to Money.format(v.second) }
        rows += L.totalTtc to Money.format(t.ttc) + " " + L.currency
        val showPay = input.doc.type == DocType.FACTURE && input.doc.status == DocStatus.VALIDATED || input.doc.deposit > 0
        if (showPay) {
            val paid = maxOf(input.paid, if (input.doc.status == DocStatus.DRAFT) input.doc.deposit else 0)
            rows += L.deposit to Money.format(paid)
            rows += L.due to Money.format(t.ttc - paid) + " " + L.currency
        }
        val totW = 230f; val rowH = 17f
        val totH = rows.size * rowH + 4
        val sigW = 200f; val sigH = 120f
        val blockH = maxOf(totH, sigH) + 14f
        if (y + 10f + blockH > footerTop - 4f) y = newPage()
        y += 10f
        // Totaux : côté fin (droite en FR, gauche en AR)
        val totLeft = W - M - totW
        rows.forEachIndexed { i, (k, v) ->
            val isTtc = k == L.totalTtc || k == L.due
            if (pdf != null && isTtc) {
                val rx = c.x(totLeft, totW); c.canvas.drawRect(rx, y + i * rowH, rx + totW, y + (i + 1) * rowH, Paint().apply { color = grey })
            }
            val p = if (isTtc) paint(9.5f, true, green) else normal
            text(c, k, totLeft + 4, y + i * rowH + 3, totW * 0.55f, p)
            text(c, v, totLeft + totW * 0.55f, y + i * rowH + 3, totW * 0.45f - 4, p, 2, userText = true)
        }
        // Zone signature : côté début, cadre réservé ; le cachet est contenu DANS ce cadre.
        if (pdf != null) {
            val sx = c.x(M, sigW)
            c.canvas.drawRect(sx, y, sx + sigW, y + sigH, Paint().apply { style = Paint.Style.STROKE; color = Color.GRAY; strokeWidth = 0.6f })
        }
        text(c, L.signature, M + 6, y + 4, sigW - 12, paint(8f, true, Color.DKGRAY))
        stamp?.let { drawFit(c, it, M + 10, y + 18, sigW - 20, sigH - 24) }

        c.page?.let { pdf?.finishPage(it) }
        return c.pageNo
    }

    private fun drawHeader(c: Ctx, input: Input, logo: Bitmap?): Float {
        val L = c.L; val co = input.company; val d = input.doc
        var y = M
        val leftW = 300f
        var hLeft = 0f
        if (logo != null) { drawFit(c, logo, M, y, 140f, 60f); hLeft = 64f }
        hLeft += text(c, co.name, M, y + hLeft, leftW, paint(12f, true, green), userText = true) + 2
        val info = listOfNotNull(
            listOf(co.address, co.city).filter { it.isNotBlank() }.joinToString(", ").ifBlank { null },
            listOfNotNull(co.phone.ifBlank { null }?.let { "${L.tel}: $it" }, co.email.ifBlank { null }).joinToString("  ").ifBlank { null },
        ).joinToString("\n")
        if (info.isNotBlank()) hLeft += text(c, info, M, y + hLeft, leftW, paint(8.5f), userText = true)

        // Bloc titre (côté fin)
        val rW = 200f; val rL = W - M - rW
        var hr = text(c, L.title(d.type), rL, y, rW, paint(16f, true, green), 2)
        val df = SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE)
        val meta = buildString {
            append("${L.number} : ${d.number ?: L.draft}\n${L.date} : ${df.format(Date(d.date))}")
            input.sourceNumber?.let { append("\n${L.source} : $it") }
        }
        hr += 4 + text(c, meta, rL, y + hr + 4, rW, paint(9f), 2)
        y += maxOf(hLeft, hr) + 12

        // Cadre client (côté fin)
        val cl = input.client
        val cName = if (cl.isCounter) L.counter else cl.name
        val cInfo = listOfNotNull(
            listOf(cl.address, cl.city).filter { it.isNotBlank() }.joinToString(", ").ifBlank { null },
            cl.phone.ifBlank { null }?.let { "${L.tel}: $it" },
            cl.ice.ifBlank { null }?.let { "ICE: $it" },
        ).joinToString("\n")
        val bw = 250f; val bl = W - M - bw
        val h1 = text(c, L.client, bl + 8, y + 6, bw - 16, paint(8f, false, Color.GRAY))
        val h2 = text(c, cName, bl + 8, y + 8 + h1, bw - 16, paint(10.5f, true), userText = true)
        val h3 = if (cInfo.isNotBlank()) text(c, cInfo, bl + 8, y + 10 + h1 + h2, bw - 16, paint(8.5f), userText = true) else 0f
        val boxH = h1 + h2 + h3 + 16
        if (c.pdf != null) {
            val bx = c.x(bl, bw)
            c.canvas.drawRoundRect(RectF(bx, y, bx + bw, y + boxH), 4f, 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.rgb(11, 93, 59); strokeWidth = 0.8f })
        }
        return y + boxH + 12
    }

    private fun drawFooter(c: Ctx, input: Input, top: Float) {
        val co = input.company; val L = c.L
        if (c.pdf != null) c.canvas.drawLine(M, top, W - M, top, Paint().apply { color = Color.LTGRAY; strokeWidth = 0.5f })
        val legal = listOfNotNull(
            co.ice.ifBlank { null }?.let { "ICE: $it" }, co.ifNumber.ifBlank { null }?.let { "IF: $it" },
            co.rc.ifBlank { null }?.let { "RC: $it" }, co.bank.ifBlank { null }?.let { "${L.bank}: $it" },
        ).joinToString(" — ")
        text(c, legal, M, top + 4, W - 2 * M - 70, paint(7.5f, false, Color.DKGRAY), 0, userText = true)
        val pg = if (c.totalPages > 0) "${L.page} ${c.pageNo} ${L.of} ${c.totalPages}" else ""
        text(c, pg, W - M - 70, top + 4, 70f, paint(7.5f, false, Color.DKGRAY), 2)
    }

    companion object {
        fun archiveFile(ctx: Context, d: Document): File =
            File(File(ctx.filesDir, "pdf"), "${d.type.prefix}_${d.number ?: "draft-${d.id}"}_${d.language}.pdf")
        fun previewFile(ctx: Context, d: Document): File =
            File(File(ctx.cacheDir, "preview"), "${d.type.prefix}_${d.number ?: "brouillon-${d.id}"}_${d.language}.pdf")
    }
}
