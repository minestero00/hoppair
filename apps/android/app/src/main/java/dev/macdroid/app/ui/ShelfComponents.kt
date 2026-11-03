package dev.macdroid.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.macdroid.app.R
import dev.macdroid.app.ShelfEntry
import dev.macdroid.app.ShelfState
import java.util.Locale
import dev.chrisbanes.haze.HazeState

@Composable
internal fun ShelfToolbar(searching: Boolean, canSearch: Boolean, hazeState: HazeState, onSearch: () -> Unit, onConnection: () -> Unit,
    onCheckUpdates: () -> Unit) {
    var menu by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
        .padding(horizontal = 16.dp, vertical = 8.dp).hoppairGlass(hazeState).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.hoppair_brand), null, Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)))
        Spacer(Modifier.width(10.dp))
        Text("Hoppair", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (canSearch) IconButton(onClick = onSearch) { Icon(if (searching) Icons.Outlined.Close else Icons.Outlined.Search, if (searching) "Close search" else "Search your shelf") }
        IconButton(onClick = onConnection) { Icon(Icons.Outlined.LaptopMac, "Mac connection") }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More options") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Check for updates") }, leadingIcon = { Icon(Icons.Outlined.SystemUpdate, null) },
                    onClick = { menu = false; onCheckUpdates() })
            }
        }
    }
}

@Composable
internal fun ConnectionCard(state: ShelfState, onClick: () -> Unit) {
    val pending = state.entries.count { it.pending }
    Surface(onClick = onClick, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(46.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.LaptopMac, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(if (state.online) "Connected to ${state.peerName}" else "Waiting for ${state.peerName}", style = MaterialTheme.typography.titleMedium)
                Text(if (!state.online && pending > 0) "$pending waiting · ${state.status}" else state.status,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Icon(if (state.online) Icons.Outlined.CheckCircle else Icons.Outlined.CloudOff, null,
                tint = if (state.online) ShelfColors.success() else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
internal fun ShelfFilters(selected: String, waiting: Int, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val filters = listOf("All", "Files", "Notes", "Links") + if (waiting > 0 || selected == "Waiting") listOf("Waiting") else emptyList()
        filters.forEach { filter ->
            FilterChip(selected = selected == filter, onClick = { onSelect(filter) },
                label = { Text(if (filter == "Waiting" && waiting > 0) "Waiting ($waiting)" else filter) },
                modifier = Modifier.heightIn(min = 48.dp),
                shape = CircleShape, border = null,
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    selectedContainerColor = MaterialTheme.colorScheme.onSurface,
                    selectedLabelColor = MaterialTheme.colorScheme.surface))
        }
    }
}

internal fun entryIcon(entry: ShelfEntry): ImageVector = when {
    entry.kind == "link" -> Icons.Outlined.Link
    entry.kind != "file" -> Icons.AutoMirrored.Outlined.Notes
    entry.json.optString("mime").startsWith("image/") -> Icons.Outlined.Image
    else -> Icons.Outlined.Description
}

internal fun entryMetadata(entry: ShelfEntry): String {
    val source = entry.json.optString("source").ifBlank { "This device" }
    val detail = when (entry.kind) {
        "file" -> if (entry.size >= 1024 * 1024) String.format(Locale.getDefault(), "%.1f MB", entry.size / (1024.0 * 1024))
            else if (entry.size >= 1024) String.format(Locale.getDefault(), "%.0f KB", entry.size / 1024.0) else entry.size.toString() + " B"
        "link" -> "Link"
        else -> "Note"
    }
    return "$source · $detail"
}

@Composable
internal fun ShelfItem(entry: ShelfEntry, onDetails: () -> Unit, onCopy: (String) -> Unit, onOpen: (ShelfEntry) -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.clickable(onClick = onDetails).padding(start = 16.dp, top = 18.dp, end = 8.dp, bottom = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                Icon(entryIcon(entry), null, tint = if (entry.kind == "link") ShelfColors.link() else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(entry.title.substringBefore('\n'), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (entry.kind == "text") {
                    entry.text?.substringAfter('\n', "")?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (entry.pinned) Icon(Icons.Outlined.PushPin, "Pinned on Mac", Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (entry.pending) Icon(Icons.Outlined.Schedule, null, Modifier.size(13.dp), tint = ShelfColors.warning())
                    Text(if (entry.pending) "Waiting to send" else entryMetadata(entry),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (entry.pending) ShelfColors.warning() else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (entry.kind == "text") {
                IconButton(onClick = { entry.text?.let(onCopy) }) { Icon(Icons.Outlined.ContentCopy, "Copy " + entry.title, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                IconButton(onClick = { onOpen(entry) }) { Icon(Icons.Outlined.OpenInNew, "Open " + entry.title, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
internal fun ShelfComposer(draft: String, onDraft: (String) -> Unit, busy: Boolean, hazeState: HazeState, onAttach: () -> Unit, onSend: () -> Unit) {
    Surface(color = androidx.compose.ui.graphics.Color.Transparent) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .navigationBarsPadding().imePadding().padding(horizontal = 16.dp).padding(top = 10.dp, bottom = 12.dp)) {
                Row(Modifier.hoppairGlass(hazeState).padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalIconButton(onClick = onAttach, enabled = !busy, modifier = Modifier.size(48.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface)) {
                        Icon(Icons.Outlined.Add, "Add a file or photo")
                    }
                    OutlinedTextField(value = draft, onValueChange = onDraft, placeholder = { Text("Note or link") },
                        modifier = Modifier.weight(1f).semantics { contentDescription = "Note or link" },
                        maxLines = 4, shape = RoundedCornerShape(24.dp),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface, focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
                        trailingIcon = {
                            FilledIconButton(onClick = onSend, enabled = draft.isNotBlank() && !busy, modifier = Modifier.padding(end = 4.dp).size(48.dp)) {
                                Icon(Icons.Outlined.ArrowUpward, "Send to shelf", Modifier.size(22.dp))
                            }
                        })
                }
            }
        }
    }
}

@Composable
internal fun EmptyShelf(filtered: Boolean, waiting: Boolean) {
    Column(Modifier.fillMaxWidth().padding(vertical = 44.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(72.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
            Icon(if (filtered) Icons.Outlined.Search else Icons.Outlined.Inbox, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        Text(if (filtered) "Nothing here yet" else "Ready when you are", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(if (waiting) "All your items have been sent." else if (filtered) "Try another search or filter." else "Send a file, photo, or note.\nFind it here on both devices.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun WelcomePanel(busy: Boolean, onScan: () -> Unit, onManual: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(188.dp, 140.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.align(Alignment.CenterStart).size(116.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(32.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.LaptopMac, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Surface(Modifier.align(Alignment.BottomEnd).size(80.dp, 100.dp), shape = RoundedCornerShape(25.dp),
                color = MaterialTheme.colorScheme.surface, border = BorderStroke(4.dp, MaterialTheme.colorScheme.background)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.PhoneAndroid, null, Modifier.size(45.dp), tint = MaterialTheme.colorScheme.primary) }
            }
        }
        Spacer(Modifier.height(32.dp))
        Text("Your Mac.\nNow on your phone.", style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(14.dp))
        Text("Files, links, and notes, together.\nScan once to make the connection.", style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))
        Button(onClick = onScan, enabled = !busy, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            contentPadding = PaddingValues(16.dp)) {
            Icon(Icons.Outlined.QrCodeScanner, null); Spacer(Modifier.width(10.dp)); Text("Scan QR to connect")
        }
        TextButton(onClick = onManual, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = ShelfColors.link())) { Text("Use a pairing link") }
        Spacer(Modifier.height(16.dp))
        Text("On your Mac, open Hoppair → Connect Phone.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}
