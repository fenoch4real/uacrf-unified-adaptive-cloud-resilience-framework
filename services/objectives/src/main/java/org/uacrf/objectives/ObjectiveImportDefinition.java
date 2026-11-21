package org.uacrf.objectives;

import java.math.BigDecimal;

record ObjectiveImportDefinition(
    String metric, BigDecimal target, String unit, String window, String measurementRef,
    Comparison comparison, MeasurementKind measurementKind, BigDecimal percentile) {
  enum Comparison { AT_MOST, AT_LEAST, EQUAL }
  enum MeasurementKind { THRESHOLD, PERCENTILE, RATIO, RECOVERY_TIME, OUTCOME }

  ObjectiveImportDefinition {
    requireText(metric, 200);
    requireText(unit, 80);
    requireText(window, 1000);
    requireText(measurementRef, 1000);
    if (target == null || target.signum() < 0
        || ("percent".equals(unit) && target.compareTo(BigDecimal.valueOf(100)) > 0)
        || comparison == null || measurementKind == null) {
      throw new IllegalArgumentException("Invalid objective definition.");
    }
    if (measurementKind == MeasurementKind.PERCENTILE) {
      if (percentile == null || percentile.signum() <= 0
          || percentile.compareTo(BigDecimal.valueOf(100)) > 0) {
        throw new IllegalArgumentException("A valid percentile is required.");
      }
    } else if (percentile != null) {
      throw new IllegalArgumentException("Percentile is only valid for percentile measurements.");
    }
  }

  ObjectiveImportDefinition convert(String targetUnit, String targetWindow, boolean allowFractionToPercent) {
    BigDecimal converted = ObjectiveImportTargetConversion.convert(
        target, unit, targetUnit, window, targetWindow, allowFractionToPercent);
    return new ObjectiveImportDefinition(metric, converted, targetUnit, targetWindow,
        measurementRef, comparison, measurementKind, percentile);
  }

  private static void requireText(String value, int maxLength) {
    if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > maxLength) {
      throw new IllegalArgumentException("Invalid objective definition field.");
    }
  }
}
