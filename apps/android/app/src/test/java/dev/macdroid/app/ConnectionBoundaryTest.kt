package dev.macdroid.app

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class ConnectionBoundaryTest {
    @Test fun cryptoRejectsWrongKeyLengthAndFramesBeforeDecryption() {
        assertThrows(IllegalArgumentException::class.java) { PayloadCrypto.seal(byteArrayOf(1), ByteArray(16)) }
        assertThrows(IllegalArgumentException::class.java) { PayloadCrypto.open(ByteArray(28), ByteArray(16)) }
        assertThrows(IllegalArgumentException::class.java) { PayloadCrypto.open(ByteArray(27), ByteArray(32)) }
        assertThrows(IllegalArgumentException::class.java) { PayloadCrypto.open(ByteArray(MAX_FRAME + 1), ByteArray(32)) }
        assertThrows(IllegalArgumentException::class.java) { PayloadCrypto.seal(ByteArray(MAX_FRAME - 27), ByteArray(32)) }
    }
    @Test fun boundedReadAllowsExactLimitAndConsumesAtMostOneExtraByte() {
        assertArrayEquals(byteArrayOf(1, 2, 3), ByteArrayInputStream(byteArrayOf(1, 2, 3)).readBytesBounded(3))
        var consumed = 0
        val endless = object : InputStream() { override fun read(): Int { consumed++; return 1 } }
        assertThrows(IllegalArgumentException::class.java) { endless.readBytesBounded(8) }
        assertEquals(9, consumed)
        val zeroRead = object : InputStream() {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
            override fun read(): Int = -1
        }
        assertTrue(zeroRead.readBytesBounded(8).isEmpty())
    }
}
