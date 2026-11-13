package ma.gestion.stock

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ma.gestion.stock.backup.BackupManager
import ma.gestion.stock.data.*
import ma.gestion.stock.domain.Repository
import ma.gestion.stock.pdf.PdfGenerator
import java.io.File
import kotlin.system.exitProcess

class App : Application() {
    lateinit var db: AppDatabase; private set
    lateinit var repo: Repository; private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        db = AppDatabase.build(this)
        repo = Repository(db)
    }

    /** Valide le document puis archive son PDF (copie figée, réimprimable telle qu'émise). */
    suspend fun validateAndArchive(docId: Long, avoirReturned: Boolean): Document = withContext(Dispatchers.IO) {
        val d = repo.validate(docId, avoirReturned)
        if (d.pdfPath == null || !File(filesDir, d.pdfPath).exists()) {
            val out = PdfGenerator.archiveFile(this@App, d)
            PdfGenerator(this@App).generate(pdfInput(d), out)
            db.documents().setPdf(d.id, out.relativeTo(filesDir).invariantSeparatorsPath)
        }
        db.documents().get(docId)!!
    }

    /** PDF à afficher : l'archive figée si le document est validé et dans sa langue d'émission, sinon un aperçu. */
    suspend fun pdfFor(docId: Long, language: String?): File = withContext(Dispatchers.IO) {
        var d = db.documents().get(docId)!!
        if (d.status == DocStatus.VALIDATED && (language == null || language == d.language) && d.pdfPath != null) {
            val f = File(filesDir, d.pdfPath!!); if (f.exists()) return@withContext f
        }
        if (language != null) d = d.copy(language = language)
        val out = PdfGenerator.previewFile(this@App, d)
        PdfGenerator(this@App).generate(pdfInput(d), out)
        out
    }

    private suspend fun pdfInput(d: Document) = PdfGenerator.Input(
        doc = d, lines = db.documents().lines(d.id), client = db.clients().get(d.clientId)!!,
        company = db.misc().companyNow() ?: Company(), paid = db.payments().paidForDoc(d.id),
        sourceNumber = d.sourceDocId?.let { db.documents().get(it)?.number },
    )

    // ---------- Images (logo / cachet) ----------
    /** Lit l'image choisie (PNG/JPEG) pour l'aperçu. */
    fun decodePreview(uri: Uri): Bitmap? = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }

    /** Copie l'image dans le stockage privé ; PNG gardé tel quel (transparence), JPEG gardé tel quel. Retourne le chemin relatif. */
    fun storeImage(uri: Uri, kind: String): String {
        val mime = contentResolver.getType(uri) ?: ""
        val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val isPng = mime.contains("png") || (bytes.size > 4 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte())
        val isJpeg = mime.contains("jpeg") || mime.contains("jpg") || (bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte())
        require(isPng || isJpeg) { "Format non pris en charge (PNG ou JPEG)" }
        requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "Image illisible" }
        val dir = File(filesDir, "images").apply { mkdirs() }
        val f = File(dir, "${kind}_${System.currentTimeMillis()}.${if (isPng) "png" else "jpg"}")
        f.writeBytes(bytes)
        return f.relativeTo(filesDir).invariantSeparatorsPath
    }

    fun file(rel: String?) = rel?.let { File(filesDir, it) }

    // ---------- Sauvegarde ----------
    val backup by lazy { BackupManager(this) }

    fun restoreAndRestart(v: BackupManager.Verified, from: Context) {
        db.close()
        backup.apply(v)
        val i = packageManager.getLaunchIntentForPackage(packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        from.startActivity(i)
        exitProcess(0)
    }

    companion object { lateinit var instance: App; private set }
}
