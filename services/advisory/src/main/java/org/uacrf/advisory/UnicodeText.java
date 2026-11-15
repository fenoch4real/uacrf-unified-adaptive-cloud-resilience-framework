package org.uacrf.advisory;

final class UnicodeText {
  private UnicodeText() {}

  static boolean isValid(String value) {
    for (int i = 0; i < value.length(); i++) {
      char current = value.charAt(i);
      if (Character.isHighSurrogate(current)) {
        if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i))) return false;
      } else if (Character.isLowSurrogate(current)) {
        return false;
      }
    }
    return true;
  }
}
