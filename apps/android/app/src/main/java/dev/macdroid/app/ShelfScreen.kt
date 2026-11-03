package dev.macdroid.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.ImeAction
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.app.Activity
import android.content.Intent
import dev.macdroid.app.ui.*
import kotlinx.coroutines.launch
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

@Composable
internal fun HoppairScreen(model: ShelfViewModel, updater: AppUpdateViewModel, onInstallUpdate: () -> Unit, onFiles: () -> Unit, onCamera: () -> Unit,
    onCopy: (String) -> Unit, onOpen: (ShelfEntry) -> Unit) {
    val state by model.ui.collectAsStateWithLifecycle()
    val updateState by updater.ui.collectAsStateWithLifecycle()
    LaunchedEffect(updateState.stage, updateState.installRequested) {
        if (updateState.stage == UpdateStage.READY && updateState.installRequested) onInstallUpdate()
    }
    var pairing by rememberSaveable { mutableStateOf(false) }
    var manual by rememberSaveable { mutableStateOf(false) }
    var link by rememberSaveable { mutableStateOf("") }
    var draft by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("All") }
    var attachments by rememberSaveable { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val searchFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val hazeState = rememberHazeState()
    val copy: (String) -> Unit = { text -> onCopy(text); scope.launch { snackbar.showSnackbar("Copied") } }
    val closeSearch: () -> Unit = { searching = false; search = ""; focusManager.clearFocus(); keyboard?.hide() }
    val showComposer = state.paired || state.entries.isNotEmpty()
    val results = rememberShelfSearch(state.entries, search, filter)
    LaunchedEffect(state.entries, selectedId) {
        if (selectedId != null && state.entries.none { it.id == selectedId }) selectedId = null
    }
    val scanner = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            if (result.data?.getBooleanExtra(QrScannerActivity.USE_LINK, false) == true) { manual = true; pairing = true }
            else result.data?.getStringExtra(QrScannerActivity.PAIRING_LINK)?.let { scanned ->
                model.pair(scanned) { pairing = false; link = "" }
            }
        }
    }
    val context = LocalContext.current
    val launchScan: () -> Unit = { scanner.launch(Intent(context, QrScannerActivity::class.java)) }
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
                    ShelfToolbar(searching, showComposer, hazeState,
                        onSearch = { if (searching) closeSearch() else searching = true }, onConnection = { pairing = true }, onCheckUpdates = { updater.check() })
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
                    ShelfComposer(draft, { draft = it }, state.busy, hazeState, onAttach = { attachments = true },
                        onSend = { val submitted = draft; model.addText(submitted) { if (draft == submitted) draft = "" } })
                }
            },
            contentWindowInsets = WindowInsets.safeDrawing.only(
                if (showComposer) WindowInsetsSides.Horizontal else WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
        ) { padding ->
            BoxWithConstraints(Modifier.fillMaxSize().consumeWindowInsets(padding).hazeSource(hazeState).hoppairCanvas()) {
                val gutter = if (maxWidth >= 600.dp) 32.dp else 24.dp
                val compact = maxHeight - padding.calculateTopPadding() - padding.calculateBottomPadding() < 400.dp
                val contentWidth = Modifier.widthIn(max = 720.dp).fillMaxWidth().align(Alignment.TopCenter)
                LazyColumn(contentWidth, contentPadding = PaddingValues(start = gutter, end = gutter,
                    top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (updateState.release != null && updateState.stage !in setOf(UpdateStage.IDLE, UpdateStage.CURRENT, UpdateStage.CHECKING)) {
                        item { UpdateBanner(updateState, onOpen = updater::show) }
                    }
                    if (!state.paired && !searching) {
                        item { WelcomePanel(state.busy, onScan = scan, onManual = { manual = true; pairing = true }) }
                    } else if (state.paired && !searching && !compact) {
                        item {
                            Row(Modifier.padding(top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Your shelf", style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineLarge,
                                    modifier = Modifier.weight(1f).semantics { heading() })
                                Text(state.entries.size.toString() + if (state.entries.size == 1) " item" else " items",
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        item { ConnectionCard(state, onClick = { pairing = true }) }
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
                        if (results.loading) item {
                            LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "Updating search results" })
                        }
                        val visible = results.entries
                        if (results.ready && visible.isEmpty()) item {
                            EmptyShelf(search.isNotBlank() || filter != "All", filter == "Waiting" && search.isBlank() && state.entries.none { it.pending })
                        }
                        items(visible, key = { it.id }) { entry ->
                            ShelfItem(entry, onDetails = { selectedId = entry.id }, onCopy = copy, onOpen = onOpen)
                        }
                    }
                }
            }
        }
        if (updateState.sheet) UpdateSheet(updateState, onCheck = { updater.check() }, onUpdate = updater::update,
            onCancel = { updater.cancel() }, onDismiss = updater::dismiss)
        if (pairing) ConnectionSheet(state, manual, link, { link = it }, onManual = { manual = !manual }, onScan = scan,
            onPair = { model.pair(link) { pairing = false; link = "" } }, onDismiss = { pairing = false }, onRename = model::renameDevice)
        if (attachments) AttachmentSheet(
            onFiles = { attachments = false; onFiles() }, onCamera = { attachments = false; onCamera() }, onDismiss = { attachments = false })
        state.entries.firstOrNull { it.id == selectedId }?.let { entry ->
            ItemDetailsSheet(entry, onCopy = onCopy, onOpen = onOpen, onDismiss = { selectedId = null },
                canDelete = state.canDelete, busy = state.busy, onDelete = { deleteId = entry.id })
        }
        state.entries.firstOrNull { it.id == deleteId }?.let { entry ->
            AlertDialog(onDismissRequest = { deleteId = null }, title = { Text("Delete from the shared shelf?") },
                text = { Text("“${entry.title}” will be removed from this phone and ${state.peerName}. Both apps must be online. Copies saved outside Hoppair are kept.",
                    modifier = Modifier.verticalScroll(rememberScrollState())) },
                confirmButton = { TextButton(onClick = { deleteId = null; model.deleteEntry(entry.id) { selectedId = null } }, enabled = !state.busy) { Text("Delete") } },
                dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Cancel") } })
        }
        state.replacement?.let { replacement ->
            AlertDialog(onDismissRequest = model::cancelPairingReplacement,
                title = { Text("Replace your saved Mac?") },
                text = {
                    val waiting = state.entries.count { it.pending }
                    Text("This QR is from a different Mac or a new installation of Hoppair. Continue only if you opened it on your Mac.\n\n" +
                        "Connect to ${replacement.host} instead of ${state.peerName}. Items already saved on this phone will stay here." +
                        (if (waiting > 0) "\n\n$waiting ${if (waiting == 1) "item waiting" else "items waiting"} to send will be shared with this Mac." else ""),
                        modifier = Modifier.verticalScroll(rememberScrollState()))
                },
                confirmButton = { TextButton(onClick = { model.confirmPairingReplacement(replacement.id) }, enabled = !state.busy) { Text("Replace and connect") } },
                dismissButton = { TextButton(onClick = model::cancelPairingReplacement) { Text("Keep current Mac") } })
        }
        state.error?.let { error ->
            AlertDialog(onDismissRequest = model::clearError, title = { Text("Something needs your attention") }, text = { Text(error) },
                confirmButton = { TextButton(onClick = model::clearError) { Text("OK") } })
        }
    }
}
