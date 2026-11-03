package dev.macdroid.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.macdroid.app.ShelfEntry
import dev.macdroid.app.ShelfState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConnectionSheet(state: ShelfState, manual: Boolean, link: String, onLink: (String) -> Unit,
    onManual: () -> Unit, onScan: () -> Unit, onPair: () -> Unit, onDismiss: () -> Unit, onRename: (String) -> Unit = {}) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            SheetHeading(if (state.paired) "Your Mac" else "Make the connection", onDismiss)
            if (state.paired) {
                Text(if (state.online) "Connected to ${state.peerName}" else "Waiting for ${state.peerName}", style = MaterialTheme.typography.titleMedium,
                    color = if (state.online) ShelfColors.success() else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(state.status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Your Mac is saved. Hoppair reconnects automatically when you open the app.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            OutlinedTextField(value = state.deviceName, onValueChange = onRename, label = { Text("This phone’s name") },
                placeholder = { Text(state.defaultDeviceName) }, supportingText = { Text("Shown on your Mac. Leave blank to use the system name.") },
                modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(16.dp))
            if (state.paired) {
                TextButton(onClick = onScan, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = ShelfColors.link())) {
                    Icon(Icons.Outlined.QrCodeScanner, null); Spacer(Modifier.width(10.dp)); Text("Update connection with QR")
                }
            } else {
                Text("Open Connect Phone in Hoppair on your Mac, then scan its QR code.", style = MaterialTheme.typography.bodyLarge)
                Button(onClick = onScan, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                    shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(16.dp)) {
                    Icon(Icons.Outlined.QrCodeScanner, null); Spacer(Modifier.width(10.dp)); Text("Scan QR to connect")
                }
            }
            TextButton(onClick = onManual, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = ShelfColors.link())) { Text(if (manual) "Hide pairing link" else "Use a pairing link") }
            if (manual) {
                OutlinedTextField(value = link, onValueChange = onLink, label = { Text("Pairing link from your Mac") },
                    modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4, enabled = !state.busy, shape = RoundedCornerShape(16.dp))
                Button(onClick = onPair, enabled = link.isNotBlank() && !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = RoundedCornerShape(16.dp)) { Text(if (state.busy) "Connecting…" else "Connect") }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Keep both apps open to sync. Use the same Wi-Fi unless your Mac has a relay set up.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SheetHeading(title: String, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f).semantics { heading() })
        IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Close") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AttachmentSheet(onFiles: () -> Unit, onCamera: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SheetHeading("Add to your shelf", onDismiss)
            AttachmentAction(Icons.Outlined.UploadFile, "Choose files", "Browse your phone", onFiles)
            AttachmentAction(Icons.Outlined.PhotoCamera, "Take a photo", "Send a new photo to your Mac", onCamera)
            Text("Up to 8 MiB per file. Items wait safely when your Mac is offline.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun AttachmentAction(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.background) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ItemDetailsSheet(entry: ShelfEntry, onCopy: (String) -> Unit, onOpen: (ShelfEntry) -> Unit, onDismiss: () -> Unit) {
    var copied by remember(entry.id) { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            SheetHeading(when (entry.kind) { "file" -> "File"; "link" -> "Link"; else -> "Note" }, onDismiss)
            Box(Modifier.size(56.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                Icon(entryIcon(entry), null, Modifier.size(28.dp), tint = if (entry.kind == "link") ShelfColors.link() else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SelectionContainer { Text(if (entry.kind == "file") entry.filename else entry.text ?: entry.title, style = MaterialTheme.typography.bodyLarge) }
            Text(if (entry.pending) "Waiting to send · saved on this phone" else entryMetadata(entry),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (entry.kind != "text") {
                Button(onClick = { onOpen(entry) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(16.dp)) {
                    Text(if (entry.kind == "file") "Open file" else "Open link")
                }
            }
            entry.text?.let { text ->
                Button(onClick = { onCopy(text); copied = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (entry.kind == "text") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
                        contentColor = if (entry.kind == "text") MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer)) {
                    Icon(if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy, null)
                    Spacer(Modifier.width(8.dp)); Text(if (copied) "Copied" else "Copy")
                }
            }
        }
    }
}
