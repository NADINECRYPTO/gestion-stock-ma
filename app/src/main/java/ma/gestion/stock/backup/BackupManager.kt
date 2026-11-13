package ma.gestion.stock.backup

import android.content.Context
import android.net.Uri
import ma.gestion.stock.data.AppDatabase
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Sauvegarde complète (.gsbak = ZIP) :
 *   manifest.json  — format, version, date, empreintes SHA-256 de chaque fichier
 *   db/gestion.db  — base SQLite (après checkpoint WAL)
 *   files/images/… — logo, cachet
 *   files/pdf/…    — PDF archivés tels qu'émis
 * La restauration vérifie le manifeste, chaque empreinte et l'en-tête SQLite AVANT de toucher aux données.
 */
class BackupManager(private val ctx: Context) {
    companion object {
        const val FORMAT = "gestion-stock-ma-backup"
        const val VERSION = 1
        private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray()
    }

    data class Verified(val dir: File, val createdAt: Long, val fileCount: Int, val summary: String)

    private fun sha256(f: File): String = MessageDigest.getInstance("SHA-256").let { md ->
        f.inputStream().use { ins -> val buf = ByteArray(64 * 1024); while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun dataFiles(): List<Pair<String, File>> {
        val out = mutableListOf<Pair<String, File>>()
        listOf("images", "pdf").forEach { sub ->
            File(ctx.filesDir, sub).walkTopDown().filter { it.isFile }.forEach { f ->
                out += "files/" + f.relativeTo(ctx.filesDir).invariantSeparatorsPath to f
            }
        }
        return out
    }

    fun export(db: AppDatabase, target: Uri) {
        // Checkpoint pour que tout soit dans le fichier principal
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        val dbFile = ctx.getDatabasePath(AppDatabase.NAME)
        val snapshot = File(ctx.cacheDir, "snapshot.db").also { dbFile.copyTo(it, overwrite = true) }
        val entries = listOf("db/${AppDatabase.NAME}" to snapshot) + dataFiles()
        val manifest = JSONObject().apply {
            put("format", FORMAT); put("version", VERSION); put("createdAt", System.currentTimeMillis())
            put("files", JSONObject().apply { entries.forEach { (name, f) -> put(name, sha256(f)) } })
        }
        ctx.contentResolver.openOutputStream(target, "w")!!.use { os ->
            ZipOutputStream(os.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest.toString(2).toByteArray()); zip.closeEntry()
                entries.forEach { (name, f) -> zip.putNextEntry(ZipEntry(name)); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
            }
        }
        snapshot.delete()
    }

    /** Extrait et vérifie, sans modifier les données en place. Lève une exception explicite si invalide. */
    fun verify(source: Uri): Verified {
        val dir = File(ctx.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        ctx.contentResolver.openInputStream(source)!!.use { ins ->
            ZipInputStream(ins.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val out = File(dir, e.name).canonicalFile
                    require(out.path.startsWith(dir.canonicalPath + File.separator)) { "chemin interdit (${e.name})" }
                    if (e.isDirectory) { out.mkdirs(); continue }
                    out.parentFile?.mkdirs(); out.outputStream().use { zip.copyTo(it) }
                }
            }
        }
        val mf = File(dir, "manifest.json")
        require(mf.exists()) { "manifest.json absent" }
        val json = JSONObject(mf.readText())
        require(json.optString("format") == FORMAT) { "format inconnu" }
        require(json.optInt("version") in 1..VERSION) { "version non prise en charge" }
        val files = json.getJSONObject("files")
        val db = File(dir, "db/${AppDatabase.NAME}")
        require(files.has("db/${AppDatabase.NAME}") && db.exists()) { "base de données absente" }
        files.keys().forEach { name ->
            val f = File(dir, name)
            require(f.exists()) { "fichier manquant : $name" }
            require(sha256(f) == files.getString(name)) { "fichier corrompu : $name" }
        }
        val head = db.inputStream().use { s -> ByteArray(16).also { s.read(it) } }
        require(head.contentEquals(SQLITE_MAGIC)) { "base SQLite invalide" }
        val created = json.optLong("createdAt")
        val n = files.length()
        val date = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.FRANCE).format(java.util.Date(created))
        return Verified(dir, created, n, "$date — $n fichiers")
    }

    /** Remplace les données. La base doit être FERMÉE avant l'appel ; l'app redémarre ensuite. */
    fun apply(v: Verified) {
        val dbFile = ctx.getDatabasePath(AppDatabase.NAME)
        listOf("", "-wal", "-shm", "-journal").forEach { File(dbFile.path + it).delete() }
        dbFile.parentFile?.mkdirs()
        File(v.dir, "db/${AppDatabase.NAME}").copyTo(dbFile, overwrite = true)
        listOf("images", "pdf").forEach { sub ->
            File(ctx.filesDir, sub).deleteRecursively()
            val src = File(v.dir, "files/$sub")
            if (src.exists()) src.copyRecursively(File(ctx.filesDir, sub), overwrite = true)
        }
        // Les chemins (logo, cachet, PDF) sont relatifs à filesDir : valides sur n'importe quel appareil.
        v.dir.deleteRecursively()
    }
}
