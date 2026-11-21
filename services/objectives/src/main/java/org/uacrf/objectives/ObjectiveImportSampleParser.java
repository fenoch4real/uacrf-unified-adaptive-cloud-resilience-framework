package org.uacrf.objectives;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

final class ObjectiveImportSampleParser {
  private static final int MAX_BYTES = 16_384;
  private static final Set<String> FIELDS = Set.of(
      "metric", "target", "unit", "window", "measurement_ref", "comparison",
      "measurement_kind", "percentile");
  private static final ObjectMapper JSON = JsonMapper.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
      .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
      .build();

  private ObjectiveImportSampleParser() {}

  static ObjectiveImportDefinition parse(String payload) {
    if (payload == null || payload.isBlank() || payload.length() > MAX_BYTES
        || payload.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
      throw new IllegalArgumentException("Sample objective payload exceeds supported bounds.");
    }
    JsonNode root;
    try {
      root = JSON.readTree(payload);
    } catch (JacksonException error) {
      throw new IllegalArgumentException("Sample objective payload is not valid JSON.", error);
    }
    if (root == null || !root.isObject() || root.size() != FIELDS.size()
        || FIELDS.stream().anyMatch(field -> !root.has(field))) {
      throw new IllegalArgumentException("Sample objective payload has unexpected or missing fields.");
    }
    String comparison = text(root, "comparison");
    String measurementKind = text(root, "measurement_kind");
    ObjectiveImportDefinition.Comparison parsedComparison;
    ObjectiveImportDefinition.MeasurementKind parsedKind;
    try {
      parsedComparison = ObjectiveImportDefinition.Comparison.valueOf(comparison);
      parsedKind = ObjectiveImportDefinition.MeasurementKind.valueOf(measurementKind);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("Sample objective has unsupported measurement semantics.", error);
    }
    JsonNode percentile = root.get("percentile");
    return new ObjectiveImportDefinition(
        text(root, "metric"), number(root.get("target")), text(root, "unit"),
        text(root, "window"), text(root, "measurement_ref"), parsedComparison, parsedKind,
        percentile.isNull() ? null : number(percentile));
  }

  private static String text(JsonNode root, String field) {
    JsonNode value = root.get(field);
    if (value == null || !value.isString()) {
      throw new IllegalArgumentException("Sample objective field must be text: " + field);
    }
    return value.stringValue();
  }

  private static BigDecimal number(JsonNode node) {
    if (node == null || !node.isNumber()) {
      throw new IllegalArgumentException("Sample objective numeric field must be a number.");
    }
    BigDecimal value = node.decimalValue();
    if (value.precision() > 100 || value.scale() < -100 || value.scale() > 100) {
      throw new IllegalArgumentException("Sample objective numeric field exceeds supported precision.");
    }
    return value;
  }
}
