package org.uacrf.objectives;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ObjectiveImportDefinitionTests {
  @Test
  void conversionPreservesMeasurementMeaningAndDoesNotMutateSource() {
    var source = new ObjectiveImportDefinition("recovery.time", new BigDecimal("720"), "seconds",
        "stable-30m", "sample:independent-order-check",
        ObjectiveImportDefinition.Comparison.AT_MOST,
        ObjectiveImportDefinition.MeasurementKind.RECOVERY_TIME, null);
    var converted = source.convert("minutes", "stable-30m", false);
    assertThat(converted.target()).isEqualByComparingTo("12");
    assertThat(converted.unit()).isEqualTo("minutes");
    assertThat(converted.metric()).isEqualTo(source.metric());
    assertThat(converted.window()).isEqualTo(source.window());
    assertThat(converted.measurementRef()).isEqualTo(source.measurementRef());
    assertThat(converted.comparison()).isEqualTo(source.comparison());
    assertThat(converted.measurementKind()).isEqualTo(source.measurementKind());
    assertThat(converted.percentile()).isNull();
    assertThat(source.target()).isEqualByComparingTo("720");
    assertThatThrownBy(() -> source.convert("minutes", "calendar-month", false))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void enforcesPercentileAndTargetBounds() {
    var percentile = new ObjectiveImportDefinition("latency", BigDecimal.ONE, "seconds",
        "five-minutes", "sample:metric", ObjectiveImportDefinition.Comparison.AT_MOST,
        ObjectiveImportDefinition.MeasurementKind.PERCENTILE, new BigDecimal("99.9"));
    assertThat(percentile.convert("seconds", "five-minutes", false).percentile()).isEqualByComparingTo("99.9");
    assertThatThrownBy(() -> new ObjectiveImportDefinition("latency", BigDecimal.ONE, "seconds",
        "five-minutes", "sample:metric", ObjectiveImportDefinition.Comparison.AT_MOST,
        ObjectiveImportDefinition.MeasurementKind.PERCENTILE, null)).isInstanceOf(IllegalArgumentException.class);
    for (String invalid : new String[] {"0", "100.01"}) {
      assertThatThrownBy(() -> new ObjectiveImportDefinition("latency", BigDecimal.ONE, "seconds",
          "five-minutes", "sample:metric", ObjectiveImportDefinition.Comparison.AT_MOST,
          ObjectiveImportDefinition.MeasurementKind.PERCENTILE, new BigDecimal(invalid)))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> new ObjectiveImportDefinition("latency", BigDecimal.ONE, "seconds",
        "five-minutes", "sample:metric", ObjectiveImportDefinition.Comparison.AT_MOST,
        ObjectiveImportDefinition.MeasurementKind.THRESHOLD, BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ObjectiveImportDefinition("latency", new BigDecimal("101"), "percent",
        "five-minutes", "sample:metric", ObjectiveImportDefinition.Comparison.AT_MOST,
        ObjectiveImportDefinition.MeasurementKind.THRESHOLD, null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ObjectiveImportDefinition(" ", BigDecimal.ONE, "seconds",
        "five-minutes", "sample:metric", ObjectiveImportDefinition.Comparison.AT_MOST,
        ObjectiveImportDefinition.MeasurementKind.THRESHOLD, null)).isInstanceOf(IllegalArgumentException.class);
  }
}
