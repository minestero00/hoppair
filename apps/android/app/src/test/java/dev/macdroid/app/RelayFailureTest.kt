package dev.macdroid.app

import org.junit.Assert.*
import org.junit.Test

class RelayFailureTest {
    @Test fun handshakeFailuresAreActionableAndNeverExposeRemoteText() {
        assertTrue(RelayFailure.message(401).contains("registration"))
        assertTrue(RelayFailure.message(429).contains("reconnect limit"))
        assertTrue(RelayFailure.message(503).contains("Mac is not available"))
        assertFalse(RelayFailure.message(101, 1008, "Session ended").contains("transfer limit"))
        assertTrue(RelayFailure.message(101, 1008, "Transfer limit reached").contains("transfer limit"))
        assertFalse(RelayFailure.message(101, 1008, "SECRET remote reason").contains("SECRET"))
        assertFalse(RelayFailure.message(error = java.io.IOException("SECRET URL")).contains("SECRET"))
    }
}
