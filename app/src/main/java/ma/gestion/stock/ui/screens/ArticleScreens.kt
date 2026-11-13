package ma.gestion.stock.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import ma.gestion.stock.App
import ma.gestion.stock.R
import ma.gestion.stock.data.*
import ma.gestion.stock.domain.Money
import ma.gestion.stock.ui.*
import java.util.Date

object ArticleFilter { var negativeOnly by mutableStateOf(false) }

@Composable
fun StockBadge(stock: Double, unit: String) {
    val neg = stock < 0
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (neg) Icon(Icons.Default.Warning, null, tint = NegativeRed, modifier = Modifier.size(16.dp))
        Text("${Money.qty(stock)} $unit", color = if (neg) NegativeRed else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ArticlesScreen(nav: NavController) {
    var q by remember { mutableStateOf("") }
    val neg = ArticleFilter.negativeOnly
    val list by App.instance.db.articles().search(q, neg).collectAsState(emptyList())
    Scaffold(topBar = { TopBar(stringResource(R.string.nav_articles)) },
        floatingActionButton = { FloatingActionButton({ nav.navigate("article/0") }) { Icon(Icons.Default.Add, stringResource(R.string.new_article)) } }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 12.dp)) {
            Field(stringResource(R.string.search), q, { q = it })
            FilterChip(neg, { ArticleFilter.negativeOnly = !neg }, { Text(stringResource(R.string.negative_only)) },
                leadingIcon = { Icon(Icons.Default.Warning, null, tint = NegativeRed) })
            if (list.isEmpty()) EmptyState()
            LazyColumn {
                items(list, key = { it.id }) { a ->
                    ListItem(modifier = Modifier.clickable { nav.navigate("article/${a.id}") },
                        headlineContent = { Text(a.designation) },
                        supportingContent = { Text("${a.ref} · ${Money.dh(a.salePrice)} HT · TVA ${Money.qty(a.vatRate)}%") },
                        trailingContent = { StockBadge(a.stock, a.unit) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun ArticleScreen(nav: NavController, id: Long) {
    val app = App.instance
    val existing by (if (id == 0L) flowOf(null) else app.db.articles().observe(id)).collectAsState(null)
    val moves by (if (id == 0L) flowOf(emptyList()) else app.db.moves().forArticle(id)).collectAsState(emptyList())
    val company by app.db.misc().company().collectAsState(null)
    val rates = company?.vatRates?.split(';')?.mapNotNull { it.trim().toDoubleOrNull() } ?: listOf(0.0, 7.0, 10.0, 14.0, 20.0)
    var loaded by remember { mutableStateOf(id == 0L) }
    var ref by remember { mutableStateOf("") }; var des by remember { mutableStateOf("") }
    var fam by remember { mutableStateOf("") }; var unit by remember { mutableStateOf("U") }
    var pa by remember { mutableStateOf("") }; var pv by remember { mutableStateOf("") }
    var vat by remember { mutableStateOf("20") }; var initQty by remember { mutableStateOf("0") }
    var error by remember { mutableStateOf<String?>(null) }
    var moveType by remember { mutableStateOf<MoveType?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(existing) {
        existing?.let { if (!loaded) {
            ref = it.ref; des = it.designation; fam = it.family; unit = it.unit
            pa = Money.format(it.purchasePrice); pv = Money.format(it.salePrice); vat = Money.qty(it.vatRate); loaded = true } }
    }
    val requiredMsg = stringResource(R.string.required)
    Column {
        TopBar(if (id == 0L) stringResource(R.string.new_article) else des, nav)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
            existing?.let { Row { Text(stringResource(R.string.stock) + " : "); StockBadge(it.stock, it.unit) } }
            Field(stringResource(R.string.reference), ref, { ref = it }, isError = error != null && ref.isBlank())
            Field(stringResource(R.string.designation), des, { des = it }, isError = error != null && des.isBlank())
            Field(stringResource(R.string.family), fam, { fam = it })
            Field(stringResource(R.string.unit), unit, { unit = it })
            Row { Field(stringResource(R.string.purchase_price), pa, { pa = it }, Modifier.weight(1f).padding(end = 4.dp), number = true)
                  Field(stringResource(R.string.sale_price), pv, { pv = it }, Modifier.weight(1f), number = true) }
            Text(stringResource(R.string.vat_rate))
            Row { rates.forEach { r -> FilterChip(vat.replace(',', '.').toDoubleOrNull() == r, { vat = Money.qty(r) }, { Text(Money.qty(r)) }, Modifier.padding(end = 4.dp)) } }
            if (id == 0L) Field(stringResource(R.string.initial_qty), initQty, { initQty = it }, number = true)
            error?.let { Text(it, color = NegativeRed) }
            Button({
                if (ref.isBlank() || des.isBlank()) { error = requiredMsg; return@Button }
                val a = Article(id = id, ref = ref.trim(), designation = des.trim(), family = fam.trim(), unit = unit.trim(),
                    purchasePrice = Money.parse(pa) ?: 0, salePrice = Money.parse(pv) ?: 0,
                    vatRate = Money.parseQty(vat) ?: 20.0, stock = existing?.stock ?: 0.0)
                scope.launch {
                    runCatching {
                        if (id == 0L) app.repo.createArticle(a, Money.parseQty(initQty) ?: 0.0) else app.db.articles().update(a)
                    }.onSuccess { nav.popBackStack() }.onFailure { error = it.message }
                }
            }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.save)) }

            if (id != 0L) {
                Text(stringResource(R.string.movements), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(MoveType.IN to R.string.mv_in, MoveType.OUT to R.string.mv_out, MoveType.RETURN to R.string.mv_return, MoveType.ADJUST to R.string.mv_adjust).forEach { (t, l) ->
                        AssistChip({ moveType = t }, { Text(stringResource(l), maxLines = 1) })
                    }
                }
                moves.forEach { m ->
                    ListItem(
                        headlineContent = { Text("${moveLabel(m.type)}  ${if (m.quantity > 0) "+" else ""}${Money.qty(m.quantity)}", color = if (m.quantity < 0) NegativeRed else MaterialTheme.colorScheme.primary) },
                        supportingContent = { Text("${dateFmt.format(Date(m.date))} · ${m.reason}\n${stringResource(R.string.before)} ${Money.qty(m.stockBefore)} → ${stringResource(R.string.after)} ${Money.qty(m.stockAfter)}") },
                        trailingContent = { m.documentId?.let { did -> TextButton({ nav.navigate("doc/$did") }) { Text(stringResource(R.string.linked_doc)) } } })
                    HorizontalDivider()
                }
            }
        }
    }
    moveType?.let { t -> existing?.let { a -> MoveDialog(a, t) { moveType = null } } }
}

@Composable
fun moveLabel(t: MoveType) = when (t) {
    MoveType.INITIAL -> stringResource(R.string.initial_qty); MoveType.IN -> stringResource(R.string.mv_in)
    MoveType.OUT -> stringResource(R.string.mv_out); MoveType.RETURN -> stringResource(R.string.mv_return)
    MoveType.ADJUST -> stringResource(R.string.mv_adjust)
}

@Composable
private fun MoveDialog(a: Article, type: MoveType, onDismiss: () -> Unit) {
    var qty by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var confirmNeg by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val repo = App.instance.repo
    val q = Money.parseQty(qty)
    val after = when (type) { MoveType.ADJUST -> q; MoveType.OUT -> q?.let { a.stock - it }; else -> q?.let { a.stock + it } }
    fun commit() = scope.launch {
        if (type == MoveType.ADJUST) repo.adjustTo(a.id, q!!, reason) else repo.manualMove(a.id, type, q!!, reason)
        onDismiss()
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(moveLabel(type)) },
        text = { Column {
            Field(stringResource(if (type == MoveType.ADJUST) R.string.new_stock_value else R.string.quantity), qty, { qty = it }, number = true)
            Field(stringResource(R.string.reason), reason, { reason = it })
            after?.let { Row { Text("${stringResource(R.string.after)} : "); StockBadge(it, a.unit) } }
        } },
        confirmButton = { TextButton({
            if (q == null || (type != MoveType.ADJUST && q <= 0)) return@TextButton
            if (after != null && after < 0 && !confirmNeg) confirmNeg = true else commit()
        }) { Text(stringResource(R.string.validate)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } })
    if (confirmNeg) AlertDialog(onDismissRequest = { confirmNeg = false },
        icon = { Icon(Icons.Default.Warning, null, tint = NegativeRed) },
        title = { Text(stringResource(R.string.neg_warning_title)) },
        text = { Text(stringResource(R.string.neg_warning, "${a.designation} (${Money.qty(a.stock)} → ${Money.qty(after ?: 0.0)})")) },
        confirmButton = { TextButton({ confirmNeg = false; commit() }) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton({ confirmNeg = false }) { Text(stringResource(R.string.cancel)) } })
}
