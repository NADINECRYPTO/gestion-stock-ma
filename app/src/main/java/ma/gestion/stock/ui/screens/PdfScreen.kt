package ma.gestion.stock.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ma.gestion.stock.App
import ma.gestion.stock.R
import ma.gestion.stock.ui.TopBar
import java.io.File
import java.io.FileOutputStream

@Composable
fun PdfScreen(nav: NavController, id: Long, lang: String?) {
    val ctx = LocalContext.current
    var file by remember { mutableStateOf<File?>(null) }
    var pages by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(id, lang) {
        runCatching {
            val f = App.instance.pdfFor(id, lang); file = f
            pages = withContext(Dispatchers.IO) { renderPages(f) }
        }.onFailure { error = it.message }
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val f = file ?: return@rememberLauncherForActivityResult
        if (uri != null) scope.launch(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } } }
    }
    Column {
        TopBar(file?.name ?: stringResource(R.string.pdf_preview), nav) {
            file?.let { f ->
                IconButton({ saver.launch(f.name) }) { Icon(Icons.Default.Save, stringResource(R.string.save_pdf)) }
                IconButton({ share(ctx, f) }) { Icon(Icons.Default.Share, stringResource(R.string.share)) }
                IconButton({ print(ctx, f) }) { Icon(Icons.Default.Print, stringResource(R.string.print)) }
            }
        }
        error?.let { Text(it, Modifier.padding(16.dp), color = Color.Red) }
        if (pages.isEmpty() && error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.fillMaxSize().background(Color(0xFFDDDDDD)), contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(pages) { b -> Image(b.asImageBitmap(), null, Modifier.fillMaxWidth().aspectRatio(b.width.toFloat() / b.height)) }
        }
    }
}

private fun renderPages(f: File): List<Bitmap> {
    ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
        PdfRenderer(pfd).use { r ->
            return (0 until r.pageCount).map { i ->
                r.openPage(i).use { p ->
                    val scale = 2
                    Bitmap.createBitmap(p.width * scale, p.height * scale, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(AColor.WHITE); p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
    }
}

private fun share(ctx: Context, f: File) {
    // Copie dans le cache pour partager aussi bien les archives que les aperçus via FileProvider
    val shared = File(ctx.cacheDir, "share").apply { mkdirs() }.let { File(it, f.name) }
    f.copyTo(shared, overwrite = true)
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", shared)
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }, f.name))
}

/** Impression via le service d'impression Android (imprimante compatible requise). */
private fun print(ctx: Context, f: File) {
    val pm = ctx.getSystemService(Context.PRINT_SERVICE) as PrintManager
    pm.print(f.nameWithoutExtension, object : PrintDocumentAdapter() {
        override fun onLayout(old: PrintAttributes?, new: PrintAttributes, cs: CancellationSignal?, cb: LayoutResultCallback, extras: Bundle?) {
            if (cs?.isCanceled == true) { cb.onLayoutCancelled(); return }
            cb.onLayoutFinished(PrintDocumentInfo.Builder(f.name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), true)
        }
        override fun onWrite(pages: Array<out PageRange>?, dest: ParcelFileDescriptor, cs: CancellationSignal?, cb: WriteResultCallback) {
            runCatching { f.inputStream().use { i -> FileOutputStream(dest.fileDescriptor).use { i.copyTo(it) } } }
                .onSuccess { cb.onWriteFinished(arrayOf(PageRange.ALL_PAGES)) }.onFailure { cb.onWriteFailed(it.message) }
        }
    }, PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build())
}
