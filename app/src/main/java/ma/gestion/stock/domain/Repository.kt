package ma.gestion.stock.domain

import androidx.room.withTransaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ma.gestion.stock.data.*
import java.util.Calendar

/**
 * Règles métier. Toute validation (numérotation + mouvements + totaux + acompte)
 * s'exécute dans UNE transaction SQLite, protégée par un Mutex : un double clic
 * ou une interruption ne peut ni doubler les mouvements ni sauter un numéro.
 */
class Repository(val db: AppDatabase) {
    private val validationLock = Mutex()

    // ---------- Stock ----------

    /** Applique un mouvement (stock négatif toujours autorisé). À appeler dans une transaction. */
    private suspend fun applyMove(articleId: Long, qty: Double, type: MoveType, reason: String, docId: Long?, date: Long) {
        val a = db.articles().get(articleId) ?: return
        val after = a.stock + qty
        db.moves().insert(StockMove(articleId = articleId, date = date, type = type, quantity = qty,
            reason = reason, documentId = docId, stockBefore = a.stock, stockAfter = after))
        db.articles().setStock(articleId, after)
    }

    suspend fun createArticle(a: Article, initialQty: Double): Long = db.withTransaction {
        val id = db.articles().insert(a.copy(stock = 0.0))
        if (initialQty != 0.0) applyMove(id, initialQty, MoveType.INITIAL, "Quantité initiale", null, System.currentTimeMillis())
        id
    }

    /** Entrée/sortie/retour manuels. qty toujours positive en entrée ; le signe dépend du type. */
    suspend fun manualMove(articleId: Long, type: MoveType, qty: Double, reason: String) = db.withTransaction {
        val signed = when (type) { MoveType.OUT -> -qty; else -> qty }
        applyMove(articleId, signed, type, reason, null, System.currentTimeMillis())
    }

    /** Ajustement d'inventaire : on saisit le stock compté, le mouvement = écart. */
    suspend fun adjustTo(articleId: Long, counted: Double, reason: String) = db.withTransaction {
        val a = db.articles().get(articleId) ?: return@withTransaction
        applyMove(articleId, counted - a.stock, MoveType.ADJUST, reason, null, System.currentTimeMillis())
    }

    // ---------- Documents ----------

    suspend fun newDraft(type: DocType, clientId: Long, language: String): Long =
        db.documents().insert(Document(type = type, clientId = clientId, language = language))

    suspend fun recomputeDraft(docId: Long) {
        val d = db.documents().get(docId) ?: return
        if (d.status != DocStatus.DRAFT) return
        val t = Calc.totals(db.documents().lines(docId), d.globalDiscountPct)
        db.documents().update(d.copy(totalHt = t.ht, totalTva = t.tva, totalTtc = t.ttc))
    }

    /** Articles qui passeront en négatif si on valide ce document (pour l'avertissement). */
    suspend fun negativeWarnings(docId: Long): List<String> {
        val d = db.documents().get(docId) ?: return emptyList()
        if (!willDeductStock(d)) return emptyList()
        return db.documents().lines(docId).filter { it.articleId != null }
            .groupBy { it.articleId!! }
            .mapNotNull { (aid, ls) ->
                val a = db.articles().get(aid) ?: return@mapNotNull null
                val after = a.stock - ls.sumOf { it.quantity }
                if (after < 0) "${a.designation} (${Money.qty(a.stock)} → ${Money.qty(after)})" else null
            }
    }

    private suspend fun sourceCoversStock(d: Document): Boolean {
        val src = d.sourceDocId?.let { db.documents().get(it) } ?: return false
        return src.type == DocType.BL && src.status == DocStatus.VALIDATED && src.stockCovered
    }

    private suspend fun willDeductStock(d: Document) = when (d.type) {
        DocType.BL -> true
        DocType.FACTURE -> !sourceCoversStock(d)
        else -> false
    }

    private suspend fun nextNumber(type: DocType, date: Long): String {
        val year = Calendar.getInstance().apply { timeInMillis = date }.get(Calendar.YEAR)
        val key = "${type.prefix}-$year"
        val n = (db.misc().counter(key)?.last ?: 0) + 1
        db.misc().saveCounter(Counter(key, n))
        return "%s-%d-%05d".format(type.prefix, year, n)
    }

    /**
     * Valide un brouillon. Idempotent : si le document est déjà validé, rien n'est refait.
     * @param avoirReturned pour un avoir : articles physiquement retournés ?
     */
    suspend fun validate(docId: Long, avoirReturned: Boolean = false): Document = validationLock.withLock {
        db.withTransaction {
            val d = db.documents().get(docId) ?: error("Document introuvable")
            if (d.status == DocStatus.VALIDATED) return@withTransaction d
            val lines = db.documents().lines(docId)
            require(lines.isNotEmpty()) { "Document vide" }
            val now = System.currentTimeMillis()
            val number = nextNumber(d.type, d.date)
            val t = Calc.totals(lines, d.globalDiscountPct)
            val reason = "${labelFr(d.type)} $number"

            var applied = false
            var covered = false
            var returned = false
            when (d.type) {
                DocType.DEVIS -> Unit // aucun mouvement
                DocType.BL -> {
                    lines.filter { it.articleId != null }.forEach { applyMove(it.articleId!!, -it.quantity, MoveType.OUT, reason, d.id, now) }
                    applied = true; covered = true
                }
                DocType.FACTURE -> {
                    if (sourceCoversStock(d)) {
                        covered = true // réutilise les mouvements du BL : pas de double déduction
                    } else {
                        lines.filter { it.articleId != null }.forEach { applyMove(it.articleId!!, -it.quantity, MoveType.OUT, reason, d.id, now) }
                        applied = true; covered = true
                    }
                }
                DocType.AVOIR -> if (avoirReturned) {
                    lines.filter { it.articleId != null }.forEach { applyMove(it.articleId!!, it.quantity, MoveType.RETURN, reason, d.id, now) }
                    applied = true; returned = true
                }
            }
            val validated = d.copy(number = number, status = DocStatus.VALIDATED, totalHt = t.ht, totalTva = t.tva,
                totalTtc = t.ttc, stockApplied = applied, stockCovered = covered, returnedToStock = returned)
            db.documents().update(validated)
            if (d.deposit > 0 && d.type == DocType.FACTURE) {
                db.payments().insert(Payment(clientId = d.clientId, documentId = d.id, amount = d.deposit, method = "DEPOSIT", note = "Acompte"))
            }
            validated
        }
    }

    /** Transforme un document : devis→BL/facture, BL→facture, facture→avoir. Crée un brouillon. */
    suspend fun transform(sourceId: Long, target: DocType): Long = db.withTransaction {
        val s = db.documents().get(sourceId) ?: error("Source introuvable")
        val allowed = when (s.type) {
            DocType.DEVIS -> target == DocType.BL || target == DocType.FACTURE
            DocType.BL -> target == DocType.FACTURE
            DocType.FACTURE -> target == DocType.AVOIR
            DocType.AVOIR -> false
        }
        require(allowed) { "Transformation non autorisée" }
        val id = db.documents().insert(Document(type = target, clientId = s.clientId, language = s.language,
            globalDiscountPct = s.globalDiscountPct, sourceDocId = s.id, note = s.note))
        db.documents().lines(s.id).forEach { db.documents().insertLine(it.copy(id = 0, documentId = id)) }
        id
    }

    suspend fun deleteDraft(docId: Long) {
        val d = db.documents().get(docId) ?: return
        if (d.status == DocStatus.DRAFT) db.documents().delete(d)
    }

    suspend fun quickClient(name: String, phone: String): Long = db.clients().upsert(Client(name = name, phone = phone))

    companion object {
        fun labelFr(t: DocType) = when (t) {
            DocType.DEVIS -> "Devis"; DocType.BL -> "Bon de livraison"; DocType.FACTURE -> "Facture"; DocType.AVOIR -> "Avoir"
        }
    }
}
