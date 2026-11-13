package ma.gestion.stock.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import ma.gestion.stock.domain.Money
import ma.gestion.stock.ui.*
import java.text.SimpleDateFormat
import java.util.*

val dateFmt = SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE)

@Composable
fun HomeScreen(nav: NavController) {
    val db = App.instance.db
    val neg by db.articles().negativeCount().collectAsState(0)
    val due by db.clients().totalDue().collectAsState(0L)
    val drafts by db.documents().draftCount().collectAsState(0)
    val company by db.misc().company().collectAsState(null)
    var newDoc by remember { mutableStateOf(false) }
    Column {
        TopBar(company?.name?.ifBlank { null } ?: stringResource(R.string.app_name))
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(stringResource(R.string.dashboard_negative), neg.toString(), neg > 0) {
                ArticleFilter.negativeOnly = true; nav.navigate("articles") { launchSingleTop = true }
            }
            StatCard(stringResource(R.string.dashboard_due), Money.dh(due), false) { nav.navigate("clients") }
            StatCard(stringResource(R.string.dashboard_drafts), drafts.toString(), false) { nav.navigate("documents") }
            Button({ newDoc = true }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.new_document)) }
            OutlinedButton({ nav.navigate("company") }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Business, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.company)) }
        }
    }
    if (newDoc) NewDocumentDialog(nav, null) { newDoc = false }
}

@Composable
private fun StatCard(label: String, value: String, alert: Boolean, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge,
                color = if (alert) NegativeRed else MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun ClientsScreen(nav: NavController) {
    var q by remember { mutableStateOf("") }
    val list by App.instance.db.clients().search(q).collectAsState(emptyList())
    var edit by remember { mutableStateOf<Client?>(null) }
    Scaffold(topBar = { TopBar(stringResource(R.string.nav_clients)) },
        floatingActionButton = { FloatingActionButton({ edit = Client(name = "") }) { Icon(Icons.Default.PersonAdd, stringResource(R.string.new_client)) } }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 12.dp)) {
            Field(stringResource(R.string.search), q, { q = it })
            if (list.isEmpty()) EmptyState()
            LazyColumn {
                items(list, key = { it.id }) { c ->
                    ListItem(modifier = Modifier.clickable { nav.navigate("client/${c.id}") },
                        headlineContent = { Text(clientName(c.name, c.isCounter)) },
                        supportingContent = { Text(listOf(c.phone, c.city).filter { it.isNotBlank() }.joinToString(" · ")) },
                        leadingContent = { Icon(if (c.isCounter) Icons.Default.Storefront else Icons.Default.Person, null) })
                    HorizontalDivider()
                }
            }
        }
    }
    edit?.let { ClientDialog(it, onDismiss = { edit = null }) { edit = null } }
}

/** Création / modification d'une fiche client. onSaved reçoit l'id. */
@Composable
fun ClientDialog(initial: Client, quick: Boolean = false, onDismiss: () -> Unit, onSaved: (Long) -> Unit) {
    var c by remember { mutableStateOf(initial) }
    var err by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(if (quick) R.string.quick_client else if (initial.id == 0L) R.string.new_client else R.string.edit)) },
        text = {
            Column {
                Field(stringResource(R.string.name), c.name, { c = c.copy(name = it); err = false }, isError = err)
                Field(stringResource(R.string.phone), c.phone, { c = c.copy(phone = it) })
                if (!quick) {
                    Field(stringResource(R.string.address), c.address, { c = c.copy(address = it) })
                    Field(stringResource(R.string.city), c.city, { c = c.copy(city = it) })
                    Field(stringResource(R.string.ice), c.ice, { c = c.copy(ice = it) })
                }
            }
        },
        confirmButton = { TextButton({
            if (c.name.isBlank()) { err = true; return@TextButton }
            scope.launch { val id = App.instance.db.clients().upsert(c.copy(name = c.name.trim())); onSaved(if (c.id != 0L) c.id else id) }
        }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
fun ClientDetailScreen(nav: NavController, id: Long) {
    val db = App.instance.db
    val client by db.clients().observe(id).collectAsState(null)
    val bal by db.clients().balance(id).collectAsState(ClientBalance(id, 0, 0, 0))
    val docs by db.documents().forClient(id).collectAsState(emptyList())
    val pays by db.payments().forClient(id).collectAsState(emptyList())
    var edit by remember { mutableStateOf(false) }
    var pay by remember { mutableStateOf(false) }
    val c = client ?: return
    Column {
        TopBar(clientName(c.name, c.isCounter), nav) { IconButton({ edit = true }) { Icon(Icons.Default.Edit, stringResource(R.string.edit)) } }
        LazyColumn(Modifier.padding(12.dp)) {
            item {
                Text(listOf(c.phone, c.address, c.city, c.ice.ifBlank { null }?.let { "ICE $it" } ?: "").filter { it.isNotBlank() }.joinToString("\n"))
                Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Row(Modifier.padding(12.dp)) {
                        Text(stringResource(R.string.balance_due), Modifier.weight(1f))
                        Text(Money.dh(bal.due), fontWeight = FontWeight.Bold, color = if (bal.due > 0) NegativeRed else MaterialTheme.colorScheme.primary)
                    }
                }
                Row {
                    Button({ pay = true }) { Text(stringResource(R.string.add_payment)) }
                }
                Text(stringResource(R.string.history), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            }
            items(docs, key = { "d" + it.doc.id }) { d ->
                ListItem(modifier = Modifier.clickable { nav.navigate("doc/${d.doc.id}") },
                    headlineContent = { Text("${docTypeLabel(d.doc.type)} ${d.doc.number ?: ""}") },
                    supportingContent = { Text("${dateFmt.format(Date(d.doc.date))} · ${statusLabel(d.doc.status)}") },
                    trailingContent = { Text(Money.dh(d.doc.totalTtc)) })
            }
            item { Text(stringResource(R.string.payments), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp)) }
            items(pays, key = { "p" + it.id }) { p ->
                ListItem(headlineContent = { Text(Money.dh(p.amount)) },
                    supportingContent = { Text("${dateFmt.format(Date(p.date))} · ${methodLabel(p.method)} ${p.note}") })
            }
        }
    }
    if (edit) ClientDialog(c, onDismiss = { edit = false }) { edit = false }
    if (pay) PaymentDialog(c.id, null, { pay = false })
}

@Composable
fun methodLabel(m: String) = when (m) {
    "CHEQUE" -> stringResource(R.string.pay_cheque); "TRANSFER" -> stringResource(R.string.pay_transfer)
    "LCN" -> stringResource(R.string.pay_lcn); "DEPOSIT" -> stringResource(R.string.deposit)
    else -> stringResource(R.string.pay_cash)
}

@Composable
fun PaymentDialog(clientId: Long, docId: Long?, onDismiss: () -> Unit) {
    var amount by remember { mutableStateOf("") }
    var method by remember { mutableStateOf("CASH") }
    var note by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.add_payment)) },
        text = {
            Column {
                Field(stringResource(R.string.amount), amount, { amount = it }, number = true, isError = amount.isNotEmpty() && Money.parse(amount) == null)
                Row { listOf("CASH", "CHEQUE", "TRANSFER", "LCN").forEach { m ->
                    FilterChip(method == m, { method = m }, { Text(methodLabel(m)) }, Modifier.padding(end = 4.dp)) } }
                Field(stringResource(R.string.reason), note, { note = it })
            }
        },
        confirmButton = { TextButton({
            val a = Money.parse(amount) ?: return@TextButton
            scope.launch { App.instance.db.payments().insert(Payment(clientId = clientId, documentId = docId, amount = a, method = method, note = note)); onDismiss() }
        }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } })
}
