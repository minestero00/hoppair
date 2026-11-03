package dev.macdroid.app

import org.java_websocket.drafts.Draft_6455
import org.java_websocket.enums.Role
import org.java_websocket.exceptions.InvalidDataException
import org.java_websocket.exceptions.InvalidHandshakeException
import org.java_websocket.exceptions.LimitExceededException
import org.java_websocket.framing.BinaryFrame
import org.java_websocket.framing.ContinuousFrame
import org.java_websocket.framing.FramedataImpl1
import org.java_websocket.framing.TextFrame
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class BoundedRelayDraftTest {
    private fun fragment(first: Boolean, size: Int): FramedataImpl1 = (if (first) BinaryFrame() else ContinuousFrame()).apply {
        setPayload(ByteBuffer.allocate(size)); setFin(false)
    }
    @Test fun advertisedOversizeFrameIsRejectedWithoutReceivingItsPayload() {
        assertEquals(MAX_FRAME, BoundedRelayDraft.MAX_FRAME)
        val header = ByteBuffer.allocate(10).put(0x82.toByte()).put(127.toByte()).putLong(MAX_FRAME + 1L).apply { flip() }
        assertThrows(LimitExceededException::class.java) { BoundedRelayDraft().translateFrame(header) }
    }
    @Test fun retainedFragmentBytesAndCountStayBounded() {
        val bytes = BoundedRelayDraft()
        bytes.processFrame(null, fragment(true, MAX_FRAME - 1))
        assertThrows(LimitExceededException::class.java) { bytes.processFrame(null, fragment(false, 2)) }
        val count = BoundedRelayDraft()
        count.processFrame(null, fragment(true, 0))
        repeat(BoundedRelayDraft.MAX_FRAGMENTS - 1) { count.processFrame(null, fragment(false, 0)) }
        assertThrows(LimitExceededException::class.java) { count.processFrame(null, fragment(false, 0)) }
        assertTrue(count.copyInstance() is BoundedRelayDraft)
        (count.copyInstance() as Draft_6455).processFrame(null, fragment(true, 1))
    }
    @Test fun textAndCompressedFramesAreRejected() {
        val draft = BoundedRelayDraft()
        val text = TextFrame().apply { setPayload(ByteBuffer.wrap(byteArrayOf(65))); setFin(true) }
        assertThrows(InvalidDataException::class.java) { draft.processFrame(null, text) }
        assertThrows(InvalidDataException::class.java) { draft.translateFrame(ByteBuffer.wrap(byteArrayOf(0xc2.toByte(), 1, 0))) }
    }
    @Test fun handshakeBoundPreservesCoalescedFollowingFrameBytes() {
        val draft = BoundedRelayDraft().apply { setParseMode(Role.CLIENT) }
        val prefix = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n".toByteArray(Charsets.US_ASCII)
        val coalesced = ByteBuffer.allocate(prefix.size + 20_000).put(prefix).put(ByteArray(20_000)).apply { flip() }
        draft.translateHandshake(coalesced)
        assertEquals(prefix.size, coalesced.position()); assertEquals(20_000, coalesced.remaining())
        val header = ("HTTP/1.1 101 Switching Protocols\r\nX: " + "a".repeat(20_000)).toByteArray(Charsets.US_ASCII)
        assertThrows(InvalidHandshakeException::class.java) { draft.translateHandshake(ByteBuffer.wrap(header)) }
        val oversizedPreferred = ByteBuffer.allocate(20_000).put('H'.code.toByte()).apply { flip() }
        assertThrows(InvalidHandshakeException::class.java) { draft.translateHandshake(oversizedPreferred) }
    }
}
