@file:OptIn(ExperimentalMaterial3Api::class)

package app.banikhoj

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Home : Screen
    data class Shabad(val lineId: String) : Screen
    data class Bani(val id: String, val title: String) : Screen
}

/** Width (dp) at which the drawer becomes a permanent sidebar. */
private const val EXPANDED_WIDTH_DP = 840
private const val PREFS_READER = "reader_prefs"
private const val KEY_FONT_SCALE = "font_scale"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
                null -> BootGreeting()
                false -> Message("Could not open the Gurbani database.", isError = true)
                true -> Root()
            }
        }
    }
}

@Composable
fun BootGreeting() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("ੴ", fontSize = 46.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(14.dp))
        Text(
            "ਸਤਿ ਸ੍ਰੀ ਅਕਾਲ ਜੀ",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Loading Gurbani…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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

// ---------- Navigation shell with sidebar ----------

@Composable
fun Root() {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var showAbout by remember { mutableStateOf(false) }
    val banis by produceState<List<Bani>>(emptyList()) {
        // The Shabad OS master DB lists some banis multiple times (same name,
        // different ids/sources — e.g. two Rehraas Sahib rows). Merge duplicates,
        // keeping first appearance and preferring the entry with English.
        value = withContext(Dispatchers.IO) {
            val seen = LinkedHashMap<String, Bani>()
            for (b in GurbaniDb.banis()) {
                val key = b.nameGuru.ifBlank { b.nameLatin }.trim().lowercase()
                val existing = seen[key]
                if (existing == null || (!existing.hasEnglish && b.hasEnglish)) seen[key] = b
            }
            seen.values.toList()
        }
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val compact = LocalConfiguration.current.screenWidthDp < EXPANDED_WIDTH_DP
    val drawerOpen = drawerState.isOpen

    val navigate: (Screen) -> Unit = { target ->
        screen = target
        scope.launch { drawerState.close() }
    }

    BackHandler(enabled = screen != Screen.Home && !drawerOpen) { screen = Screen.Home }

    val drawerContent: @Composable () -> Unit = {
        AppDrawer(current = screen, banis = banis, onNavigate = navigate, onAbout = { showAbout = true })
    }

    if (compact) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = { ModalDrawerSheet { drawerContent() } }
        ) {
            NavBody(screen, banis, showMenu = true, onMenu = { scope.launch { drawerState.open() } },
                backBlockedByDrawer = drawerOpen, onNavigate = navigate)
        }
    } else {
        PermanentNavigationDrawer(
            drawerContent = { PermanentDrawerSheet { drawerContent() } }
        ) {
            NavBody(screen, banis, showMenu = false, onMenu = {}, backBlockedByDrawer = false, onNavigate = navigate)
        }
    }

    if (showAbout) AboutDialog(onDismiss = { showAbout = false })
}

@Composable
private fun NavBody(
    screen: Screen,
    banis: List<Bani>,
    showMenu: Boolean,
    onMenu: () -> Unit,
    backBlockedByDrawer: Boolean,
    onNavigate: (Screen) -> Unit,
) {
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
            is Screen.Home ->
                HomeScreen(showMenu, onMenu, backBlockedByDrawer, banis, onOpen = onNavigate)
            is Screen.Shabad ->
                ReaderScreen("Shabad", s.lineId, onBack = { onNavigate(Screen.Home) }) { GurbaniDb.shabadOf(it) }
            is Screen.Bani ->
                ReaderScreen(s.title, s.id, onBack = { onNavigate(Screen.Home) }) { GurbaniDb.baniLines(it) }
        }
    }
}

// Curated nitnem order for the drawer — kept distinct from the full home grid.
private val DAILY_BANI_KEYS = listOf(
    "japji", "jaap", "savaiye", "chaupai", "rehraas", "rehras", "sohila", "ardaas", "ardas"
)

/** First bani matching each key, in nitnem order, de-duplicated by id. */
private fun dailyBanis(banis: List<Bani>): List<Bani> {
    val out = ArrayList<Bani>()
    for (key in DAILY_BANI_KEYS) {
        banis.firstOrNull { b ->
            (b.nameGuru + " " + b.nameLatin).lowercase().contains(key)
        }?.let { picked -> if (out.none { it.id == picked.id }) out.add(picked) }
    }
    return out
}

@Composable
private fun AppDrawer(
    current: Screen,
    banis: List<Bani>,
    onNavigate: (Screen) -> Unit,
    onAbout: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        DrawerHeader()

        NavigationDrawerItem(
            icon = { Icon(Icons.Filled.Search, contentDescription = null) },
            label = { Text("ਖੋਜ · Search") },
            selected = current is Screen.Home,
            onClick = { onNavigate(Screen.Home) },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )

        Spacer(Modifier.height(10.dp))
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        Text(
            "ਨਿੱਤ ਨੇਮ · Daily",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp)
        )
        Column(Modifier.padding(horizontal = 12.dp)) {
            dailyBanis(banis).forEach { b ->
                val title = b.nameGuru.ifBlank { b.nameLatin }
                NavigationDrawerItem(
                    label = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    selected = current is Screen.Bani && current.id == b.id,
                    onClick = { onNavigate(Screen.Bani(b.id, title)) },
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }

        Spacer(Modifier.weight(1f))
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        NavigationDrawerItem(
            icon = { Icon(Icons.Filled.Info, contentDescription = null) },
            label = { Text("ਐਪ ਬਾਰੇ · About") },
            selected = false,
            onClick = onAbout,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun DrawerHeader() {
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.tertiary
                    )
                )
            )
    ) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 26.dp)) {
            Text("ੴ", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.height(10.dp))
            Text(
                "ਬਾਣੀ ਖੋਜ",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Text(
                "Gurbani Search & Reader",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
            )
        }
    }
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val version = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }
            .getOrNull() ?: "–"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        title = { Text("ਬਾਣੀ ਖੋਜ") },
        text = {
            Text(
                "Gurbani search & reader.\nVersion $version\n\n" +
                    "Use + and − buttons to zoom the Gurbani text.\n" +
                    "ਵਾਹਿਗੁਰੂ ਜੀ ਕਾ ਖ਼ਾਲਸਾ, ਵਾਹਿਗੁਰੂ ਜੀ ਦੀ ਫ਼ਤਿਹ।"
            )
        }
    )
}

// ---------- Home ----------

@Composable
fun HomeScreen(
    showMenu: Boolean,
    onMenu: () -> Unit,
    drawerOpen: Boolean,
    banis: List<Bani>?,
    onOpen: (Screen) -> Unit,
) {
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

    BackHandler(enabled = !drawerOpen && keyboardOn && query.isNotEmpty()) { query = "" }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("ਬਾਣੀ ਖੋਜ", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    if (showMenu) {
                        IconButton(onClick = onMenu) {
                            Icon(Icons.Filled.Menu, contentDescription = "Menu")
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent
                )
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
                    BanisGrid(Modifier.weight(1f), banis, onOpen = onOpen)
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
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        "ਗੁਰਮੁਖੀ ਵਿੱਚ ਖੋਜੋ…",
                        fontSize = 17.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(
                        fontSize = 17.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { state ->
                            // Tapping the field opens the system keyboard;
                            // hide the built-in panel to avoid two keyboards.
                            if (state.isFocused && keyboardOn) onToggleKeyboard()
                        }
                )
            }
            if (query.isNotEmpty()) {
                Icon(
                    Icons.Filled.Close, contentDescription = "Clear",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable(onClick = onClear).size(20.dp)
                )
            }
            IconButton(onClick = onToggleKeyboard, modifier = Modifier.size(32.dp)) {
                Icon(
                    if (keyboardOn) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                    contentDescription = if (keyboardOn) "Hide keyboard" else "Show keyboard",
                    tint = if (keyboardOn) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun BanisGrid(modifier: Modifier = Modifier, banis: List<Bani>?, onOpen: (Screen) -> Unit) {
    Column(modifier) {
        Text(
            "ਸਤਿ ਸ੍ਰੀ ਅਕਾਲ ਜੀ",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 170.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(banis.orEmpty(), key = { it.id }) { b ->
                BaniCard(b, onOpen)
            }
        }
    }
}

@Composable
private fun BaniCard(b: Bani, onOpen: (Screen) -> Unit) {
    val title = b.nameGuru.ifBlank { b.nameLatin }
    Card(
        onClick = { onOpen(Screen.Bani(b.id, title)) },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(46.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        title.trim().firstOrNull()?.toString() ?: "ੴ",
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Column {
                Text(
                    title,
                    fontSize = 17.sp,
                    lineHeight = 23.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
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
        if (!searching && results.isEmpty()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Filled.Search, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(44.dp)
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "ਕੋਈ ਨਤੀਜਾ ਨਹੀਂ ਮਿਲਿਆ",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Try a different spelling",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 8.dp)
            ) {
                listItems(results, key = { it.lineId }) { r ->
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = {
                            GurmukhiText(r.gurmukhi, fontSize = 19.sp, lineHeight = 31.sp)
                        },
                        supportingContent = r.english.takeIf { it.isNotBlank() }?.let {
                            { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        },
                        modifier = Modifier
                            .clickable { onOpen(Screen.Shabad(r.lineId)) }
                    )
                }
            }
        }
    }
}

// ---------- Punctuation-aware Gurmukhi text ----------

/** Traditional dandas and Shabad OS visraam variation selectors. */
private fun isTraditionalMark(c: Char): Boolean =
    c == '।' || c == '॥' || c == '\uFE00' || c == '\uFE01' || c == '\uFE02' || c == '\uFE03'

/** Western punctuation — hidden from display. */
private fun isWesternMark(c: Char): Boolean =
    c in ";.,:"

/** Footnote subscript digits — displayed as-is. */
private fun isSubscript(c: Char): Boolean =
    c in "₀₁₂₃₄₅₆₇₈₉"

private fun isAnyMark(c: Char): Boolean =
    isTraditionalMark(c) || isWesternMark(c) || isSubscript(c)

/**
 * Rendering rules:
 *  - traditional dandas, visraam selectors and subscript digits render exactly
 *    as written, with no font or colour change to the word before them;
 *  - western marks (; , :) are hidden from display, and the word immediately
 *    before them takes the accent colour.
 */
@Composable
fun GurmukhiText(
    text: String,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    val accent = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
    val annotated = remember(text, accent) {
        val n = text.length
        val accentWord = BooleanArray(n)

        var i = 0
        while (i < n) {
            if (!isAnyMark(text[i])) {
                i++
                continue
            }
            var j = i + 1
            while (j < n && isAnyMark(text[j])) j++
            val runHasHidden = (i until j).any { isWesternMark(text[it]) }

            // Walk back from the run through its word (skipping one gap if needed).
            if (runHasHidden) {
                var s = j - 1
                var sawWord = false
                while (s >= 0) {
                    val ch = text[s]
                    if (accentWord[s]) break          // already claimed by an earlier mark
                    if (ch == ' ') { if (sawWord) break; s--; continue }
                    if (isAnyMark(ch)) { s--; continue } // the mark run itself
                    s--
                    sawWord = true
                }
                if (sawWord) {
                    for (t in (s + 1) until i) accentWord[t] = true
                }
            }
            i = j
        }

        buildAnnotatedString {
            for (idx in text.indices) {
                val c = text[idx]
                when {
                    isWesternMark(c) -> { /* glyph hidden */ }
                    accentWord[idx] -> withStyle(SpanStyle(color = accent)) { append(c) }
                    else -> append(c) // traditional marks & subscripts render untouched
                }
            }
        }
    }
    Text(
        annotated,
        modifier = modifier,
        fontSize = fontSize,
        lineHeight = lineHeight,
        fontWeight = fontWeight,
        maxLines = maxLines
    )
}

// ---------- Reader ----------

private const val MIN_FONT_SCALE = 0.55f
private const val MAX_FONT_SCALE = 2.4f
private const val DOUBLE_TAP_SCALE = 1.5f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(title: String, dbKey: String, onBack: () -> Unit, loader: (String) -> List<Line>) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(PREFS_READER, Context.MODE_PRIVATE) }

    val lines by produceState<List<Line>>(emptyList(), dbKey) {
        value = withContext(Dispatchers.IO) { loader(dbKey) }
    }

    // Translation languages actually available for this content; cycle off → EN → PA.
    val availableLangs = remember(lines) {
        ReaderLang.entries.filter { lang -> lines.any { it.translation(lang).isNotBlank() } }
    }
    var langSel by rememberSaveable(dbKey) { mutableIntStateOf(-1) }
    val currentLang = if (langSel in availableLangs.indices) availableLangs[langSel] else null
    fun cycleLang() {
        if (availableLangs.isEmpty()) return
        langSel = if (langSel + 1 >= availableLangs.size) -1 else langSel + 1
    }
    LaunchedEffect(availableLangs) {
        if (langSel == -1 && availableLangs.isNotEmpty() && dbKey.isNotBlank()) langSel = 0
    }

    // Dedicated zoom buttons: simple multiplicative steps, persisted instantly.
    var fontScale by rememberSaveable {
        mutableFloatStateOf(prefs.getFloat(KEY_FONT_SCALE, 1f).coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE))
    }
    val stepZoom: (Float) -> Unit = { factor ->
        val next = (fontScale * factor).coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
        if (next != fontScale) {
            fontScale = next
            prefs.edit().putFloat(KEY_FONT_SCALE, next).apply()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(title, fontSize = 21.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { stepZoom(1f / 1.15f) }) {
                        Text("A−", fontSize = 17.sp, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { stepZoom(1.15f) }) {
                        Text("A+", fontSize = 17.sp, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { cycleLang() }) {
                        Text(
                            currentLang?.label ?: "ਗੁ",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (currentLang != null) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(lines.size) { i ->
                    val line = lines[i]
                    val showSection = line.section.isNotBlank() &&
                        (i == 0 || lines[i - 1].section != line.section)
                    Column {
                        if (showSection) {
                            Text(
                                line.section,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }
                        GurmukhiText(
                            line.gurmukhi,
                            fontSize = (23 * fontScale).sp,
                            lineHeight = (40 * fontScale).sp,
                        )
                        val tr = currentLang?.let { line.translation(it) }.orEmpty()
                        if (tr.isNotBlank()) {
                            Spacer(Modifier.height((5 * fontScale).dp))
                            Row {
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    tr,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontSize = (15 * fontScale).sp,
                                    lineHeight = (22 * fontScale).sp,
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
