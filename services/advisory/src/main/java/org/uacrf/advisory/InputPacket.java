package org.uacrf.advisory;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class InputPacket {
  static final int MAX_BYTES = 1_048_576;

  private final byte[] bytes;
  private final String bytesDigest;

  InputPacket(byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
      throw new IllegalArgumentException("The input packet must contain 1 to 1048576 bytes.");
    }
    this.bytes = bytes.clone();
    try {
      bytesDigest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(this.bytes));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is required by the runtime.", error);
    }
  }

  byte[] bytes() {
    return bytes.clone();
  }

  String bytesDigest() {
    return bytesDigest;
  }
}
