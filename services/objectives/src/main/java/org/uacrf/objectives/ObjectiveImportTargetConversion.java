package org.uacrf.objectives;

import java.math.BigDecimal;

final class ObjectiveImportTargetConversion {
  private static final BigDecimal SIXTY = BigDecimal.valueOf(60);
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private ObjectiveImportTargetConversion() {}

  static BigDecimal convert(
      BigDecimal value, String sourceUnit, String targetUnit,
      String sourceWindow, String targetWindow, boolean allowFractionToPercent) {
    if (value == null || value.signum() < 0) {
      throw new IllegalArgumentException("Invalid source target.");
    }
    if (sourceUnit == null || sourceUnit.isBlank() || targetUnit == null || targetUnit.isBlank()
        || sourceWindow == null || sourceWindow.isBlank() || !sourceWindow.equals(targetWindow)) {
      throw new IllegalArgumentException("Units and compatible measurement windows are required.");
    }

    BigDecimal result;
    if (sourceUnit.equals(targetUnit)) {
      result = value;
    } else if (sourceUnit.equals("seconds") && targetUnit.equals("minutes")) {
      try {
        result = value.divide(SIXTY);
      } catch (ArithmeticException error) {
        throw new IllegalArgumentException("Conversion requires an approved rounding policy.", error);
      }
    } else if (sourceUnit.equals("minutes") && targetUnit.equals("seconds")) {
      result = value.multiply(SIXTY);
    } else if (sourceUnit.equals("fraction") && targetUnit.equals("percent")
        && allowFractionToPercent) {
      if (value.compareTo(BigDecimal.ONE) > 0) {
        throw new IllegalArgumentException("Fraction exceeds one.");
      }
      result = value.multiply(HUNDRED);
    } else {
      throw new IllegalArgumentException("Unapproved unit mapping.");
    }
    if (targetUnit.equals("percent") && result.compareTo(HUNDRED) > 0) {
      throw new IllegalArgumentException("Percent exceeds one hundred.");
    }
    return result;
  }
}
