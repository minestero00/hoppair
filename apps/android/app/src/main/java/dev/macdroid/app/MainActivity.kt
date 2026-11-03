package dev.macdroid.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import dev.macdroid.app.ui.HoppairTheme
import androidx.core.net.toUri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    private val model: ShelfViewModel by viewModels()
    private val updater: AppUpdateViewModel by viewModels()
    private val installUpdate = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        updater.installerReturned(result.resultCode == RESULT_OK)
    }
    private val allowUpdateInstall = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        updater.permissionReturned(packageManager.canRequestPackageInstalls())
    }
    private var cameraUri: Uri? = null
    private data class PairingInvite(val link: String, val host: String, val relayHost: String?)
    private var pairingInvite by mutableStateOf<PairingInvite?>(null)
    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) model.addFiles(uris) }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) takePhoto() else android.widget.Toast.makeText(this, "Allow camera access to take a photo.", android.widget.Toast.LENGTH_LONG).show()
    }
    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = cameraUri
        if (success && uri != null) model.addFiles(listOf(uri))
        cameraUri = null
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraUri = savedInstanceState?.getString("cameraUri")?.let(Uri::parse)
        enableEdgeToEdge()
        setContent {
            HoppairScreen(model, updater, onInstallUpdate = ::launchVerifiedUpdate, onFiles = { pickFiles.launch(arrayOf("*/*")) }, onCamera = ::takePhoto,
                onCopy = { text -> getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Hoppair", text)) }, onOpen = ::openEntry)
            pairingInvite?.let { invite ->
                HoppairTheme {
                    AlertDialog(onDismissRequest = ::dismissInvite,
                        title = { Text("Connect to your Mac?") },
                        text = { Text("Only continue if you scanned this code from your Mac. Your shelf will be shared with the Mac at ${invite.host}." + (invite.relayHost?.let { "\n\nRelay: $it" } ?: "")) },
                        confirmButton = { TextButton(onClick = { dismissInvite(); model.pair(invite.link) }) { Text("Connect") } },
                        dismissButton = { TextButton(onClick = ::dismissInvite) { Text("Cancel") } })
                }
            }
        }
        receiveLink(intent)
        if (savedInstanceState == null) receiveShare(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { while (isActive) { updater.check(manual = false); delay(60_000) } }
                try { while (isActive) { model.sync(); delay(3000) } }
                finally { model.pauseConnection() }
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); receiveLink(intent); receiveShare(intent) }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("cameraUri", cameraUri?.toString()); super.onSaveInstanceState(outState) }
    private fun dismissInvite() { pairingInvite = null; intent.data = null }
    private fun receiveLink(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW) return
        pairingInvite = null
        val link = intent.dataString ?: return
        try {
            val pair = Pairing.parse(link)
            pairingInvite = PairingInvite(link, pair.host, pair.relay?.let { java.net.URI(it).host })
        } catch (_: Exception) {
            intent.data = null
            android.widget.Toast.makeText(this, "Invalid pairing link. Scan the QR code shown on your Mac.", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    private fun receiveShare(intent: Intent) {
        try {
            val uris = when (intent.action) {
                Intent.ACTION_SEND -> listOfNotNull(androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
                Intent.ACTION_SEND_MULTIPLE -> androidx.core.content.IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                else -> return
            }
            if (uris.isNotEmpty()) {
                require(uris.size <= MAX_SHELF_ITEMS && uris.all { externalShareAllowed(it, packageName) }) { "Share up to 500 files from another app’s content provider." }
                model.addFiles(uris)
            } else if (intent.action == Intent.ACTION_SEND) {
                intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.let { text ->
                    require(text.length <= 64 * 1024) { "Text must be 64 KiB or smaller." }
                    val value = text.toString()
                    require(value.toByteArray(Charsets.UTF_8).size <= 64 * 1024) { "Text must be 64 KiB or smaller." }
                    model.addText(value)
                }
            }
        } catch (error: Exception) {
            android.widget.Toast.makeText(this, error.message?.take(160) ?: "Could not read this shared item.", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    private fun launchVerifiedUpdate() {
        val file = updater.consumeInstallRequest() ?: return
        try {
            if (!packageManager.canRequestPackageInstalls()) {
                updater.permissionRequired()
                allowUpdateInstall.launch(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                return
            }
            require(file.isFile) { "The update file is no longer available." }
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            updater.installerStarted()
            @Suppress("DEPRECATION")
            val installer = Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_RETURN_RESULT, true)
            installUpdate.launch(installer)
        } catch (_: Exception) { updater.installationFailed() }
    }
    private fun takePhoto() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(android.Manifest.permission.CAMERA)
            return
        }
        try {
            val directory = File(cacheDir, "camera").apply { mkdirs() }
            // Keep only the most recent capture; the repository copies successful photos into the shelf.
            directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86400000 }?.forEach { it.delete() }
            val file = File.createTempFile("capture-", ".jpg", directory)
            cameraUri = FileProvider.getUriForFile(this, "$packageName.files", file)
            camera.launch(cameraUri!!)
        } catch (_: Exception) { android.widget.Toast.makeText(this, "Could not open the camera app.", android.widget.Toast.LENGTH_LONG).show() }
    }
    private fun openEntry(entry: ShelfEntry) {
        try {
            val intent = if (entry.kind == "link") {
                val uri = entry.text!!.toUri()
                require(uri.scheme == "https" || uri.scheme == "http")
                Intent(Intent.ACTION_VIEW, uri)
            } else {
                val file = checkNotNull(model.contentFile(entry))
                val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, entry.json.optString("mime", "application/octet-stream"))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Open"))
        } catch (_: Exception) { android.widget.Toast.makeText(this, "No app is available to open this item.", android.widget.Toast.LENGTH_LONG).show() }
    }
}

/** Keep QR scanning in the user's current orientation. */
class QrScannerActivity : com.journeyapps.barcodescanner.CaptureActivity()

/** External intents never borrow Hoppair's own private FileProvider privileges. */
internal fun externalShareAllowed(uri: Uri, packageName: String): Boolean = uri.scheme == "content" && !uri.authority.isNullOrBlank() &&
    !uri.authority!!.substringAfterLast('@').equals("$packageName.files", ignoreCase = true)
