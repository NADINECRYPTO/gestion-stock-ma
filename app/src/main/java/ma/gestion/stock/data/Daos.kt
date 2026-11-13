package ma.gestion.stock.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ClientDao {
    @Query("SELECT * FROM clients ORDER BY isCounter DESC, name COLLATE NOCASE")
    fun all(): Flow<List<Client>>

    @Query("SELECT * FROM clients WHERE name LIKE '%' || :q || '%' OR phone LIKE '%' || :q || '%' ORDER BY isCounter DESC, name COLLATE NOCASE")
    fun search(q: String): Flow<List<Client>>

    @Query("SELECT * FROM clients WHERE id = :id") suspend fun get(id: Long): Client?
    @Query("SELECT * FROM clients WHERE id = :id") fun observe(id: Long): Flow<Client?>
    @Query("SELECT * FROM clients WHERE isCounter = 1 LIMIT 1") suspend fun counter(): Client?
    @Upsert suspend fun upsert(c: Client): Long

    @Query("""
        SELECT :id AS clientId,
          COALESCE((SELECT SUM(totalTtc) FROM documents WHERE clientId = :id AND status = 'VALIDATED' AND type = 'FACTURE'), 0) AS invoiced,
          COALESCE((SELECT SUM(totalTtc) FROM documents WHERE clientId = :id AND status = 'VALIDATED' AND type = 'AVOIR'), 0) AS credited,
          COALESCE((SELECT SUM(amount) FROM payments WHERE clientId = :id), 0) AS paid
    """)
    fun balance(id: Long): Flow<ClientBalance>

    @Query("""
        SELECT COALESCE((SELECT SUM(totalTtc) FROM documents WHERE status='VALIDATED' AND type='FACTURE'),0)
             - COALESCE((SELECT SUM(totalTtc) FROM documents WHERE status='VALIDATED' AND type='AVOIR'),0)
             - COALESCE((SELECT SUM(amount) FROM payments),0)
    """)
    fun totalDue(): Flow<Long>
}

@Dao
interface ArticleDao {
    @Query("""SELECT * FROM articles
        WHERE (designation LIKE '%' || :q || '%' OR ref LIKE '%' || :q || '%')
          AND (:negOnly = 0 OR stock < 0)
        ORDER BY designation COLLATE NOCASE""")
    fun search(q: String, negOnly: Boolean): Flow<List<Article>>

    @Query("SELECT * FROM articles WHERE id = :id") suspend fun get(id: Long): Article?
    @Query("SELECT * FROM articles WHERE id = :id") fun observe(id: Long): Flow<Article?>
    @Query("SELECT COUNT(*) FROM articles WHERE stock < 0") fun negativeCount(): Flow<Int>
    @Insert suspend fun insert(a: Article): Long
    @Update suspend fun update(a: Article)
    @Query("UPDATE articles SET stock = :stock WHERE id = :id") suspend fun setStock(id: Long, stock: Double)
    @Query("SELECT DISTINCT family FROM articles WHERE family <> '' ORDER BY family") fun families(): Flow<List<String>>
}

@Dao
interface StockMoveDao {
    @Insert suspend fun insert(m: StockMove): Long
    @Query("SELECT * FROM stock_moves WHERE articleId = :articleId ORDER BY date DESC, id DESC")
    fun forArticle(articleId: Long): Flow<List<StockMove>>
    @Query("SELECT * FROM stock_moves WHERE documentId = :docId") suspend fun forDocument(docId: Long): List<StockMove>
}

@Dao
interface DocumentDao {
    @Query("""SELECT d.*, c.name AS clientName, c.isCounter AS clientIsCounter FROM documents d JOIN clients c ON c.id = d.clientId
        WHERE (:type IS NULL OR d.type = :type)
        ORDER BY d.date DESC, d.id DESC""")
    fun list(type: DocType?): Flow<List<DocWithClient>>

    @Query("""SELECT d.*, c.name AS clientName, c.isCounter AS clientIsCounter FROM documents d JOIN clients c ON c.id = d.clientId
        WHERE d.clientId = :clientId ORDER BY d.date DESC""")
    fun forClient(clientId: Long): Flow<List<DocWithClient>>

    @Query("SELECT * FROM documents WHERE id = :id") suspend fun get(id: Long): Document?
    @Query("SELECT * FROM documents WHERE id = :id") fun observe(id: Long): Flow<Document?>
    @Query("SELECT COUNT(*) FROM documents WHERE status = 'DRAFT'") fun draftCount(): Flow<Int>
    @Insert suspend fun insert(d: Document): Long
    @Update suspend fun update(d: Document)
    @Delete suspend fun delete(d: Document)
    @Query("UPDATE documents SET pdfPath = :path WHERE id = :id") suspend fun setPdf(id: Long, path: String)

    @Query("SELECT * FROM doc_lines WHERE documentId = :docId ORDER BY position, id") suspend fun lines(docId: Long): List<DocLine>
    @Query("SELECT * FROM doc_lines WHERE documentId = :docId ORDER BY position, id") fun observeLines(docId: Long): Flow<List<DocLine>>
    @Insert suspend fun insertLine(l: DocLine): Long
    @Update suspend fun updateLine(l: DocLine)
    @Delete suspend fun deleteLine(l: DocLine)
}

@Dao
interface PaymentDao {
    @Insert suspend fun insert(p: Payment): Long
    @Delete suspend fun delete(p: Payment)
    @Query("SELECT * FROM payments WHERE clientId = :clientId ORDER BY date DESC") fun forClient(clientId: Long): Flow<List<Payment>>
    @Query("SELECT * FROM payments WHERE documentId = :docId ORDER BY date") fun forDocument(docId: Long): Flow<List<Payment>>
    @Query("SELECT COALESCE(SUM(amount),0) FROM payments WHERE documentId = :docId") suspend fun paidForDoc(docId: Long): Long
}

@Dao
interface MiscDao {
    @Query("SELECT * FROM counters WHERE `key` = :key") suspend fun counter(key: String): Counter?
    @Upsert suspend fun saveCounter(c: Counter)
    @Query("SELECT * FROM company WHERE id = 1") fun company(): Flow<Company?>
    @Query("SELECT * FROM company WHERE id = 1") suspend fun companyNow(): Company?
    @Upsert suspend fun saveCompany(c: Company)
}
