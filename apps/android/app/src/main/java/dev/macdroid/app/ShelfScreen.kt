package dev.macdroid.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.ImeAction
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.macdroid.app.ui.*
import kotlinx.coroutines.launch

@Composable
internal fun HoppairScreen(model: ShelfViewModel, onFiles: () -> Unit, onCamera: () -> Unit,
    onCopy: (String) -> Unit, onOpen: (ShelfEntry) -> Unit) {
    val state by model.ui.collectAsStateWithLifecycle()
    var pairing by rememberSaveable { mutableStateOf(false) }
    var manual by rememberSaveable { mutableStateOf(false) }
    var link by rememberSaveable { mutableStateOf("") }
    var draft by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("All") }
    var attachments by rememberSaveable { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val searchFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copy: (String) -> Unit = { text -> onCopy(text); scope.launch { snackbar.showSnackbar("Copied") } }
    val closeSearch: () -> Unit = { searching = false; search = ""; focusManager.clearFocus(); keyboard?.hide() }
    val showComposer = state.paired || state.entries.isNotEmpty()
    LaunchedEffect(state.entries, selectedId) {
        if (selectedId != null && state.entries.none { it.id == selectedId }) selectedId = null
    }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { scanned -> model.pair(scanned) { pairing = false; link = "" } }
    }
    val launchScan: () -> Unit = {
        scanner.launch(ScanOptions().setCaptureActivity(QrScannerActivity::class.java).setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setOrientationLocked(false).setPrompt("Point your camera at the QR code in Hoppair").setBeepEnabled(false))
    }
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchScan() else model.cameraDenied()
    }
    val scan: () -> Unit = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchScan()
        else permission.launch(Manifest.permission.CAMERA)
    }
    BackHandler(enabled = searching && !pairing && !attachments && selectedId == null, onBack = closeSearch)
    HoppairTheme {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                Column {
                    ShelfToolbar(searching, showComposer,
                        onSearch = { if (searching) closeSearch() else searching = true }, onConnection = { pairing = true })
                    if (searching && showComposer) {
                        LaunchedEffect(Unit) { searchFocus.requestFocus() }
                        Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
                            contentAlignment = Alignment.Center) {
                            OutlinedTextField(search, { search = it }, label = { Text("Search your shelf") },
                                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                                modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 12.dp).focusRequester(searchFocus),
                                singleLine = true, shape = RoundedCornerShape(18.dp),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                                colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                    focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant))
                        }
                    }
                }
            },
            bottomBar = {
                if (showComposer) {
                    ShelfComposer(draft, { draft = it }, state.busy, onAttach = { attachments = true },
                        onSend = { model.addText(draft) { draft = "" } })
                }
            },
            contentWindowInsets = WindowInsets.safeDrawing.only(
                if (showComposer) WindowInsetsSides.Horizontal else WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
        ) { padding ->
            BoxWithConstraints(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                val gutter = if (maxWidth >= 600.dp) 32.dp else 24.dp
                val compact = maxHeight < 400.dp
                val contentWidth = Modifier.widthIn(max = 720.dp).fillMaxWidth().align(Alignment.TopCenter)
                LazyColumn(contentWidth, contentPadding = PaddingValues(start = gutter, end = gutter, top = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!state.paired && !searching) {
                        item { WelcomePanel(state.busy, onScan = scan, onManual = { manual = true; pairing = true }) }
                    } else if (state.paired && !searching) {
                        item {
                            Row(Modifier.padding(top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Your shelf", style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineLarge,
                                    modifier = Modifier.weight(1f).semantics { heading() })
                                Text(state.entries.size.toString() + if (state.entries.size == 1) " item" else " items",
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (!compact) item { ConnectionCard(state, onClick = { pairing = true }) }
                    }
                    if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    state.cameraRequest?.let {
                        item {
                            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("A photo for your Mac", style = MaterialTheme.typography.titleMedium)
                                    Text("Your Mac requested a photo. Open the camera when you're ready.", style = MaterialTheme.typography.bodyMedium)
                                    Button(onClick = { model.dismissCamera(); onCamera() }, modifier = Modifier.fillMaxWidth()) { Text("Open camera") }
                                    TextButton(onClick = model::dismissCamera, modifier = Modifier.fillMaxWidth()) { Text("Not now") }
                                }
                            }
                        }
                    }
                    if (state.paired || state.entries.isNotEmpty()) {
                        item { ShelfFilters(filter, state.entries.count { it.pending }, onSelect = { filter = it }) }
                        val visible = state.entries.filter { entry ->
                            when (filter) { "Files" -> entry.kind == "file"; "Notes" -> entry.kind == "text"; "Links" -> entry.kind == "link"; "Waiting" -> entry.pending; else -> true } &&
                                (search.isBlank() || (entry.title + " " + entry.text.orEmpty()).contains(search, ignoreCase = true))
                        }.sortedByDescending { it.pinned }
                        if (visible.isEmpty()) item {
                            EmptyShelf(search.isNotBlank() || filter != "All", filter == "Waiting" && search.isBlank() && state.entries.none { it.pending })
                        }
                        items(visible, key = { it.id }) { entry ->
                            ShelfItem(entry, onDetails = { selectedId = entry.id }, onCopy = copy, onOpen = onOpen)
                        }
                    }
                }
            }
        }
        if (pairing) ConnectionSheet(state, manual, link, { link = it }, onManual = { manual = !manual }, onScan = scan,
            onPair = { model.pair(link) { pairing = false; link = "" } }, onDismiss = { pairing = false })
        if (attachments) AttachmentSheet(
            onFiles = { attachments = false; onFiles() }, onCamera = { attachments = false; onCamera() }, onDismiss = { attachments = false })
        state.entries.firstOrNull { it.id == selectedId }?.let { entry ->
            ItemDetailsSheet(entry, onCopy = onCopy, onOpen = onOpen, onDismiss = { selectedId = null })
        }
        state.error?.let { error ->
            AlertDialog(onDismissRequest = model::clearError, title = { Text("Something needs your attention") }, text = { Text(error) },
                confirmButton = { TextButton(onClick = model::clearError) { Text("OK") } })
        }
    }
}
