package dev.macdroid.app

import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MacDroidScreen(model: ShelfViewModel, onFiles: () -> Unit, onCamera: () -> Unit,
                           onCopy: (String) -> Unit, onOpen: (ShelfEntry) -> Unit) {
    val state by model.ui.collectAsStateWithLifecycle()
    var pairing by rememberSaveable { mutableStateOf(false) }
    var link by rememberSaveable { mutableStateOf("") }
    var draft by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("All") }
    var manual by rememberSaveable { mutableStateOf(false) }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { scanned -> model.pair(scanned) { pairing = false; link = "" } }
    }
    val launchScan: () -> Unit = {
        scanner.launch(ScanOptions().setCaptureActivity(QrScannerActivity::class.java).setDesiredBarcodeFormats(ScanOptions.QR_CODE).setOrientationLocked(false)
            .setPrompt("Point your camera at the QR code in MacDroid").setBeepEnabled(false))
    }
    val context = LocalContext.current
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchScan() else model.cameraDenied()
    }
    val scan: () -> Unit = {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) launchScan()
        else cameraPermission.launch(android.Manifest.permission.CAMERA)
    }
    val colors = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF81D5C8), secondary = Color(0xFFB5CCC6))
        else lightColorScheme(primary = Color(0xFF087B6F), secondary = Color(0xFF4A635E), background = Color(0xFFF6FAF8), surface = Color(0xFFF6FAF8))
    MaterialTheme(colorScheme = colors) {
        Scaffold(
            topBar = { TopAppBar(title = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { Image(painterResource(R.drawable.macdroid_brand), null, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))); Column { Text("MacDroid", fontWeight = FontWeight.Bold); Text("Two devices. One flow.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } } },
                actions = { IconButton(onClick = { pairing = true }) { Icon(Icons.Outlined.Link, "Mac Connection") } }) },
            bottomBar = {
                Surface(tonalElevation = 2.dp) {
                    Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = draft, onValueChange = { draft = it }, label = { Text("Text or link") }, modifier = Modifier.weight(1f), maxLines = 4)
                            FilledIconButton(onClick = { model.addText(draft) { draft = "" } }, enabled = draft.isNotBlank() && !state.busy, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.ArrowUpward, "Add to Shelf") }
                        }
                        Text(state.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        ) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    Text("Shared Shelf", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("From your phone to your Mac, and back.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item {
                    ElevatedCard(onClick = { pairing = true }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Outlined.LaptopMac, null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f)) {
                                Text(if (state.paired) "My Mac" else "Connect your Mac", fontWeight = FontWeight.SemiBold)
                                Text(if (state.online) "Connected · syncs while the app is open" else if (state.paired) "Reconnecting" else "Scan once to connect your devices", style = MaterialTheme.typography.bodySmall)
                            }
                            Icon(Icons.Outlined.ChevronRight, null)
                        }
                    }
                }
                if (state.cameraRequest != null) item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Your Mac requested a photo", fontWeight = FontWeight.Bold)
                            Text("Your photo will be added to the shared shelf.")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { model.dismissCamera(); onCamera() }) { Text("Open Camera") }
                                TextButton(onClick = model::dismissCamera) { Text("Close") }
                            }
                        }
                    }
                }
                if (!state.paired) item {
                    Button(onClick = scan, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = !state.busy) {
                        Icon(Icons.Outlined.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text("Scan QR to connect")
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Open “Connect Phone” on your Mac to show the QR code.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = onFiles, modifier = Modifier.weight(1f), enabled = !state.busy) { Icon(Icons.Outlined.UploadFile, null); Spacer(Modifier.width(6.dp)); Text("Files") }
                        OutlinedButton(onClick = onCamera, modifier = Modifier.weight(1f), enabled = !state.busy) { Icon(Icons.Outlined.PhotoCamera, null); Spacer(Modifier.width(6.dp)); Text("Camera") }
                    }
                }
                item { OutlinedTextField(search, { search = it }, label = { Text("Search the shelf") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (name in listOf("All", "Files", "Waiting")) FilterChip(selected = filter == name, onClick = { filter = name }, label = { Text(name) })
                    }
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                val visible = state.entries.filter { entry ->
                    (filter != "Files" || entry.kind == "file") && (filter != "Waiting" || entry.pending) &&
                        (search.isBlank() || "${entry.title} ${entry.text.orEmpty()}".contains(search, ignoreCase = true))
                }.sortedByDescending { it.pinned }
                if (visible.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Outlined.Inbox, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(if (search.isNotBlank()) "No matching items" else "Your shelf is empty", style = MaterialTheme.typography.titleMedium)
                        Text("Add files, text, or links.\nThey will appear on your connected Mac.", style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
                items(visible, key = { it.id }) { entry ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                                Icon(if (entry.kind == "file") Icons.Outlined.Description else if (entry.kind == "link") Icons.Outlined.Link else Icons.AutoMirrored.Outlined.Notes, null, tint = MaterialTheme.colorScheme.primary)
                                Text(entry.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                if (entry.pinned) Icon(Icons.Outlined.PushPin, "Pinned on Mac")
                            }
                            entry.text?.takeIf { it != entry.title }?.let { Text(it, maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (entry.pending) "Pending" else "${entry.json.optString("source")} · Delivered", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                if (entry.text != null) TextButton(onClick = { onCopy(entry.text!!) }) { Text("Copy") }
                                if (entry.kind != "text") TextButton(onClick = { onOpen(entry) }) { Text("Open") }
                            }
                        }
                    }
                }
                item { Text("Up to 8 MiB per file · direct local transfer", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        if (pairing) AlertDialog(onDismissRequest = { if (!state.busy) { pairing = false; link = "" } },
            title = { Text(if (state.paired) "Mac Connection" else "Connect your Mac") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Open “Connect Phone” on your Mac, then scan its QR code. Use the same network unless a relay is configured.")
                Button(onClick = scan, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !state.busy) {
                    Icon(Icons.Outlined.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text("Scan QR to connect")
                }
                Text("The scanner needs camera access. You can paste a pairing link instead.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { manual = !manual }) { Text(if (manual) "Hide" else "Use a pairing link") }
                if (manual) OutlinedTextField(link, { link = it }, label = { Text("Pairing link from your Mac") }, modifier = Modifier.fillMaxWidth(), maxLines = 4, enabled = !state.busy)
                if (state.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Connecting to your Mac…", style = MaterialTheme.typography.bodySmall) }
            } },
            confirmButton = { if (manual) TextButton(onClick = { model.pair(link) { link = ""; pairing = false } }, enabled = link.isNotBlank() && !state.busy) { Text("Connect") } },
            dismissButton = { TextButton(onClick = { pairing = false; link = "" }, enabled = !state.busy) { Text("Close") } })
        state.error?.let { error -> AlertDialog(onDismissRequest = model::clearError, title = { Text("Could Not Complete the Action") }, text = { Text(error) }, confirmButton = { TextButton(onClick = model::clearError) { Text("OK") } }) }
    }
}
