package ma.gestion.stock.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import ma.gestion.stock.R
import ma.gestion.stock.data.DocStatus
import ma.gestion.stock.data.DocType

val NegativeRed = Color(0xFFC62828)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopBar(title: String, nav: NavController? = null, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1) },
        navigationIcon = { if (nav != null) IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary,
            titleContentColor = Color.White, navigationIconContentColor = Color.White, actionIconContentColor = Color.White),
    )
}

@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier.fillMaxWidth(),
          number: Boolean = false, isError: Boolean = false, singleLine: Boolean = true) {
    OutlinedTextField(value, onChange, modifier.padding(vertical = 3.dp), label = { Text(label) }, singleLine = singleLine,
        isError = isError,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default)
}

@Composable
fun docTypeLabel(t: DocType) = stringResource(when (t) {
    DocType.DEVIS -> R.string.doc_devis; DocType.BL -> R.string.doc_bl
    DocType.FACTURE -> R.string.doc_facture; DocType.AVOIR -> R.string.doc_avoir
})

@Composable
fun statusLabel(s: DocStatus) = stringResource(if (s == DocStatus.DRAFT) R.string.draft else R.string.validated)

@Composable
fun clientName(name: String, isCounter: Boolean) = if (isCounter) stringResource(R.string.counter_client) else name

@Composable
fun EmptyState() { Text(stringResource(R.string.empty_list), Modifier.padding(24.dp), color = Color.Gray) }
