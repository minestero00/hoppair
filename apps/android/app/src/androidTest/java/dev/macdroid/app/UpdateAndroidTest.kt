package dev.macdroid.app

import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateAndroidTest {
    @Test fun feedUsesAndroidVersionAndBatchTagSeparatelyAndRejectsMalformedVerificationFields() {
        fun feed() = JSONObject().put("version", "0.1.7").put("android", JSONObject().put("versionName", "0.1.6").put("versionCode", 7)
            .put("url", "https://github.com/minestero00/hoppair/releases/download/v0.1.7/Hoppair-0.1.6-android.apk")
            .put("sha256", "a".repeat(64)).put("size", 12345).put("minSdk", 28))
        val parsed = checkNotNull(UpdatePolicy.parse(feed(), 6, 36))
        assertEquals("0.1.6", parsed.version); assertEquals("0.1.7", parsed.tag)
        assertNull(UpdatePolicy.parse(feed(), 7, 36))
        val malformed = feed().also { it.getJSONObject("android").put("versionCode", 7.5) }
        assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.parse(malformed, 6, 36) }
        val missing = feed().also { it.getJSONObject("android").remove("sha256") }
        assertThrows(Exception::class.java) { UpdatePolicy.parse(missing, 6, 36) }
        val fallback = feed().also {
            it.getJSONObject("android").remove("versionName")
            it.getJSONObject("android").put("url", "https://github.com/minestero00/hoppair/releases/download/v0.1.7/Hoppair-0.1.7-android.apk")
        }
        assertEquals("0.1.7", checkNotNull(UpdatePolicy.parse(fallback, 6, 36)).version)
    }
    @Test fun packageManagerReadsInstalledArchiveCertificatesWithoutLaunchingInstaller() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.packageManager
        val installed = manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val archive = checkNotNull(manager.getPackageArchiveInfo(context.applicationInfo.sourceDir, PackageManager.GET_SIGNING_CERTIFICATES))
        assertEquals(context.packageName, archive.packageName)
        assertEquals(installed.longVersionCode, archive.longVersionCode)
        val expected = installed.signingInfo!!.apkContentsSigners.map { sha256(it.toByteArray()) }.toSet()
        val actual = archive.signingInfo!!.apkContentsSigners.map { sha256(it.toByteArray()) }.toSet()
        assertTrue(expected.isNotEmpty()); assertEquals(expected, actual)
        assertEquals(28, archive.applicationInfo!!.minSdkVersion)
    }
    @Test fun externalSharesCannotBorrowPrivateFileOrUpdaterProviderPrivileges() {
        val app = "dev.macdroid.app"
        for (uri in listOf("file:///data/data/$app/files/pairing.enc", "content://$app.files/updates/fixture.apk",
            "content://$app.files/shelf/fixture", "content://10@$app.files/camera/fixture", "https://example.com/file", "content:///file")) {
            assertFalse(uri, externalShareAllowed(Uri.parse(uri), app))
        }
        assertTrue(externalShareAllowed(Uri.parse("content://other.provider/document/fixture"), app))
    }
}
