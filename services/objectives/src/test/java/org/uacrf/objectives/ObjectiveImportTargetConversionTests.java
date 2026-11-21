package org.uacrf.objectives;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ObjectiveImportTargetConversionTests {
  @Test
  void performsExactApprovedConversions() {
    assertThat(convert("720", "seconds", "minutes", "stable-30m", "stable-30m", false))
        .isEqualByComparingTo("12");
    assertThat(convert("1.5", "seconds", "minutes", "x", "x", false))
        .isEqualByComparingTo("0.025");
    assertThat(convert("12", "minutes", "seconds", "stable-30m", "stable-30m", false))
        .isEqualByComparingTo("720");
    assertThat(convert("0.98", "fraction", "percent", "100-tasks", "100-tasks", true))
        .isEqualByComparingTo("98");
    assertThat(convert("99.5", "percent", "percent", "30-days", "30-days", false))
        .isEqualByComparingTo("99.5");
  }

  @Test
  void rejectsRoundingUnsupportedMappingsAndIncompatibleWindows() {
    assertThatThrownBy(() -> convert("61", "seconds", "minutes", "x", "x", false))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("rounding");
    assertThatThrownBy(() -> convert("0.98", "fraction", "percent", "x", "x", false))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unapproved");
    assertThatThrownBy(() -> convert("1", "hours", "minutes", "x", "x", true))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unapproved");
    assertThatThrownBy(() -> convert("10", "minutes", "minutes", "30-days", "calendar-month", false))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("windows");
    assertThatThrownBy(() -> convert("10", "minutes", "minutes", "", "", false))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("windows");
  }

  @Test
  void rejectsInvalidTargetsAndPercentages() {
    assertThatThrownBy(() -> ObjectiveImportTargetConversion.convert(null, "minutes", "minutes", "x", "x", false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> convert("-1", "minutes", "minutes", "x", "x", false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> convert("1.01", "fraction", "percent", "x", "x", true))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Fraction");
    assertThatThrownBy(() -> convert("101", "percent", "percent", "x", "x", false))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Percent");
  }

  private BigDecimal convert(String value, String sourceUnit, String targetUnit,
      String sourceWindow, String targetWindow, boolean allowFractionToPercent) {
    return ObjectiveImportTargetConversion.convert(new BigDecimal(value), sourceUnit, targetUnit,
        sourceWindow, targetWindow, allowFractionToPercent);
  }
}
