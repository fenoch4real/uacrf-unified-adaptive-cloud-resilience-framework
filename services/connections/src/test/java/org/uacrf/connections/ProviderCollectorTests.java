package org.uacrf.connections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ProviderCollectorTests {
  @Test
  void awsRejectsUnknownSourceReferences() {
    AwsCollector aws =
        new AwsCollector(
            (region, roleArn, externalId) -> {
              throw new AssertionError("Invalid source references must not open AWS clients.");
            },
            new ObjectMapper());
    assertThatThrownBy(
            () ->
                aws.collect(
                    new ProviderCollector.Request(
                        "conn", "src", "identity-ref", "uacrf-test", null, 0, null, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("aws/<region>/config/inventory");
  }
}
