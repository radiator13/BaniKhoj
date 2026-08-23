@file:OptIn(ExperimentalMaterial3Api::class)

package app.banikhoj

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
        value = withContext(Dispatchers.IO) { GurbaniDb.open(ctx) }
    }

    val scheme =
        if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    MaterialTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (ready) {
                null -> Message("Preparing database…")
                false -> Message("Could not open the Gurbani database.", isError = true)
                true -> Navigator()
            }
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
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun Navigator() {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }

    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            val dir = if (targetState is Screen.Home) -1 else 1
            (slideInHorizontally { it / 4 * dir } + fadeIn()) togetherWith
                (slideOutHorizontally { it / 4 * -dir } + fadeOut())
        },
        label = "nav"
    ) { s ->
        when (s) {
            is Screen.Home -> HomeScreen(onOpen = { screen = it })
            is Screen.Shabad ->
                ReaderScreen("Shabad", s.lineId) { GurbaniDb.shabadOf(it) }
            is Screen.Bani ->
                ReaderScreen(s.title, s.id) { GurbaniDb.baniLines(it) }
        }
    }
}

@Composable
fun HomeScreen(onOpen: (Screen) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var keyboardOn by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = emptyList()
            searching = false
        } else {
            searching = true
            delay(220)
            results = withContext(Dispatchers.IO) { GurbaniDb.search(query.trim()) }
            searching = false
        }
    }

    BackHandler(enabled = keyboardOn && query.isNotEmpty()) { query = "" }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("ਬਾਣੀ ਖੋਜ", fontWeight = FontWeight.Bold) }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {

            SearchField(
                query = query,
                keyboardOn = keyboardOn,
                onToggleKeyboard = { keyboardOn = !keyboardOn },
                onQueryChange = { query = it },
                onClear = { query = "" }
            )

            AnimatedContent(
                targetState = query.isBlank(),
                label = "homeBody",
                transitionSpec = { fadeIn() togetherWith fadeOut() }
            ) { idle ->
                if (idle) {
                    BanisGrid(Modifier.weight(1f), onOpen = onOpen)
                } else {
                    ResultsList(
                        results, searching,
                        Modifier.weight(1f),
                        onOpen = onOpen
                    )
                }
            }

            AnimatedVisibility(keyboardOn) {
                GurmukhiKeyboard(
                    onKey = { query += it },
                    onBackspace = {
                        query = query.dropLast(1)
                        if (query.isEmpty()) results = emptyList()
                    }
                )
            }
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    keyboardOn: Boolean,
    onToggleKeyboard: () -> Unit,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable { if (!keyboardOn) onToggleKeyboard() }
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Filled.Search, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                query.ifEmpty { "ਗੁਰਮੁਖੀ ਵਿੱਚ ਖੋਜੋ…" },
                fontSize = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (query.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (query.isNotEmpty()) {
                Icon(
                    Icons.Filled.Close, contentDescription = "Clear",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable(onClick = onClear).size(20.dp)
                )
            }
            IconButton(onClick = onToggleKeyboard, modifier = Modifier.size(30.dp)) {
                Text(
                    "\u2328",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (keyboardOn) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun BanisGrid(modifier: Modifier = Modifier, onOpen: (Screen) -> Unit) {
    val banis by produceState<List<Bani>>(emptyList()) {
        value = withContext(Dispatchers.IO) { GurbaniDb.banis() }
    }
    Column(modifier) {
        Text(
            "NITNEM & BANIS",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 20.dp, top = 6.dp, bottom = 2.dp)
        )
        Text(
            "ਸਤਿ ਸ੍ਰੀ ਅਕਾਲ ਜੀ",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(banis, key = { it.id }) { b ->
                Card(
                    onClick = { onOpen(Screen.Bani(b.id, b.nameGuru.ifBlank { b.nameLatin })) },
                    modifier = Modifier.animateItem()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            b.nameGuru.ifBlank { b.nameLatin },
                            fontSize = 19.sp,
                            lineHeight = 28.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            b.nameLatin,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ResultsList(
    results: List<SearchResult>,
    searching: Boolean,
    modifier: Modifier = Modifier,
    onOpen: (Screen) -> Unit,
) {
    Column(modifier) {
        Text(
            when {
                searching -> "Searching…"
                results.isEmpty() -> "No results"
                else -> "${results.size} result${if (results.size == 1) "" else "s"}"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
        )
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 8.dp)
        ) {
            listItems(results, key = { it.lineId }) { r ->
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = {
                        Text(r.gurmukhi, fontSize = 19.sp, lineHeight = 31.sp)
                    },
                    supportingContent = r.english.takeIf { it.isNotBlank() }?.let {
                        { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    },
                    modifier = Modifier
                        .clickable { onOpen(Screen.Shabad(r.lineId)) }
                )
                HorizontalDivider(
                    Modifier.padding(horizontal = 20.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(title: String, dbKey: String, loader: (String) -> List<Line>) {
    val lines by produceState<List<Line>>(emptyList(), dbKey) {
        value = withContext(Dispatchers.IO) { loader(dbKey) }
    }
    var showTranslation by rememberSaveable { mutableStateOf(true) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(title, fontSize = 21.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                actions = {
                    IconButton(onClick = { showTranslation = !showTranslation }) {
                        Text(
                            if (showTranslation) "EN" else "ਗੁ",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(lines.size) { i ->
                    val l = lines[i]
                    Column {
                        Text(l.gurmukhi, fontSize = 22.sp, lineHeight = 38.sp)
                        if (showTranslation && l.english.isNotBlank()) {
                            Spacer(Modifier.height(5.dp))
                            Row {
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    l.english,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
