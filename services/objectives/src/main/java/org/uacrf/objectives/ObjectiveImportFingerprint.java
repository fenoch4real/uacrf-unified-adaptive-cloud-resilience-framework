package org.uacrf.objectives;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class ObjectiveImportFingerprint {
  static final String VERSION = "OBJECTIVE_DEFINITION_EXACT_DECIMAL_V1";
  private static final char[] HEX = "0123456789abcdef".toCharArray();

  private ObjectiveImportFingerprint() {}

  record Value(String version, String digest) {
    Value {
      if (!VERSION.equals(version) || digest == null || !digest.matches("[a-f0-9]{64}")) {
        throw new IllegalArgumentException("Expected a supported version and lowercase SHA-256 digest.");
      }
    }
  }

  static Value fingerprint(ObjectiveImportDefinition definition) {
    return new Value(VERSION, digest(definition));
  }

  static String digest(ObjectiveImportDefinition definition) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(canonical(definition).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable.", error);
    }
  }

  static String canonical(ObjectiveImportDefinition definition) {
    if (definition == null) throw new IllegalArgumentException("Definition is required.");
    return "{\"comparison\":" + text(definition.comparison().name())
        + ",\"measurement_kind\":" + text(definition.measurementKind().name())
        + ",\"measurement_ref\":" + text(definition.measurementRef())
        + ",\"metric\":" + text(definition.metric())
        + ",\"percentile\":" + (definition.percentile() == null ? "null" : number(definition.percentile()))
        + ",\"target\":" + number(definition.target())
        + ",\"unit\":" + text(definition.unit())
        + ",\"window\":" + text(definition.window()) + "}";
  }

  static String text(String value) {
    StringBuilder result = new StringBuilder(value.length() + 2).append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (Character.isHighSurrogate(c)) {
        if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
          throw new IllegalArgumentException("Definition contains invalid Unicode.");
        }
        result.append(c).append(value.charAt(++i));
      } else if (Character.isLowSurrogate(c)) {
        throw new IllegalArgumentException("Definition contains invalid Unicode.");
      } else {
        switch (c) {
          case '"' -> result.append("\\\"");
          case '\\' -> result.append("\\\\");
          case '\b' -> result.append("\\b");
          case '\t' -> result.append("\\t");
          case '\n' -> result.append("\\n");
          case '\f' -> result.append("\\f");
          case '\r' -> result.append("\\r");
          default -> {
            if (c < 0x20) {
              result.append("\\u00").append(HEX[c >> 4]).append(HEX[c & 15]);
            } else {
              result.append(c);
            }
          }
        }
      }
    }
    return result.append('"').toString();
  }

  private static String number(BigDecimal value) {
    if (value.precision() > 100 || value.scale() < -100 || value.scale() > 100) {
      throw new IllegalArgumentException("Definition numeric field exceeds supported precision.");
    }
    return value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
  }
}
