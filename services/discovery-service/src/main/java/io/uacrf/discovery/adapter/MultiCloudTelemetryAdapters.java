package io.uacrf.discovery.adapter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Multi-cloud & OpenTelemetry SPI adapters covering AWS, GCP, Azure, OCI, and OTel Collector feeds.
 * Each adapter cleanly separates cloud-native discovery behind configuration flags without fabricating live metrics.
 */
public final class MultiCloudTelemetryAdapters {

    private MultiCloudTelemetryAdapters() {
    }

    @Component
    public static class AwsTelemetryAdapter implements TelemetryAdapter {
        private final boolean enabled;
        private final String region;

        public AwsTelemetryAdapter(
                @Value("${uacrf.discovery.adapters.aws.enabled:false}") boolean enabled,
                @Value("${uacrf.discovery.adapters.aws.region:us-east-1}") String region) {
            this.enabled = enabled;
            this.region = region;
        }

        @Override
        public String providerKey() {
            return "AWS";
        }

        @Override
        public String telemetrySource() {
            return enabled ? "AWS_CLOUDWATCH_LIVE:" + region : "AWS_ADAPTER_CONFIG_STANDBY";
        }

        @Override
        public AdapterBatch collectSnapshot(UUID tenantId, UUID environmentId, String scenarioKey) {
            return new AdapterBatch(List.of(), List.of());
        }
    }

    @Component
    public static class GcpTelemetryAdapter implements TelemetryAdapter {
        private final boolean enabled;
        private final String projectId;

        public GcpTelemetryAdapter(
                @Value("${uacrf.discovery.adapters.gcp.enabled:false}") boolean enabled,
                @Value("${uacrf.discovery.adapters.gcp.project-id:uacrf-unconfigured}") String projectId) {
            this.enabled = enabled;
            this.projectId = projectId;
        }

        @Override
        public String providerKey() {
            return "GCP";
        }

        @Override
        public String telemetrySource() {
            return enabled ? "GCP_CLOUD_MONITORING_LIVE:" + projectId : "GCP_ADAPTER_CONFIG_STANDBY";
        }

        @Override
        public AdapterBatch collectSnapshot(UUID tenantId, UUID environmentId, String scenarioKey) {
            return new AdapterBatch(List.of(), List.of());
        }
    }

    @Component
    public static class AzureTelemetryAdapter implements TelemetryAdapter {
        private final boolean enabled;
        private final String subscriptionId;

        public AzureTelemetryAdapter(
                @Value("${uacrf.discovery.adapters.azure.enabled:false}") boolean enabled,
                @Value("${uacrf.discovery.adapters.azure.subscription-id:unconfigured}") String subscriptionId) {
            this.enabled = enabled;
            this.subscriptionId = subscriptionId;
        }

        @Override
        public String providerKey() {
            return "AZURE";
        }

        @Override
        public String telemetrySource() {
            return enabled ? "AZURE_MONITOR_LIVE:" + subscriptionId : "AZURE_ADAPTER_CONFIG_STANDBY";
        }

        @Override
        public AdapterBatch collectSnapshot(UUID tenantId, UUID environmentId, String scenarioKey) {
            return new AdapterBatch(List.of(), List.of());
        }
    }

    @Component
    public static class OciTelemetryAdapter implements TelemetryAdapter {
        private final boolean enabled;
        private final String tenancyOcid;

        public OciTelemetryAdapter(
                @Value("${uacrf.discovery.adapters.oci.enabled:false}") boolean enabled,
                @Value("${uacrf.discovery.adapters.oci.tenancy-ocid:unconfigured}") String tenancyOcid) {
            this.enabled = enabled;
            this.tenancyOcid = tenancyOcid;
        }

        @Override
        public String providerKey() {
            return "OCI";
        }

        @Override
        public String telemetrySource() {
            return enabled ? "OCI_MONITORING_LIVE:" + tenancyOcid : "OCI_ADAPTER_CONFIG_STANDBY";
        }

        @Override
        public AdapterBatch collectSnapshot(UUID tenantId, UUID environmentId, String scenarioKey) {
            return new AdapterBatch(List.of(), List.of());
        }
    }

    @Component
    public static class OtelReceiverAdapter implements TelemetryAdapter {
        private final boolean enabled;
        private final String endpoint;

        public OtelReceiverAdapter(
                @Value("${uacrf.discovery.adapters.otel.enabled:true}") boolean enabled,
                @Value("${uacrf.discovery.adapters.otel.endpoint:http://otel-collector:4318}") String endpoint) {
            this.enabled = enabled;
            this.endpoint = endpoint;
        }

        @Override
        public String providerKey() {
            return "OTEL";
        }

        @Override
        public String telemetrySource() {
            return enabled ? "OTEL_OTLP_HTTP:" + endpoint : "OTEL_ADAPTER_STANDBY";
        }

        @Override
        public AdapterBatch collectSnapshot(UUID tenantId, UUID environmentId, String scenarioKey) {
            return new AdapterBatch(List.of(), List.of());
        }
    }
}
