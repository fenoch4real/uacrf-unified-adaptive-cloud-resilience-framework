package org.uacrf.objectives;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ObjectiveImportSampleParserTests {
  private static final String VALID = """
      {"metric":"m","target":0.123456789012345678901,"unit":"seconds","window":"w",
       "measurement_ref":"sample:check","comparison":"AT_MOST","measurement_kind":"PERCENTILE",
       "percentile":99.9}
      """;

  @Test
  void parsesBundledSampleAndPreservesExactMeaningDuringConversion() throws Exception {
    var fixture = JsonMapper.builder().build().readTree(
        Files.readString(Path.of("../../sample-data/objectives/sample-objective-definitions.json")));
    var definition = ObjectiveImportSampleParser.parse(fixture.get("definitions").get(0).get("payload").stringValue());
    var normalized = definition.convert("minutes", "stable-30m", false);
    assertThat(normalized.target()).isEqualByComparingTo("12");
    assertThat(normalized.metric()).isEqualTo("recovery.time");
    assertThat(normalized.measurementRef()).isEqualTo("sample:independent-order-check");
    assertThat(normalized.measurementKind()).isEqualTo(ObjectiveImportDefinition.MeasurementKind.RECOVERY_TIME);
    assertThat(normalized.comparison()).isEqualTo(ObjectiveImportDefinition.Comparison.AT_MOST);
    assertThat(normalized.percentile()).isNull();
    assertThat(ObjectiveImportSampleParser.parse(VALID).target())
        .isEqualByComparingTo("0.123456789012345678901");
  }

  @Test
  void rejectsAmbiguousMalformedAndUnsupportedDefinitions() {
    for (String invalid : new String[] {
        VALID.replace("\"metric\":\"m\"", "\"metric\":\"m\",\"metric\":\"other\""),
        VALID + "{}",
        VALID.replace("\"metric\":\"m\",", ""),
        VALID.replace("\"metric\":\"m\"", "\"metric\":\"m\",\"extra\":1"),
        VALID.replace("0.123456789012345678901", "\"0.1\""),
        VALID.replace("0.123456789012345678901", "1e1000"),
        VALID.replace("AT_MOST", "LESS_THAN"),
        VALID.replace("99.9", "null"),
        VALID.replace("PERCENTILE", "THRESHOLD"),
        "[]", "x".repeat(16_385)
    }) {
      assertThatThrownBy(() -> ObjectiveImportSampleParser.parse(invalid))
          .as("invalid input: %s", invalid.substring(0, Math.min(150, invalid.length())))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
