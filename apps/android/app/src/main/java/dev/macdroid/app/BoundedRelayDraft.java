package dev.macdroid.app;

import java.nio.ByteBuffer;
import java.util.Collections;
import org.java_websocket.WebSocketImpl;
import org.java_websocket.drafts.Draft;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.enums.Opcode;
import org.java_websocket.exceptions.IncompleteHandshakeException;
import org.java_websocket.exceptions.InvalidDataException;
import org.java_websocket.exceptions.InvalidHandshakeException;
import org.java_websocket.exceptions.LimitExceededException;
import org.java_websocket.framing.Framedata;
import org.java_websocket.handshake.Handshakedata;

/**
 * Uses the library parser with finite handshake, frame, aggregate, and fragment bounds.
 * The base parser bounds each allocation; processFrame bounds retained fragments before concatenation.
 * Peak parser memory also includes a bounded current frame/assembly and the fixed read batch.
 * Extensions are disabled so decompression cannot expand ciphertext beyond these limits.
 */
public final class BoundedRelayDraft extends Draft_6455 {
  static final int MAX_FRAME = 12 * 1024 * 1024;
  static final int MAX_HEADER = 16 * 1024;
  static final int MAX_FRAGMENTS = 1024;
  private long heldBytes;
  private int fragments;

  private final java.util.function.IntConsumer statusObserver;
  public BoundedRelayDraft() { this(status -> {}); }
  public BoundedRelayDraft(java.util.function.IntConsumer statusObserver) {
    super(Collections.emptyList(), MAX_FRAME);
    this.statusObserver = statusObserver;
  }
  @Override public Draft copyInstance() { return new BoundedRelayDraft(statusObserver); }

  @Override public Handshakedata translateHandshake(ByteBuffer input) throws InvalidHandshakeException {
    ByteBuffer limited = input.duplicate();
    limited.limit(limited.position() + Math.min(limited.remaining(), MAX_HEADER));
    // The upstream parser throws before returning non-101 responses. Capture
    // only the fixed HTTP status prefix, never a server reason/header/body.
    if (limited.remaining() >= 12) {
      byte[] prefix = new byte[12];
      limited.duplicate().get(prefix);
      if (prefix[0] == 'H' && prefix[1] == 'T' && prefix[2] == 'T' && prefix[3] == 'P'
          && prefix[4] == '/' && prefix[5] == '1' && prefix[6] == '.'
          && (prefix[7] == '0' || prefix[7] == '1') && prefix[8] == ' '
          && prefix[9] >= '1' && prefix[9] <= '5'
          && prefix[10] >= '0' && prefix[10] <= '9' && prefix[11] >= '0' && prefix[11] <= '9') {
        statusObserver.accept((prefix[9] - '0') * 100 + (prefix[10] - '0') * 10 + prefix[11] - '0');
      }
    }
    try {
      Handshakedata result = super.translateHandshake(limited);
      input.position(limited.position());
      return result;
    } catch (IncompleteHandshakeException error) {
      if (limited.limit() - input.position() >= MAX_HEADER || error.getPreferredSize() > MAX_HEADER) {
        throw new InvalidHandshakeException("Handshake exceeds size limit");
      }
      throw error;
    }
  }

  @Override public void processFrame(WebSocketImpl connection, Framedata frame) throws InvalidDataException {
    Opcode opcode = frame.getOpcode();
    if (opcode == Opcode.TEXT) throw new InvalidDataException(1003, "Binary messages required");
    if (opcode == Opcode.BINARY || opcode == Opcode.CONTINUOUS) {
      if (opcode == Opcode.BINARY) {
        if (fragments != 0) throw new InvalidDataException(1002, "Unfinished message");
        heldBytes = frame.getPayloadData().remaining();
        fragments = 1;
      } else {
        if (fragments == 0) throw new InvalidDataException(1002, "Unexpected continuation");
        heldBytes += frame.getPayloadData().remaining();
        fragments++;
      }
      if (heldBytes > MAX_FRAME || fragments > MAX_FRAGMENTS) throw new LimitExceededException(MAX_FRAME);
    }
    super.processFrame(connection, frame);
    if ((opcode == Opcode.BINARY || opcode == Opcode.CONTINUOUS) && frame.isFin()) {
      heldBytes = 0;
      fragments = 0;
    }
  }
  @Override public void reset() { super.reset(); heldBytes = 0; fragments = 0; }
}
