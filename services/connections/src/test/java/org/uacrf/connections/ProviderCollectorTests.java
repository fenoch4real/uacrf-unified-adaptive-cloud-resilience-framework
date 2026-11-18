package org.uacrf.connections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ProviderCollectorTests {
  @Test
  void cloudStubsNeverFabricateDataAndAwsRejectsUnknownSourceReferences() {
    List<ProviderCollector> collectors = List.of(new AzureCollector(), new GcpCollector());

    assertThat(collectors)
        .extracting(ProviderCollector::provider)
        .containsExactly(
            ConnectionsRequests.Provider.AZURE, ConnectionsRequests.Provider.GCP);
    for (ProviderCollector collector : collectors) {
      ProviderCollector.Batch batch =
          collector.collect(
              new ProviderCollector.Request(
                  "conn", "src", "identity-ref", null, null, 0, null, null));
      assertThat(batch.status()).isEqualTo(ProviderCollector.Batch.Status.NOT_IMPLEMENTED);
      assertThat(batch.observations()).isEmpty();
    }

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
