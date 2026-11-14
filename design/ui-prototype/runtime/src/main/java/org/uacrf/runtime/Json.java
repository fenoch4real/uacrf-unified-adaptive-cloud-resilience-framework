package org.uacrf.runtime;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

final class Json {
  static final ObjectMapper M =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  static JsonNode read(String text) {
    try {
      return M.readTree(text);
    } catch (Exception e) {
      throw new Problem(400, "Malformed JSON");
    }
  }

  static String text(Object value) {
    try {
      return M.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("JSON serialization failed");
    }
  }

  static String hash(String canonicalMaterial) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonicalMaterial.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private Json() {}
}
