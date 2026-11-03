package dev.macdroid.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Synthetic public fixtures exercise the real bundled decoder, not camera hardware. */
@RunWith(AndroidJUnit4::class)
class QrScannerTest {
    private val link = "hoppair://192.168.100.100:49271?key=BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc&relay=wss%3A%2F%2Fmacdroid-relay.macdroid-relay.workers.dev"
    private val invite = "https://hoppair-connect.macdroid-relay.workers.dev/connect/#pair=" +
        Base64.encodeToString(link.toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun qr(value: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 4))
        return Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).apply {
            val pixels = IntArray(width * height) { index -> if (matrix[index % width, index / width]) Color.BLACK else Color.WHITE }
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }
    private fun decode(bitmap: Bitmap, rotation: Int = 0): List<String> {
        val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
        return try { Tasks.await(scanner.process(InputImage.fromBitmap(bitmap, rotation)), 20, TimeUnit.SECONDS).mapNotNull { it.rawValue } }
        finally { scanner.close() }
    }
    @Test fun denseInviteAtSmallAndLargeSizes() {
        assertTrue(isPairingCode(invite))
        for (size in listOf(256, 384, 720)) assertEquals("QR size $size", listOf(invite), decode(qr(invite, size)))
    }
    @Test fun rotatedInvite() {
        for (rotation in listOf(90, 180, 270)) assertEquals(listOf(invite), decode(qr(invite, 384), rotation))
    }
    @Test fun offCenterInCameraSizedFrame() {
        val frame = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        Canvas(frame).apply { drawColor(Color.LTGRAY); drawBitmap(qr(invite, 320), 740f, 90f, Paint()) }
        assertEquals(listOf(invite), decode(frame))
    }
    @Test fun unrelatedCodeIsRecognizedButNotAccepted() {
        val value = "https://example.com/"
        assertEquals(listOf(value), decode(qr(value, 300)))
        assertFalse(isPairingCode(value))
        assertFalse(isPairingCode("hoppair://localhost:49271?key=invalid"))
    }
}
