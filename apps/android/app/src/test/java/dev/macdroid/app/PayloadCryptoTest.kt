package dev.macdroid.app

import org.junit.Assert.*
import org.junit.Test

class PayloadCryptoTest {
    @Test fun opensSameFixedVectorAsSwiftAndRejectsTampering() {
        val key = ByteArray(32)
        val encrypted = ByteArray(12) + "cea7403d4d606b6e074ec5d3baf39d18d0d1c8a799996bf0265b98b5d48ab919".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertArrayEquals(ByteArray(16), PayloadCrypto.open(encrypted, key))
        val plain = "Mac and Android 📎".toByteArray()
        val sealed = PayloadCrypto.seal(plain, key)
        assertArrayEquals(plain, PayloadCrypto.open(sealed, key))
        sealed[13] = (sealed[13].toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { PayloadCrypto.open(sealed, key) }
    }
}
