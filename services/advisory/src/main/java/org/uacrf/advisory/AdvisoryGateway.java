package org.uacrf.advisory;

import java.time.Duration;
import java.util.Objects;

interface AdvisoryGateway {
  Outcome invoke(Request request);

  record Request(
      String attemptId,
      String model,
      String context,
      int maxOutputTokens,
      Duration timeout) {
    public Request {
      requireText(attemptId, "attemptId");
      requireText(model, "model");
      requireText(context, "context");
      Objects.requireNonNull(timeout, "timeout");
      if (maxOutputTokens < 1 || timeout.isZero() || timeout.isNegative()) {
        throw new IllegalArgumentException("Output limit and timeout must be positive.");
      }
    }

    private static void requireText(String value, String name) {
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException(name + " is required.");
      }
    }
  }

  sealed interface Outcome permits Response, Unknown {}

  record Response(String text, int inputTokens, int outputTokens) implements Outcome {
    public Response {
      if (text == null || text.isBlank() || inputTokens < 0 || outputTokens < 0) {
        throw new IllegalArgumentException("A response must contain text and non-negative usage.");
      }
    }
  }

  record Unknown(String reason) implements Outcome {
    public Unknown {
      if (reason == null || reason.isBlank()) {
        throw new IllegalArgumentException("An unknown outcome requires a reason.");
      }
    }
  }
}
