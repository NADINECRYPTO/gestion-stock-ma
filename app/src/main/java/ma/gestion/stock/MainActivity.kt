package ma.gestion.stock

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import ma.gestion.stock.ui.screens.*

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppTheme { AppRoot() } }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val scheme = lightColorScheme(primary = Color(0xFF0B5D3B), secondary = Color(0xFFB8860B), error = Color(0xFFC62828))
    MaterialTheme(colorScheme = scheme, content = content)
}

private data class Tab(val route: String, val label: Int, val icon: ImageVector)

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val tabs = listOf(
        Tab("home", R.string.nav_home, Icons.Default.Home),
        Tab("clients", R.string.nav_clients, Icons.Default.People),
        Tab("articles", R.string.nav_articles, Icons.Default.Inventory2),
        Tab("documents", R.string.nav_documents, Icons.Default.Description),
        Tab("settings", R.string.nav_settings, Icons.Default.Settings),
    )
    val current by nav.currentBackStackEntryAsState()
    val route = current?.destination?.route
    Scaffold(bottomBar = {
        if (tabs.any { it.route == route }) NavigationBar {
            tabs.forEach { t ->
                NavigationBarItem(selected = route == t.route, onClick = {
                    nav.navigate(t.route) { popUpTo("home") { saveState = true }; launchSingleTop = true; restoreState = true }
                }, icon = { Icon(t.icon, null) }, label = { Text(stringResource(t.label), maxLines = 1) })
            }
        }
    }) { pad ->
        NavHost(nav, "home", Modifier.padding(pad)) {
            composable("home") { HomeScreen(nav) }
            composable("clients") { ClientsScreen(nav) }
            composable("client/{id}", listOf(navArgument("id") { type = NavType.LongType })) { ClientDetailScreen(nav, it.arguments!!.getLong("id")) }
            composable("articles") { ArticlesScreen(nav) }
            composable("article/{id}", listOf(navArgument("id") { type = NavType.LongType })) { ArticleScreen(nav, it.arguments!!.getLong("id")) }
            composable("documents") { DocumentsScreen(nav) }
            composable("doc/{id}", listOf(navArgument("id") { type = NavType.LongType })) { DocumentScreen(nav, it.arguments!!.getLong("id")) }
            composable("pdf/{id}?lang={lang}", listOf(
                navArgument("id") { type = NavType.LongType },
                navArgument("lang") { type = NavType.StringType; nullable = true; defaultValue = null },
            )) { PdfScreen(nav, it.arguments!!.getLong("id"), it.arguments!!.getString("lang")) }
            composable("company") { CompanyScreen(nav) }
            composable("settings") { SettingsScreen(nav) }
        }
    }
}
