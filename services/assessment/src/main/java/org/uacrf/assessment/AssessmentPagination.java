package org.uacrf.assessment;

final class AssessmentPagination {
  private AssessmentPagination() {}

  static boolean valid(int limit, String after) {
    return limit >= 1 && limit <= 100
        && (after == null || after.matches(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"));
  }
}
