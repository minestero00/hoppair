package dev.macdroid.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.macdroid.app.BuildConfig
import dev.macdroid.app.UpdateStage
import dev.macdroid.app.UpdateState

@Composable
internal fun UpdateBanner(state: UpdateState, onOpen: () -> Unit) {
    Surface(onClick = onOpen, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.SystemUpdate, null)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(when (state.stage) {
                    UpdateStage.DOWNLOADING -> "Downloading update · ${state.progress}%"
                    UpdateStage.VERIFYING -> "Verifying update"
                    UpdateStage.READY -> "Update ready to install"
                    UpdateStage.PERMISSION -> "Allow installation in Android settings"
                    UpdateStage.INSTALLING -> "Confirm the update in Android"
                    else -> "Hoppair ${state.release?.version.orEmpty()} is available"
                }, style = MaterialTheme.typography.titleMedium)
                Text("Tap to view update", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UpdateSheet(state: UpdateState, onCheck: () -> Unit, onUpdate: () -> Unit, onCancel: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("App updates", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f).semantics { heading() })
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
            }
            Text("Installed · ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(when (state.stage) {
                UpdateStage.CHECKING -> "Checking for updates…"
                UpdateStage.CURRENT -> "You’re up to date"
                UpdateStage.AVAILABLE -> "Hoppair ${state.release?.version.orEmpty()} is ready to download"
                UpdateStage.DOWNLOADING -> "Downloading · ${state.progress}%"
                UpdateStage.VERIFYING -> "Verifying the download and signing key…"
                UpdateStage.READY -> "Verified and ready to install"
                UpdateStage.PERMISSION -> "Allow Hoppair to install updates in Android settings"
                UpdateStage.INSTALLING -> "Confirm installation in Android"
                UpdateStage.ERROR -> "Update needs another try"
                UpdateStage.IDLE -> "Check for a newer version of Hoppair"
            }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (state.stage == UpdateStage.DOWNLOADING) LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth())
            if (state.stage in setOf(UpdateStage.CHECKING, UpdateStage.VERIFYING)) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (state.release != null) {
                Text("Android will ask you to confirm installation. Your saved Mac and shelf stay in place.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when (state.stage) {
                UpdateStage.AVAILABLE, UpdateStage.READY, UpdateStage.ERROR -> Button(onClick = if (state.release == null) onCheck else onUpdate,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(16.dp)) {
                    Text(if (state.release == null) "Retry check" else if (state.stage == UpdateStage.READY) "Install update" else if (state.stage == UpdateStage.ERROR) "Retry download" else "Download update")
                }
                UpdateStage.IDLE, UpdateStage.CURRENT -> Button(onClick = onCheck, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(16.dp)) { Text("Check for updates") }
                else -> Unit
            }
            if (state.stage in setOf(UpdateStage.CHECKING, UpdateStage.DOWNLOADING, UpdateStage.VERIFYING, UpdateStage.READY, UpdateStage.PERMISSION)) {
                TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Cancel update") }
            }
        }
    }
}
