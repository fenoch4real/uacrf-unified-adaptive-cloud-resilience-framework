package org.uacrf.sampledata;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Deterministic JSON and digest helpers shared by the sample generators. */
final class Json {
  private static final JsonMapper COMPACT = JsonMapper.builder().build();
  private static final JsonMapper PRETTY =
      JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

  private Json() {}

  static String compact(Object value) {
    return COMPACT.writeValueAsString(value);
  }

  static String pretty(Object value) {
    return PRETTY.writeValueAsString(value) + "\n";
  }

  static String digest(String text) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Insertion-ordered map from alternating key/value arguments. */
  static Map<String, Object> m(Object... keyValues) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i < keyValues.length; i += 2) {
      map.put((String) keyValues[i], keyValues[i + 1]);
    }
    return map;
  }

  static List<Object> l(Object... values) {
    return new ArrayList<>(List.of(values));
  }

  static double round(double value, int places) {
    double scale = Math.pow(10, places);
    return Math.round(value * scale) / scale;
  }
}
