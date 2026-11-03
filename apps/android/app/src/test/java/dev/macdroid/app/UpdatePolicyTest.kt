package dev.macdroid.app

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException

class UpdatePolicyTest {
    private val content = "A disposable signed-update fixture".toByteArray()
    private fun release() = AndroidRelease("0.1.6", 7,
        "https://github.com/minestero00/hoppair/releases/download/v0.1.6/Hoppair-0.1.6-android.apk", sha256(content), content.size.toLong(), 28)

    @Test fun onlyNewerCompatibleVersionsAndExactRepositoryAssetsAreAccepted() {
        val valid = release()
        UpdatePolicy.validate(valid, 6, 36)
        UpdatePolicy.validate(valid.copy(tag = "0.1.7", url = valid.url.replace("/v0.1.6/", "/v0.1.7/")), 6, 36)
        for (invalid in listOf(valid.copy(versionCode = 6), valid.copy(versionCode = 0), valid.copy(minSdk = 37),
            valid.copy(size = MAX_UPDATE_BYTES + 1), valid.copy(size = 0), valid.copy(sha256 = "a".repeat(63)),
            valid.copy(version = "0.1.6/other"), valid.copy(url = valid.url.replace("github.com", "github.com.evil.example")),
            valid.copy(url = valid.url + "?download=1"), valid.copy(url = valid.url.replace("minestero00", "other")),
            valid.copy(url = valid.url.replace("https:", "http:")), valid.copy(url = valid.url.replace("Hoppair-0.1.6", "Hoppair-0.1.5")))) {
            assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.validate(invalid, 6, 36) }
        }
        assertTrue(UpdatePolicy.redirectAllowed("https://release-assets.githubusercontent.com/fixture?signature=disposable"))
        assertFalse(UpdatePolicy.redirectAllowed("http://release-assets.githubusercontent.com/fixture"))
        assertFalse(UpdatePolicy.redirectAllowed("https://release-assets.githubusercontent.com.evil.example/fixture"))
        assertFalse(UpdatePolicy.redirectAllowed("https://user@release-assets.githubusercontent.com/fixture"))
    }
    @Test fun streamedBytesMustMatchExactSizeAndHashAndSupportCancellation() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256("abc".toByteArray()))
        val valid = release()
        val output = ByteArrayOutputStream()
        copyVerifiedUpdate(ByteArrayInputStream(content), output, valid)
        assertArrayEquals(content, output.toByteArray())
        assertThrows(IllegalArgumentException::class.java) { copyVerifiedUpdate(ByteArrayInputStream(content.dropLast(1).toByteArray()), ByteArrayOutputStream(), valid) }
        val tooLong = ByteArrayInputStream(content + byteArrayOf(0, 1, 2))
        assertThrows(IllegalArgumentException::class.java) { copyVerifiedUpdate(tooLong, ByteArrayOutputStream(), valid) }
        assertEquals("Read at most one extra byte", 2, tooLong.available())
        assertThrows(IllegalArgumentException::class.java) { copyVerifiedUpdate(ByteArrayInputStream(content), ByteArrayOutputStream(), valid.copy(sha256 = "0".repeat(64))) }
        assertThrows(CancellationException::class.java) { copyVerifiedUpdate(ByteArrayInputStream(content), ByteArrayOutputStream(), valid, checkCancelled = { throw CancellationException() }) }
    }
    @Test fun packageVersionMinimumSdkAndEveryCurrentSignerMustMatch() {
        val current = UpdateArchive("dev.macdroid.app", 6, "0.1.5", 28, setOf("fixture-certificate"))
        val next = current.copy(versionCode = 7, versionName = "0.1.6")
        UpdatePolicy.verifyIdentity(next, current, release(), 36)
        for (invalid in listOf(next.copy(packageName = "other.app"), next.copy(versionCode = 8), next.copy(versionName = "other"),
            next.copy(minSdk = 29), next.copy(signers = emptySet()), next.copy(signers = setOf("other-certificate")),
            next.copy(signers = current.signers + "extra-certificate"))) {
            assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.verifyIdentity(invalid, current, release(), 36) }
        }
    }
    @Test fun cleanupOnlyRemovesOwnedPartialsInstalledAndStaleApks() {
        val root = Files.createTempDirectory("hoppair-updater-test").toFile()
        try {
            val cache = UpdateCache(root)
            val partial = cache.partial().apply { writeText("partial") }
            val installed = cache.ready(release().copy(versionCode = 6)).apply { writeText("installed") }
            val future = cache.ready(release()).apply { writeText("future") }
            val stale = cache.ready(release().copy(versionCode = 8)).apply { writeText("stale"); setLastModified(1) }
            val unrelated = File(cache.directory, "personal.apk").apply { writeText("unrelated") }
            val outside = File(root, "outside.apk").apply { writeText("outside") }
            cache.prune(6)
            assertFalse(partial.exists()); assertFalse(installed.exists()); assertFalse(stale.exists())
            assertTrue(future.exists()); assertTrue(unrelated.exists())
            cache.delete(outside)
            assertTrue(outside.exists())
        } finally { root.deleteRecursively() }
    }
}
