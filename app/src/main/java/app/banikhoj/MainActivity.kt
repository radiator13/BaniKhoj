@file:OptIn(ExperimentalMaterial3Api::class)

package app.banikhoj

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Home : Screen
    data class Shabad(val lineId: String) : Screen
    data class Bani(val id: String, val title: String) : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val ready by produceState<Boolean?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching { GurbaniDb.get(ctx) }.isSuccess
        }
    }

    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    MaterialTheme(colorScheme = scheme) {
        when (ready) {
            null -> Message("Preparing database…")
            false -> Message("Could not open the Gurbani database.", isError = true)
            true -> Navigator()
        }
    }
}

@Composable
fun Message(text: String, isError: Boolean = false) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = if (isError) MaterialTheme.colorScheme.error else Color.Gray
        )
    }
}

@Composable
fun Navigator() {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

    when (val s = screen) {
        is Screen.Home -> HomeScreen(onOpen = { screen = it })
        is Screen.Shabad ->
            ReaderScreen("Shabad", s.lineId) { GurbaniDb.shabadOf(it) }
        is Screen.Bani ->
            ReaderScreen(s.title, s.id) { GurbaniDb.baniLines(it) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpen: (Screen) -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }

    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = emptyList()
        } else {
            delay(250)
            results = withContext(Dispatchers.IO) { GurbaniDb.search(query.trim()) }
        }
    }

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("ਬਾਣੀ ਖੋਜ") }) }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search in Gurmukhi…") },
                singleLine = true
            )

            if (query.isNotBlank()) {
                Text(
                    "${results.size} result${if (results.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.Gray,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(results, key = { it.lineId }) { r ->
                        ListItem(
                            headlineContent = {
                                Text(r.gurmukhi, fontSize = 19.sp, lineHeight = 30.sp)
                            },
                            supportingContent = r.english.takeIf { it.isNotBlank() }?.let {
                                { Text(it, maxLines = 2, color = Color.Gray) }
                            },
                            modifier = Modifier
                                .padding(horizontal = 8.dp)
                                .clickable { onOpen(Screen.Shabad(r.lineId)) }
                        )
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    }
                }
            } else {
                BaniList(onOpen = onOpen)
            }
        }
    }
}

@Composable
fun BaniList(onOpen: (Screen) -> Unit) {
    val banis by produceState<List<Bani>>(emptyList()) {
        value = withContext(Dispatchers.IO) { GurbaniDb.banis() }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text(
                "NITNEM & BANIS",
                style = MaterialTheme.typography.labelLarge,
                color = Color.Gray,
                modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp)
            )
        }
        items(banis, key = { it.id }) { b ->
            Card(
                onClick = { onOpen(Screen.Bani(b.id, b.nameGuru.ifBlank { b.nameLatin })) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(b.nameGuru.ifBlank { b.nameLatin }, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Text(b.nameLatin, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
        }
    }
}

@Composable
fun ReaderScreen(title: String, dbKey: String, loader: (String) -> List<Line>) {
    val lines by produceState<List<Line>>(emptyList(), dbKey) {
        value = withContext(Dispatchers.IO) { loader(dbKey) }
    }
    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text(title, fontSize = 22.sp) }) }
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(lines.size) { i ->
                val l = lines[i]
                Column {
                    Text(l.gurmukhi, fontSize = 21.sp, lineHeight = 34.sp)
                    if (l.english.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Row {
                            Spacer(Modifier.width(10.dp))
                            Text(l.english, style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                        }
                    }
                }
            }
        }
    }
}
