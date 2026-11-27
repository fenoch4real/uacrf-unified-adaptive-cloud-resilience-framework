package io.uacrf.discovery.adapter;

import io.uacrf.discovery.api.DiscoveryDtos.DiscoveredEdgeDto;
import io.uacrf.discovery.api.DiscoveryDtos.ObservationDto;

import java.util.List;
import java.util.UUID;

/**
 * Pluggable ingestion SPI for cloud inventory, distributed tracing, and synthetic lab fixtures.
 * Real cloud adapters (AWS, GCP, Azure, OCI, OTel) implement this interface without coupling S03 core
 * persistence or S04 qualification rules to provider SDKs.
 */
public interface TelemetryAdapter {

    String providerKey();

    String telemetrySource();

    AdapterBatch collectSnapshot(UUID tenantId, UUID environmentId, String scenarioKey);

    record AdapterBatch(
            List<ObservationDto> observations,
            List<DiscoveredEdgeDto> edges
    ) {
    }
}
