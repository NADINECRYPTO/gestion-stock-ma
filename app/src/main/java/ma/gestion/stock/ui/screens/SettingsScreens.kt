package ma.gestion.stock.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ma.gestion.stock.App
import ma.gestion.stock.R
import ma.gestion.stock.backup.BackupManager
import ma.gestion.stock.data.Company
import ma.gestion.stock.ui.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun CompanyScreen(nav: NavController) {
    val app = App.instance
    val stored by app.db.misc().company().collectAsState(null)
    var c by remember { mutableStateOf<Company?>(null) }
    LaunchedEffect(stored) { if (c == null && stored != null) c = stored }
    var pending by remember { mutableStateOf<Pair<String, Uri>?>(null) } // kind, uri
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var kindToPick by remember { mutableStateOf("logo") }
    // Sélecteur système : aucune permission de stockage nécessaire
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) pending = kindToPick to uri }
    val co = c ?: return
    Column {
        TopBar(stringResource(R.string.company), nav)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
            Field(stringResource(R.string.name), co.name, { c = co.copy(name = it) })
            Field(stringResource(R.string.address), co.address, { c = co.copy(address = it) })
            Field(stringResource(R.string.city), co.city, { c = co.copy(city = it) })
            Field(stringResource(R.string.phone), co.phone, { c = co.copy(phone = it) })
            Field(stringResource(R.string.email), co.email, { c = co.copy(email = it) })
            Row { Field(stringResource(R.string.ice), co.ice, { c = co.copy(ice = it) }, Modifier.weight(1f).padding(end = 4.dp))
                  Field(stringResource(R.string.if_), co.ifNumber, { c = co.copy(ifNumber = it) }, Modifier.weight(1f)) }
            Field(stringResource(R.string.rc), co.rc, { c = co.copy(rc = it) })
            Field(stringResource(R.string.bank), co.bank, { c = co.copy(bank = it) })
            Field(stringResource(R.string.vat_rates), co.vatRates, { c = co.copy(vatRates = it) })
            ImageRow(stringResource(R.string.logo), co.logoPath,
                onPick = { kindToPick = "logo"; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onRemove = { c = co.copy(logoPath = null) })
            ImageRow(stringResource(R.string.stamp), co.stampPath,
                onPick = { kindToPick = "stamp"; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onRemove = { c = co.copy(stampPath = null) })
            error?.let { Text(it, color = NegativeRed) }
            if (saved) Text(stringResource(R.string.saved), color = MaterialTheme.colorScheme.primary)
            Button({ scope.launch { app.db.misc().saveCompany(co); saved = true } }, Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(stringResource(R.string.save)) }
        }
    }
    pending?.let { (kind, uri) ->
        val bmp = remember(uri) { runCatching { app.decodePreview(uri) }.getOrNull() }
        AlertDialog(onDismissRequest = { pending = null }, title = { Text(stringResource(R.string.preview_title)) },
            text = { CheckerImage(bmp) },
            confirmButton = { TextButton({
                runCatching { app.storeImage(uri, kind) }
                    .onSuccess { path ->
                        val nc = if (kind == "logo") co.copy(logoPath = path) else co.copy(stampPath = path)
                        c = nc; scope.launch { app.db.misc().saveCompany(nc); saved = true }
                    }.onFailure { error = it.message }
                pending = null
            }, enabled = bmp != null) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton({ pending = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

/** Fond en damier pour vérifier la transparence des PNG. */
@Composable
private fun CheckerImage(bmp: Bitmap?) {
    Box(Modifier.fillMaxWidth().height(180.dp).background(Color(0xFFEEEEEE)), contentAlignment = Alignment.Center) {
        if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize().padding(8.dp)) else Text("—")
    }
}

@Composable
private fun ImageRow(label: String, path: String?, onPick: () -> Unit, onRemove: () -> Unit) {
    val bmp = remember(path) { App.instance.file(path)?.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) } }
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(72.dp).background(Color(0xFFF2F2F2)), contentAlignment = Alignment.Center) {
                if (bmp != null) Image(bmp.asImageBitmap(), label, Modifier.fillMaxSize()) else Icon(Icons.Default.Image, null)
            }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(label)
                TextButton(onPick) { Text(stringResource(R.string.import_image)) }
            }
            if (path != null) IconButton(onRemove) { Icon(Icons.Default.Delete, stringResource(R.string.remove)) }
        }
    }
}

@Composable
fun SettingsScreen(nav: NavController) {
    val ctx = LocalContext.current
    val app = App.instance
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf<String?>(null) }
    var verified by remember { mutableStateOf<BackupManager.Verified?>(null) }
    var busy by remember { mutableStateOf(false) }
    val current = AppCompatDelegate.getApplicationLocales().toLanguageTags().ifBlank { Locale.getDefault().language }
    val okMsg = stringResource(R.string.backup_ok)
    val invalidFmt = stringResource(R.string.restore_invalid, "%s")
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) scope.launch { busy = true
            msg = withContext(Dispatchers.IO) { runCatching { app.backup.export(app.db, uri) }.fold({ okMsg }, { it.message }) }; busy = false }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { busy = true
            withContext(Dispatchers.IO) { runCatching { app.backup.verify(uri) } }
                .onSuccess { verified = it }.onFailure { msg = invalidFmt.format(it.message) }
            busy = false }
    }
    Column {
        TopBar(stringResource(R.string.nav_settings))
        Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ nav.navigate("company") }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Business, null); Text(" " + stringResource(R.string.company)) }
            Text(stringResource(R.string.language), style = MaterialTheme.typography.titleMedium)
            Row { listOf("fr" to "Français", "ar" to "العربية").forEach { (code, l) ->
                FilterChip(current.startsWith(code), {
                    // Mémorisé automatiquement (autoStoreLocales) ; bascule RTL pour l'arabe
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(code))
                }, { Text(l) }, Modifier.padding(end = 8.dp)) } }
            HorizontalDivider()
            Text(stringResource(R.string.backup_desc))
            val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date())
            Button({ exporter.launch("sauvegarde-gestion-$stamp.gsbak") }, Modifier.fillMaxWidth(), enabled = !busy) {
                Icon(Icons.Default.Backup, null); Text(" " + stringResource(R.string.backup)) }
            OutlinedButton({ importer.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth(), enabled = !busy) {
                Icon(Icons.Default.Restore, null); Text(" " + stringResource(R.string.restore)) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            msg?.let { Text(it) }
        }
    }
    verified?.let { v -> AlertDialog(onDismissRequest = { verified = null },
        icon = { Icon(Icons.Default.Warning, null, tint = NegativeRed) },
        title = { Text(stringResource(R.string.restore)) },
        text = { Text(stringResource(R.string.restore_confirm, v.summary)) },
        confirmButton = { TextButton({ verified = null; app.restoreAndRestart(v, ctx) }) { Text(stringResource(R.string.confirm), color = NegativeRed) } },
        dismissButton = { TextButton({ verified = null }) { Text(stringResource(R.string.cancel)) } }) }
}
