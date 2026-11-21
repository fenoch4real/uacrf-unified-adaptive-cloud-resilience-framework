package org.uacrf.objectives;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ObjectiveImportFingerprintTests {
  @Test
  void matchesPublishedFictionalFixture() throws Exception {
    var fixture = JsonMapper.builder().build().readTree(Files.readString(
        Path.of("../../design/ui-prototype/import_signal_design/SCHEMA_FIXTURES.json")))
        .path("fixtures").path("objective-import-candidate");
    var definition = ObjectiveImportSampleParser.parse(fixture.path("normalized_definition").toString());
    assertThat(ObjectiveImportFingerprint.digest(definition))
        .isEqualTo(fixture.path("normalized_definition_digest").asString());
  }

  @Test
  void preservesExactValuesAndMeasurementSemantics() {
    var a = definition(new BigDecimal("0.123456789012345678901234567890"), "m\"étric");
    var b = definition(new BigDecimal("0.12345678901234567890123456789"), "m\"étric");
    var changed = definition(new BigDecimal("0.123456789012345678901234567891"), "m\"étric");
    assertThat(ObjectiveImportFingerprint.digest(a)).isEqualTo(ObjectiveImportFingerprint.digest(b))
        .isNotEqualTo(ObjectiveImportFingerprint.digest(changed));
    assertThat(ObjectiveImportFingerprint.canonical(a)).contains("\"metric\":\"m\\\"étric\"")
        .contains("\"target\":0.12345678901234567890123456789");
    assertThat(ObjectiveImportFingerprint.canonical(definition(BigDecimal.ZERO, "a\n\u0001\\😀")))
        .contains("\"metric\":\"a\\n\\u0001\\\\😀\"").contains("\"target\":0");
    assertThat(ObjectiveImportFingerprint.digest(definition(new BigDecimal("1E+2"), "m")))
        .isEqualTo(ObjectiveImportFingerprint.digest(definition(new BigDecimal("100.00"), "m")));
    assertThat(ObjectiveImportFingerprint.digest(definition(BigDecimal.ONE, "m")))
        .isNotEqualTo(ObjectiveImportFingerprint.digest(new ObjectiveImportDefinition(
            "m", BigDecimal.ONE, "seconds", "window", "ref",
            ObjectiveImportDefinition.Comparison.AT_LEAST,
            ObjectiveImportDefinition.MeasurementKind.RECOVERY_TIME, null)));
  }

  @Test
  void rejectsUnboundedDecimalsAndInvalidUnicode() {
    assertThatThrownBy(() -> ObjectiveImportFingerprint.digest(definition(new BigDecimal("1E+101"), "m")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ObjectiveImportFingerprint.digest(definition(BigDecimal.ONE, "m\uD800")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static ObjectiveImportDefinition definition(BigDecimal target, String metric) {
    return new ObjectiveImportDefinition(metric, target, "seconds", "window", "ref",
        ObjectiveImportDefinition.Comparison.AT_MOST,
        ObjectiveImportDefinition.MeasurementKind.RECOVERY_TIME, null);
  }
}
