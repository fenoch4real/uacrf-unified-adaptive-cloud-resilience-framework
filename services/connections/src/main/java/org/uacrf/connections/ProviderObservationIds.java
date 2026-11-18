package org.uacrf.connections;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class ProviderObservationIds {
  private ProviderObservationIds() {}

  static String create(String prefix, String sourceRef, long sequence) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest((sourceRef + ":" + sequence).getBytes(StandardCharsets.UTF_8));
      return prefix + HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is required by the runtime.", error);
    }
  }
}
