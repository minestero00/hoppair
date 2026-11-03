package dev.macdroid.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraState
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.core.ImageAnalysis
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FlashlightOff
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.ZoomSuggestionOptions
import com.google.mlkit.vision.barcode.common.Barcode
import dev.macdroid.app.ui.HoppairTheme

/** Camera frames and pairing values are never logged or sent to a server by this screen. */
class QrScannerActivity : ComponentActivity() {
    companion object {
        const val PAIRING_LINK = "dev.hoppair.PAIRING_LINK"
        const val USE_LINK = "dev.hoppair.USE_PAIRING_LINK"
    }
    private lateinit var controller: LifecycleCameraController
    private lateinit var scanner: BarcodeScanner
    private var observedCamera: CameraInfo? = null
    private var lastDetectionAt = 0L
    private val guidance = "Point at your Mac’s QR. Tap to focus or pinch to zoom."
    private var delivered = false
    private var destroyed = false
    private var ready by mutableStateOf(false)
    private var torch by mutableStateOf(false)
    private var flashAvailable by mutableStateOf(false)
    private var zoom by mutableFloatStateOf(1f)
    private var maximumZoom by mutableFloatStateOf(1f)
    private var showPrivacy by mutableStateOf(false)
    private var message by mutableStateOf("Starting camera…")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        controller = LifecycleCameraController(this).apply {
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
            setImageAnalysisBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            imageAnalysisResolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()
            isTapToFocusEnabled = true
            isPinchToZoomEnabled = true
        }
        scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAllPotentialBarcodes()
            .setZoomSuggestionOptions(ZoomSuggestionOptions.Builder { ratio -> setZoom(ratio) }
                .setMaxSupportedZoomRatio(4f).build())
            .build())
        val executor = ContextCompat.getMainExecutor(this)
        controller.setImageAnalysisAnalyzer(executor,
            MlKitAnalyzer(listOf(scanner), ImageAnalysis.COORDINATE_SYSTEM_ORIGINAL, executor) { result ->
                if (destroyed || delivered || showPrivacy || isFinishing || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@MlKitAnalyzer
                if (result.getThrowable(scanner) != null) {
                    message = "Could not read the camera image. Try again or use a pairing link."
                    return@MlKitAnalyzer
                }
                val codes = result.getValue(scanner).orEmpty()
                val values = codes.mapNotNull { it.rawValue }.distinct()
                val valid = values.filter { isPairingCode(it) }
                val now = android.os.SystemClock.elapsedRealtime()
                if (codes.isNotEmpty()) lastDetectionAt = now
                else if (ready && now - lastDetectionAt > 1500) message = guidance
                when {
                    valid.size > 1 -> message = "Keep only one Hoppair QR code in view."
                    valid.size == 1 -> {
                        delivered = true
                        setResult(RESULT_OK, Intent().putExtra(PAIRING_LINK, valid.single()))
                        finish()
                    }
                    values.isNotEmpty() -> message = "That is not a Hoppair pairing code. Open Connect Phone on your Mac."
                    codes.isNotEmpty() -> message = "QR found. Hold steady while the camera adjusts."
                }
            })
        setContent { HoppairTheme { ScannerScreen() } }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            message = "Allow camera access in Settings and reopen this scanner, or use a pairing link."
            return
        }
        controller.initializationFuture.addListener({
            if (destroyed) return@addListener
            try {
                controller.initializationFuture.get()
                controller.bindToLifecycle(this)
                observeCamera()
            } catch (_: Exception) {
                message = "Could not start the camera. Reopen the scanner or use a pairing link."
            }
        }, executor)
    }


    private fun observeCamera() {
        if (destroyed) return
        val info = controller.cameraInfo ?: return
        if (observedCamera === info) return
        observedCamera = info
        flashAvailable = info.hasFlashUnit()
        info.torchState.observe(this) { torch = it == androidx.camera.core.TorchState.ON }
        info.zoomState.observe(this) {
            zoom = it.zoomRatio
            maximumZoom = minOf(4f, it.maxZoomRatio)
        }
        info.cameraState.observe(this) {
            ready = it.type == CameraState.Type.OPEN && it.error == null
            if (it.error != null) message = "Camera unavailable. Close other camera apps or use a pairing link."
            else if (ready) message = guidance
        }
    }

    private fun setZoom(ratio: Float): Boolean {
        if (!ready || destroyed || !ratio.isFinite()) return false
        val state = controller.cameraInfo?.zoomState?.value ?: return false
        controller.setZoomRatio(ratio.coerceIn(maxOf(1f, state.minZoomRatio), minOf(4f, state.maxZoomRatio)))
        return true
    }

    @Composable private fun ScannerScreen() {
        if (showPrivacy) AlertDialog(onDismissRequest = { showPrivacy = false },
            title = { Text("Scanner privacy") },
            text = { Text("QR images and their contents are processed on your phone. Google ML Kit collects SDK performance and usage metrics. Its terms and privacy policy are available at developers.google.com/ml-kit/terms.") },
            confirmButton = { TextButton(onClick = { showPrivacy = false }) { Text("Done") } })
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(factory = { context -> PreviewView(context).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FIT_CENTER
                controller = this@QrScannerActivity.controller
                contentDescription = "Camera preview. Tap to focus; pinch to zoom."
                // Binding can finish before PreviewView attaches, leaving cameraInfo null.
                // Observe again once the preview has acquired the actual camera.
                previewStreamState.observe(this@QrScannerActivity) { observeCamera() }
            } }, modifier = Modifier.fillMaxSize())
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(color = Color.Black.copy(alpha = 0.8f), shape = RoundedCornerShape(20.dp)) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Scan to connect", color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = { delivered = true; finish() }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Close, "Close scanner", tint = Color.White) }
                    }
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val guide = minOf(300.dp, maxWidth, maxHeight - 24.dp)
                    if (guide >= 80.dp) Box(Modifier.size(guide)
                        .border(2.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(24.dp)))
                }
                Surface(modifier = Modifier.weight(1f, fill = false), color = Color.Black.copy(alpha = 0.86f), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(message, color = Color.White, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            for (level in listOf(1f, 2f, 3f)) {
                                TextButton(onClick = { setZoom(level) }, enabled = ready && level <= maximumZoom,
                                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White, disabledContentColor = Color.Gray)) {
                                    Text("${level.toInt()}×", style = if (kotlin.math.abs(zoom - level) < 0.15f) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium)
                                }
                            }
                            if (flashAvailable) IconButton(onClick = {
                                val next = !torch
                                controller.enableTorch(next).addListener({ if (!destroyed) torch = controller.cameraInfo?.torchState?.value == androidx.camera.core.TorchState.ON }, ContextCompat.getMainExecutor(this@QrScannerActivity))
                            }, enabled = ready, modifier = Modifier.size(48.dp)) {
                                Icon(if (torch) Icons.Outlined.FlashlightOff else Icons.Outlined.FlashlightOn, if (torch) "Turn flashlight off" else "Turn flashlight on", tint = Color.White)
                            }
                        }
                        TextButton(onClick = { delivered = true; setResult(RESULT_OK, Intent().putExtra(USE_LINK, true)); finish() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) { Text("Use a pairing link instead") }
                        TextButton(onClick = { showPrivacy = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = Color.LightGray)) { Text("Scanner privacy") }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        destroyed = true
        controller.clearImageAnalysisAnalyzer()
        controller.unbind()
        scanner.close()
        super.onDestroy()
    }
}

internal fun isPairingCode(value: String): Boolean = runCatching { Pairing.parse(value); true }.getOrDefault(false)
