package org.uacrf.advisory;

final class InputPacket {
  static final int MAX_BYTES = 1_048_576;

  private final byte[] bytes;
  private final String bytesDigest;

  InputPacket(byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
      throw new IllegalArgumentException("The input packet must contain 1 to 1048576 bytes.");
    }
    this.bytes = bytes.clone();
    bytesDigest = Sha256.hex(this.bytes);
  }

  byte[] bytes() {
    return bytes.clone();
  }

  String bytesDigest() {
    return bytesDigest;
  }
}
