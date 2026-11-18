package org.uacrf.connections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class InventoryNormalizerTests {
  private final InventoryNormalizer normalizer = new InventoryNormalizer(new ObjectMapper());

  @Test
  void mapsProviderFieldsWithoutInventingGcpLocation() {
    var aws = normalizer.normalize(
        "SAMPLE_LOCAL", "obs-aws", "sample:aws/config", "aws.config.resources",
        "{\"resourceId\":\"vm-1\",\"resourceType\":\"AWS::EC2::Instance\",\"awsRegion\":\"eu-west-1\"}",
        "digest", "2026-11-18T00:00:00Z");
    var azure = normalizer.normalize(
        "SAMPLE_LOCAL", "obs-azure", "sample:azure/resourcegraph", "azure.resourcegraph.rows",
        "{\"id\":\"/subscriptions/s/resourceGroups/g/providers/Microsoft.Compute/virtualMachines/vm\","
            + "\"type\":\"microsoft.compute/virtualmachines\",\"location\":\"westeurope\"}",
        "digest", "2026-11-18T00:00:00Z");
    var gcp = normalizer.normalize(
        "SAMPLE_LOCAL", "obs-gcp", "sample:gcp/europe-west1/assetinventory",
        "gcp.assetinventory.assets",
        "{\"name\":\"//compute.googleapis.com/projects/p/global/networks/n\","
            + "\"assetType\":\"compute.googleapis.com/Network\"}",
        "digest", "2026-11-18T00:00:00Z");

    assertThat(aws.provider()).isEqualTo("AWS");
    assertThat(aws.nativeId()).isEqualTo("vm-1");
    assertThat(aws.nativeType()).isEqualTo("AWS::EC2::Instance");
    assertThat(aws.location()).isEqualTo("eu-west-1");
    assertThat(azure.provider()).isEqualTo("AZURE");
    assertThat(azure.location()).isEqualTo("westeurope");
    assertThat(gcp.provider()).isEqualTo("GCP");
    assertThat(gcp.location()).isNull();
    assertThat(gcp.sourceClass()).isEqualTo("SAMPLE_LOCAL");
    assertThat(gcp.rawObservationId()).isEqualTo("obs-gcp");
    assertThat(normalizer.normalize(
            "AWS", "obs", "source", "aws.config.resources",
            "{\"resourceId\":\"vm-1\",\"resourceType\":\"type\"}", "digest", "time").location())
        .isNull();
  }

  @Test
  void rejectsMalformedMissingAndProviderMismatchedData() {
    assertThatThrownBy(() -> normalizer.normalize(
            "AWS", "obs", "source", "gcp.assetinventory.assets",
            "{\"name\":\"asset\",\"assetType\":\"type\"}", "digest", "time"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> normalizer.normalize(
            "SAMPLE_LOCAL", "obs", "source", "gcp.assetinventory.assets",
            "{\"name\":\"asset\"}", "digest", "time"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> normalizer.normalize(
            "SAMPLE_LOCAL", "obs", "source", "gcp.assetinventory.assets",
            "not json", "digest", "time"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> normalizer.normalize(
            "SAMPLE_LOCAL", "obs", "source", "azure.resourcegraph.rows",
            "{\"id\":\"asset\",\"type\":\"type\",\"location\":42}", "digest", "time"))
        .isInstanceOf(IllegalStateException.class);
  }
}
