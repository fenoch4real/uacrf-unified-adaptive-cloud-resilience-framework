package org.uacrf.connections;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;

final class ProviderRetryAt {
  private ProviderRetryAt() {}

  static Instant fromHeaders(Map<String, String> headers, Instant now) {
    String retryAfterMs = header(headers, "x-ms-retry-after-ms");
    if (retryAfterMs != null) {
      try {
        long millis = Long.parseLong(retryAfterMs);
        if (millis > 0) {
          return now.plusMillis(millis);
        }
      } catch (NumberFormatException ignored) {
        // Fall through to the standard Retry-After header.
      }
    }
    String retryAfter = header(headers, "retry-after");
    if (retryAfter == null) {
      return null;
    }
    try {
      long seconds = Long.parseLong(retryAfter);
      return seconds > 0 ? now.plusSeconds(seconds) : null;
    } catch (NumberFormatException ignored) {
      try {
        return ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME)
            .toInstant();
      } catch (DateTimeParseException ignoredDate) {
        return null;
      }
    }
  }

  private static String header(Map<String, String> headers, String name) {
    return headers.entrySet().stream()
        .filter(entry -> entry.getKey().toLowerCase(Locale.ROOT).equals(name))
        .map(Map.Entry::getValue)
        .findFirst()
        .orElse(null);
  }
}
