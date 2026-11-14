package org.uacrf.connections;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ProviderCollectorTests {
  @Test
  void shippedCollectorsNeverFabricateDataAndAreNotAcceptedYet() {
    List<ProviderCollector> collectors = List.of(new AwsCollector(), new AzureCollector(), new GcpCollector());

    assertThat(collectors).extracting(ProviderCollector::provider)
        .containsExactly(
            ConnectionsRequests.Provider.AWS, ConnectionsRequests.Provider.AZURE, ConnectionsRequests.Provider.GCP);
    for (ProviderCollector collector : collectors) {
      ProviderCollector.Batch batch =
          collector.collect(new ProviderCollector.Request("conn", "src", null, null, null));
      assertThat(batch.status()).isEqualTo(ProviderCollector.Batch.Status.NOT_IMPLEMENTED);
      assertThat(batch.observations()).isEmpty();
    }
  }
}
