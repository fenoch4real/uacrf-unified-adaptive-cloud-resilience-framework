package org.uacrf.advisory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AdvisoryGatewayTests {
  @Test
  void disabledGatewayRejectsInvocation() {
    AdvisoryGateway gateway = new DisabledAdvisoryGateway();
    var request = new AdvisoryGateway.Request("attempt-1", "model", "context", 100, Duration.ofSeconds(5));

    assertThrows(IllegalStateException.class, () -> gateway.invoke(request));
  }

  @Test
  void providerImplementationsCanReturnAnUnknownOutcomeWithoutInventingUsage() {
    AdvisoryGateway gateway = request -> new AdvisoryGateway.Unknown("Outcome could not be determined.");
    var request = new AdvisoryGateway.Request("attempt-1", "model", "context", 100, Duration.ofSeconds(5));

    assertEquals(new AdvisoryGateway.Unknown("Outcome could not be determined."), gateway.invoke(request));
  }

  @Test
  void requestsRejectMissingBounds() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new AdvisoryGateway.Request("attempt-1", "model", "context", 0, Duration.ofSeconds(5)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AdvisoryGateway.Request("attempt-1", "model", "context", 10, Duration.ZERO));
  }
}
