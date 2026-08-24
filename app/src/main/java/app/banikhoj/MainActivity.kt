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
import androidx.compose.animation.core.animate
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlin.math.roundToInt

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
                null -> Message("Preparing database…")
                false -> Message("Could not open the Gurbani database.", isError = true)
                true -> Root()
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

// ---------- Navigation shell with sidebar ----------

@Composable
fun Root() {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var showAbout by remember { mutableStateOf(false) }
    val banis by produceState<List<Bani>>(emptyList()) {
        value = withContext(Dispatchers.IO) { GurbaniDb.banis() }
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

@Composable
private fun AppDrawer(
    current: Screen,
    banis: List<Bani>,
    onNavigate: (Screen) -> Unit,
    onAbout: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        DrawerHeader()

        // Single flat index — every bani lives here together, no source grouping.
        Text(
            "ਬਾਣੀਆਂ",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp)
        )
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp)) {
            listItems(banis, key = { it.id }) { b ->
                val title = b.nameGuru.ifBlank { b.nameLatin }
                NavigationDrawerItem(
                    label = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    selected = current is Screen.Bani && current.id == b.id,
                    onClick = { onNavigate(Screen.Bani(b.id, title)) },
                    modifier = Modifier.padding(vertical = 1.dp)
                )
            }
        }

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
                    "Pinch or double-tap to zoom the Gurbani text.\n" +
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
        Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
            Text(
                "ਸਤਿ ਸ੍ਰੀ ਅਕਾਲ ਜੀ",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "ਬਾਣੀ ਚੁਣੋ ਜਾਂ ਉੱਪਰ ਗੁਰਮੁਖੀ ਵਿੱਚ ਖੋਜੋ",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            "ਬਾਣੀਆਂ",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 170.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 6.dp),
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

/**
 * Marks whose *preceding word* is emphasised: traditional dandas and Shabad OS
 * visraam selectors, plus western punctuation (; , . :) and footnote digits.
 */
private fun isMarkChar(c: Char): Boolean =
    c == '।' || c == '॥' || c == '\uFE00' || c == '\uFE01' || c == '\uFE02' || c == '\uFE03' ||
        c in ";.,:" || c in "₀₁₂₃₄₅₆₇₈₉"

/**
 * Renders Gurmukhi with one uniform rule for every mark — dandas, visraam and
 * western punctuation alike: the mark itself stays plain; the word immediately
 * before it receives the accent colour.
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
    val accent = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
    val annotated = remember(text, accent) {
        val n = text.length
        val emphasized = BooleanArray(n)

        var i = 0
        while (i < n) {
            if (!isMarkChar(text[i])) {
                i++
                continue
            }
            var j = i + 1
            while (j < n && isMarkChar(text[j])) j++

            // Walk back from the mark through its word (skipping one gap if needed),
            // emphasising only the word — the punctuation itself stays plain.
            var s = j - 1
            var sawWord = false
            while (s >= 0) {
                val ch = text[s]
                if (emphasized[s]) break          // already claimed by an earlier mark
                if (ch == ' ') { if (sawWord) break; s--; continue }
                if (isMarkChar(ch)) { s--; continue } // the mark run itself
                s--
                sawWord = true
            }
            if (sawWord) {
                for (t in (s + 1) until i) emphasized[t] = true
            }
            i = j
        }

        buildAnnotatedString {
            var p = 0
            while (p < n) {
                val cur = emphasized[p]
                var q = p + 1
                while (q < n && emphasized[q] == cur) q++
                if (cur) withStyle(SpanStyle(color = accent)) { append(text, p, q) }
                else append(text, p, q)
                p = q
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

    // Zoomable Gurbani: pinch anywhere, double-tap to toggle, level persists.
    var fontScale by rememberSaveable {
        mutableFloatStateOf(prefs.getFloat(KEY_FONT_SCALE, 1f).coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE))
    }
    var showZoomPill by remember { mutableStateOf(false) }
    LaunchedEffect(fontScale) {
        showZoomPill = true
        delay(900)
        showZoomPill = false
        prefs.edit().putFloat(KEY_FONT_SCALE, fontScale).apply()
    }
    val scope = rememberCoroutineScope()
    val zoomState = rememberTransformableState { zoom, _, _ ->
        fontScale = (fontScale * zoom).coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
    }
    val toggleZoom: () -> Unit = {
        val from = fontScale
        val target = if (fontScale < DOUBLE_TAP_SCALE - 0.05f) DOUBLE_TAP_SCALE else 1f
        scope.launch { animate(from, target) { v, _ -> fontScale = v.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE) } }
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
                Modifier
                    .fillMaxSize()
                    .transformable(zoomState)
                    .pointerInput(Unit) { detectTapGestures(onDoubleTap = { toggleZoom() }) },
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
            AnimatedVisibility(
                visible = showZoomPill,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f),
                    tonalElevation = 3.dp
                ) {
                    Text(
                        "${(fontScale * 100).roundToInt()}%",
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
