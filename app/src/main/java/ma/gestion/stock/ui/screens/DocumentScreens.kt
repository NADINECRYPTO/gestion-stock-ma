package ma.gestion.stock.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import ma.gestion.stock.App
import ma.gestion.stock.R
import ma.gestion.stock.data.*
import ma.gestion.stock.domain.Calc
import ma.gestion.stock.domain.Money
import ma.gestion.stock.ui.*
import java.util.Date
import java.util.Locale

@Composable
fun DocumentsScreen(nav: NavController) {
    var type by remember { mutableStateOf<DocType?>(null) }
    val list by App.instance.db.documents().list(type).collectAsState(emptyList())
    var newDoc by remember { mutableStateOf(false) }
    Scaffold(topBar = { TopBar(stringResource(R.string.nav_documents)) },
        floatingActionButton = { FloatingActionButton({ newDoc = true }) { Icon(Icons.Default.Add, stringResource(R.string.new_document)) } }) { pad ->
        Column(Modifier.padding(pad)) {
            LazyRow(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { FilterChip(type == null, { type = null }, { Text(stringResource(R.string.all)) }) }
                items(DocType.entries) { t -> FilterChip(type == t, { type = t }, { Text(docTypeLabel(t)) }) }
            }
            if (list.isEmpty()) EmptyState()
            LazyColumn {
                items(list, key = { it.doc.id }) { d ->
                    ListItem(modifier = Modifier.clickable { nav.navigate("doc/${d.doc.id}") },
                        headlineContent = { Text("${docTypeLabel(d.doc.type)} ${d.doc.number ?: ""}") },
                        supportingContent = { Text("${clientName(d.clientName, d.clientIsCounter)} · ${dateFmt.format(Date(d.doc.date))}") },
                        trailingContent = { Column(horizontalAlignment = Alignment.End) {
                            Text(Money.dh(d.doc.totalTtc), fontWeight = FontWeight.Bold)
                            Text(statusLabel(d.doc.status), color = if (d.doc.status == DocStatus.DRAFT) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary)
                        } })
                    HorizontalDivider()
                }
            }
        }
    }
    if (newDoc) NewDocumentDialog(nav, null) { newDoc = false }
}

/** Choix du type → brouillon avec le client Comptoir (modifiable ensuite). */
@Composable
fun NewDocumentDialog(nav: NavController, clientId: Long?, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val app = App.instance
    val uiLang = if (Locale.getDefault().language == "ar") "ar" else "fr"
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.new_document)) },
        text = { Column { listOf(DocType.DEVIS, DocType.BL, DocType.FACTURE).forEach { t ->
            TextButton({ scope.launch {
                val cid = clientId ?: app.db.clients().counter()!!.id
                val id = app.repo.newDraft(t, cid, uiLang)
                onDismiss(); nav.navigate("doc/$id")
            } }, Modifier.fillMaxWidth()) { Text(docTypeLabel(t)) }
        } } },
        confirmButton = {}, dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
fun DocumentScreen(nav: NavController, id: Long) {
    val app = App.instance; val db = app.db
    val doc by db.documents().observe(id).collectAsState(null)
    val lines by db.documents().observeLines(id).collectAsState(emptyList())
    val payments by db.payments().forDocument(id).collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    var pickClient by remember { mutableStateOf(false) }
    var pickArticle by remember { mutableStateOf(false) }
    var editLine by remember { mutableStateOf<DocLine?>(null) }
    var negList by remember { mutableStateOf<List<String>?>(null) }
    var askReturn by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pay by remember { mutableStateOf(false) }
    val d = doc ?: return
    val client by db.clients().observe(d.clientId).collectAsState(null)
    val draft = d.status == DocStatus.DRAFT
    val totals = Calc.totals(lines, d.globalDiscountPct)

    fun update(nd: Document) = scope.launch { db.documents().update(nd); app.repo.recomputeDraft(id) }
    fun doValidate(returned: Boolean) {
        if (busy) return; busy = true // anti double clic (en plus du verrou + transaction)
        scope.launch {
            runCatching { app.validateAndArchive(id, returned) }.onFailure { error = it.message }
            busy = false
        }
    }
    fun startValidate() = scope.launch {
        if (d.type == DocType.AVOIR) { askReturn = true; return@launch }
        val w = app.repo.negativeWarnings(id)
        if (w.isNotEmpty()) negList = w else doValidate(false)
    }

    Column {
        TopBar("${docTypeLabel(d.type)} ${d.number ?: ""}", nav) {
            if (draft) IconButton({ scope.launch { app.repo.deleteDraft(id); nav.popBackStack() } }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
            IconButton({ nav.navigate("pdf/$id") }) { Icon(Icons.Default.PictureAsPdf, stringResource(R.string.pdf_preview)) }
        }
        LazyColumn(Modifier.padding(horizontal = 12.dp).weight(1f)) {
            item {
                Text("${statusLabel(d.status)} · ${dateFmt.format(Date(d.date))}", color = MaterialTheme.colorScheme.secondary)
                ListItem(headlineContent = { Text(client?.let { clientName(it.name, it.isCounter) } ?: "") },
                    overlineContent = { Text(stringResource(R.string.client)) },
                    trailingContent = { if (draft) TextButton({ pickClient = true }) { Text(stringResource(R.string.edit)) } },
                    modifier = Modifier.clickable { client?.let { nav.navigate("client/${it.id}") } })
                Text(stringResource(R.string.doc_language))
                Row { listOf("fr" to R.string.lang_fr, "ar" to R.string.lang_ar).forEach { (code, l) ->
                    FilterChip(d.language == code, { if (draft) update(d.copy(language = code)) else nav.navigate("pdf/$id?lang=$code") },
                        { Text(stringResource(l)) }, Modifier.padding(end = 6.dp)) } }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
            }
            items(lines, key = { it.id }) { l ->
                ListItem(modifier = Modifier.clickable(enabled = draft) { editLine = l },
                    headlineContent = { Text(l.designation) },
                    supportingContent = { Text("${l.ref} · ${Money.qty(l.quantity)} × ${Money.format(l.unitPrice)}" +
                        (if (l.discountPct > 0) " −${Money.qty(l.discountPct)}%" else "") + " · TVA ${Money.qty(l.vatRate)}%") },
                    trailingContent = { Text(Money.format(Calc.lineHt(l)), fontWeight = FontWeight.Bold) })
                HorizontalDivider()
            }
            item {
                if (draft) OutlinedButton({ pickArticle = true }, Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Icon(Icons.Default.Add, null); Text(stringResource(R.string.add_line)) }
                if (draft) Row {
                    var gd by remember(d.id) { mutableStateOf(Money.qty(d.globalDiscountPct)) }
                    var dep by remember(d.id) { mutableStateOf(if (d.deposit == 0L) "" else Money.format(d.deposit)) }
                    Field(stringResource(R.string.global_discount), gd, { gd = it; Money.parseQty(it)?.let { v -> update(d.copy(globalDiscountPct = v.coerceIn(0.0, 100.0))) } },
                        Modifier.weight(1f).padding(end = 4.dp), number = true)
                    if (d.type == DocType.FACTURE) Field(stringResource(R.string.deposit), dep, { dep = it; update(d.copy(deposit = Money.parse(it) ?: 0)) }, Modifier.weight(1f), number = true)
                }
                Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        TotalRow(stringResource(R.string.total_ht), Money.dh(totals.ht))
                        totals.byRate.forEach { (r, v) -> TotalRow("${stringResource(R.string.total_tva)} ${Money.qty(r)}%", Money.dh(v.second)) }
                        TotalRow(stringResource(R.string.total_ttc), Money.dh(totals.ttc), true)
                        if (d.type == DocType.FACTURE && !draft) {
                            val paid = payments.sumOf { it.amount }
                            TotalRow(stringResource(R.string.payments), Money.dh(paid))
                            TotalRow(stringResource(R.string.balance_due), Money.dh(totals.ttc - paid), true)
                        }
                    }
                }
                error?.let { Text(it, color = NegativeRed) }
                if (draft) Button({ startValidate() }, Modifier.fillMaxWidth(), enabled = !busy && lines.isNotEmpty()) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp)) else Text(stringResource(R.string.validate)) }
                else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val targets = when (d.type) {
                        DocType.DEVIS -> listOf(DocType.BL to R.string.to_bl, DocType.FACTURE to R.string.to_invoice)
                        DocType.BL -> listOf(DocType.FACTURE to R.string.to_invoice)
                        DocType.FACTURE -> listOf(DocType.AVOIR to R.string.to_avoir)
                        DocType.AVOIR -> emptyList()
                    }
                    targets.forEach { (t, l) -> OutlinedButton({ scope.launch { val nid = app.repo.transform(id, t); nav.navigate("doc/$nid") } },
                        Modifier.fillMaxWidth()) { Text(stringResource(l)) } }
                    if (d.type == DocType.FACTURE) Button({ pay = true }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.add_payment)) }
                    Button({ nav.navigate("pdf/$id") }, Modifier.fillMaxWidth()) { Icon(Icons.Default.PictureAsPdf, null); Text(" " + stringResource(R.string.regen_pdf)) }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (pickClient) ClientPicker({ pickClient = false }) { cid -> pickClient = false; update(d.copy(clientId = cid)) }
    if (pickArticle) ArticlePicker({ pickArticle = false }) { a ->
        pickArticle = false
        scope.launch {
            db.documents().insertLine(DocLine(documentId = id, position = lines.size, articleId = a.id, ref = a.ref,
                designation = a.designation, unit = a.unit, quantity = 1.0, unitPrice = a.salePrice, vatRate = a.vatRate))
            app.repo.recomputeDraft(id)
        }
    }
    editLine?.let { l -> LineDialog(l, { editLine = null }) { nl ->
        editLine = null
        scope.launch { if (nl == null) db.documents().deleteLine(l) else db.documents().updateLine(nl); app.repo.recomputeDraft(id) }
    } }
    negList?.let { w -> AlertDialog(onDismissRequest = { negList = null },
        icon = { Icon(Icons.Default.Warning, null, tint = NegativeRed) },
        title = { Text(stringResource(R.string.neg_warning_title)) },
        text = { Text(stringResource(R.string.neg_warning, w.joinToString("\n• ", "\n• "))) },
        confirmButton = { TextButton({ negList = null; doValidate(false) }) { Text(stringResource(R.string.validate)) } },
        dismissButton = { TextButton({ negList = null }) { Text(stringResource(R.string.cancel)) } }) }
    if (askReturn) AlertDialog(onDismissRequest = { askReturn = false },
        title = { Text(stringResource(R.string.avoir_return_title)) }, text = { Text(stringResource(R.string.avoir_return_q)) },
        confirmButton = { TextButton({ askReturn = false; doValidate(true) }) { Text(stringResource(R.string.yes)) } },
        dismissButton = { TextButton({ askReturn = false; doValidate(false) }) { Text(stringResource(R.string.no)) } })
    if (pay) PaymentDialog(d.clientId, d.id) { pay = false }
}

@Composable
private fun TotalRow(k: String, v: String, strong: Boolean = false) = Row(Modifier.fillMaxWidth()) {
    Text(k, Modifier.weight(1f), fontWeight = if (strong) FontWeight.Bold else null)
    Text(v, fontWeight = if (strong) FontWeight.Bold else null, color = if (strong) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun LineDialog(l: DocLine, onDismiss: () -> Unit, onDone: (DocLine?) -> Unit) {
    var des by remember { mutableStateOf(l.designation) }
    var qty by remember { mutableStateOf(Money.qty(l.quantity)) }
    var pu by remember { mutableStateOf(Money.format(l.unitPrice)) }
    var disc by remember { mutableStateOf(Money.qty(l.discountPct)) }
    var vat by remember { mutableStateOf(Money.qty(l.vatRate)) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(l.ref) },
        text = { Column {
            Field(stringResource(R.string.designation), des, { des = it }, singleLine = false)
            Row { Field(stringResource(R.string.quantity), qty, { qty = it }, Modifier.weight(1f).padding(end = 4.dp), number = true)
                  Field(stringResource(R.string.unit_price), pu, { pu = it }, Modifier.weight(1f), number = true) }
            Row { Field(stringResource(R.string.discount), disc, { disc = it }, Modifier.weight(1f).padding(end = 4.dp), number = true)
                  Field(stringResource(R.string.vat_rate), vat, { vat = it }, Modifier.weight(1f), number = true) }
        } },
        confirmButton = { TextButton({
            val q = Money.parseQty(qty) ?: return@TextButton; val p = Money.parse(pu) ?: return@TextButton
            onDone(l.copy(designation = des, quantity = q, unitPrice = p,
                discountPct = (Money.parseQty(disc) ?: 0.0).coerceIn(0.0, 100.0), vatRate = Money.parseQty(vat) ?: l.vatRate))
        }) { Text(stringResource(R.string.save)) } },
        dismissButton = { Row {
            TextButton({ onDone(null) }) { Text(stringResource(R.string.delete), color = NegativeRed) }
            TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } } })
}

@Composable
private fun ClientPicker(onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    var q by remember { mutableStateOf("") }
    val list by App.instance.db.clients().search(q).collectAsState(emptyList())
    var quick by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.choose_client)) },
        text = { Column {
            Field(stringResource(R.string.search), q, { q = it })
            TextButton({ quick = true }) { Icon(Icons.Default.PersonAdd, null); Text(" " + stringResource(R.string.quick_client)) }
            LazyColumn(Modifier.heightIn(max = 320.dp)) { items(list, key = { it.id }) { c ->
                ListItem(headlineContent = { Text(clientName(c.name, c.isCounter)) }, supportingContent = { Text(c.phone) },
                    modifier = Modifier.clickable { onPick(c.id) }) } }
        } },
        confirmButton = {}, dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } })
    if (quick) ClientDialog(Client(name = q), quick = true, onDismiss = { quick = false }) { id -> quick = false; onPick(id) }
}

@Composable
private fun ArticlePicker(onDismiss: () -> Unit, onPick: (Article) -> Unit) {
    var q by remember { mutableStateOf("") }
    val list by App.instance.db.articles().search(q, false).collectAsState(emptyList())
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.choose_article)) },
        text = { Column {
            Field(stringResource(R.string.search), q, { q = it })
            LazyColumn(Modifier.heightIn(max = 360.dp)) { items(list, key = { it.id }) { a ->
                ListItem(headlineContent = { Text(a.designation) }, supportingContent = { Text("${a.ref} · ${Money.dh(a.salePrice)}") },
                    trailingContent = { StockBadge(a.stock, a.unit) }, modifier = Modifier.clickable { onPick(a) }) } }
        } },
        confirmButton = {}, dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } })
}
