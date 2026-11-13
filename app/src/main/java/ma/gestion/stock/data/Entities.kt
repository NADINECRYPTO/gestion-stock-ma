package ma.gestion.stock.data

import androidx.room.*

/** Montants stockés en centimes (Long) pour éviter les erreurs d'arrondi. Devise : MAD (DH). */

@Entity(tableName = "clients", indices = [Index("name")])
data class Client(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String = "",
    val address: String = "",
    val city: String = "",
    val ice: String = "",
    val isCounter: Boolean = false,
)

@Entity(tableName = "articles", indices = [Index(value = ["ref"], unique = true), Index("designation")])
data class Article(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ref: String,
    val designation: String,
    val family: String = "",
    val unit: String = "U",
    val purchasePrice: Long = 0,
    val salePrice: Long = 0,
    val vatRate: Double = 20.0,
    val stock: Double = 0.0,
)

enum class MoveType { INITIAL, IN, OUT, RETURN, ADJUST }

@Entity(
    tableName = "stock_moves",
    indices = [Index("articleId"), Index("documentId")],
    foreignKeys = [ForeignKey(entity = Article::class, parentColumns = ["id"], childColumns = ["articleId"], onDelete = ForeignKey.CASCADE)]
)
data class StockMove(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val articleId: Long,
    val date: Long,
    val type: MoveType,
    /** Quantité signée : + entrée, − sortie */
    val quantity: Double,
    val reason: String,
    val documentId: Long? = null,
    val stockBefore: Double,
    val stockAfter: Double,
)

enum class DocType(val prefix: String) { DEVIS("DV"), BL("BL"), FACTURE("FA"), AVOIR("AV") }
enum class DocStatus { DRAFT, VALIDATED }

@Entity(tableName = "documents", indices = [Index("clientId"), Index(value = ["type", "number"], unique = true), Index("sourceDocId")])
data class Document(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: DocType,
    val number: String? = null,
    val status: DocStatus = DocStatus.DRAFT,
    val clientId: Long,
    val date: Long = System.currentTimeMillis(),
    val language: String = "fr",
    val globalDiscountPct: Double = 0.0,
    val deposit: Long = 0,
    val sourceDocId: Long? = null,
    /** true si ce document a lui-même généré des mouvements de stock */
    val stockApplied: Boolean = false,
    /** true si le stock est couvert (par ce document ou par le BL source) */
    val stockCovered: Boolean = false,
    val returnedToStock: Boolean = false,
    val totalHt: Long = 0,
    val totalTva: Long = 0,
    val totalTtc: Long = 0,
    val pdfPath: String? = null,
    val note: String = "",
)

@Entity(
    tableName = "doc_lines", indices = [Index("documentId"), Index("articleId")],
    foreignKeys = [ForeignKey(entity = Document::class, parentColumns = ["id"], childColumns = ["documentId"], onDelete = ForeignKey.CASCADE)]
)
data class DocLine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long,
    val position: Int = 0,
    val articleId: Long?,
    val ref: String = "",
    val designation: String,
    val unit: String = "",
    val quantity: Double,
    val unitPrice: Long,
    val discountPct: Double = 0.0,
    val vatRate: Double = 20.0,
)

@Entity(tableName = "payments", indices = [Index("clientId"), Index("documentId")])
data class Payment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientId: Long,
    val documentId: Long? = null,
    val date: Long = System.currentTimeMillis(),
    val amount: Long,
    val method: String = "CASH",
    val note: String = "",
)

@Entity(tableName = "counters")
data class Counter(@PrimaryKey val key: String, val last: Int)

@Entity(tableName = "company")
data class Company(
    @PrimaryKey val id: Int = 1,
    val name: String = "",
    val address: String = "",
    val city: String = "",
    val phone: String = "",
    val email: String = "",
    val ice: String = "",
    val ifNumber: String = "",
    val rc: String = "",
    val bank: String = "",
    val logoPath: String? = null,
    val stampPath: String? = null,
    val vatRates: String = "0;7;10;14;20",
)

data class ClientBalance(val clientId: Long, val invoiced: Long, val credited: Long, val paid: Long) {
    val due get() = invoiced - credited - paid
}

data class DocWithClient(
    @Embedded val doc: Document,
    @ColumnInfo(name = "clientName") val clientName: String,
    @ColumnInfo(name = "clientIsCounter") val clientIsCounter: Boolean,
)
