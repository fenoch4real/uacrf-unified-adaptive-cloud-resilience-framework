package io.uacrf.discovery.adapter;

import io.uacrf.crypto.CanonicalJson;
import io.uacrf.discovery.api.DiscoveryDtos.DiscoveredEdgeDto;
import io.uacrf.discovery.api.DiscoveryDtos.ObservationDto;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Generates illustrative SAMPLE_LOCAL multi-cloud + protected agent telemetry and dependency edges
 * so every UI workflow and verification pipeline runs locally without production credentials.
 * All outputs carry telemetrySource=SAMPLE_LOCAL and never qualify as production evidence.
 */
@Component
public class SampleLocalScenarioGenerator implements TelemetryAdapter {

    public static final String SOURCE_TAG = "SAMPLE_LOCAL";

    @Override
    public String providerKey() {
        return "MULTI_CLOUD_SAMPLE";
    }

    @Override
    public String telemetrySource() {
        return SOURCE_TAG;
    }

    @Override
    public AdapterBatch collectSnapshot(UUID tenantId, UUID environmentId, String scenarioKey) {
        Instant now = Instant.now();

        List<ObservationDto> observations = List.of(
                obs(tenantId, environmentId, "urn:uacrf:svc:checkout", "GCP",
                        "DEPLOYMENT_CHANGE", "deployment.version", 43.0, "version", 42.0,
                        "WARNING", now.minus(4, ChronoUnit.MINUTES),
                        Map.of("fromVersion", "v42", "toVersion", "v43", "cluster", "gke-nonprod")),
                obs(tenantId, environmentId, "urn:uacrf:svc:checkout", "OTEL",
                        "ERROR_RATE", "http.server.error_rate", 18.4, "%", 2.0,
                        "CRITICAL", now.minus(20, ChronoUnit.SECONDS),
                        Map.of("previousRate", "1.2%", "successRate", "81.6%", "journey", "place-order")),
                obs(tenantId, environmentId, "urn:uacrf:svc:payment", "AWS",
                        "LATENCY_P95", "http.server.duration.p95", 145.0, "ms", 250.0,
                        "INFO", now.minus(45, ChronoUnit.SECONDS),
                        Map.of("region", "us-east-1", "runtime", "eks")),
                obs(tenantId, environmentId, "urn:uacrf:agent:fraud-endpoint", "AZURE",
                        "AI_AVAILABILITY", "agent.endpoint.availability", 99.7, "%", 99.5,
                        "INFO", now.minus(2, ChronoUnit.MINUTES),
                        Map.of("modelVersion", "v17", "purpose", "fraud_scoring", "provenance", "SAMPLE_ONLY")),
                obs(tenantId, environmentId, "urn:uacrf:agent:fraud-endpoint", "AZURE",
                        "AI_LATENCY_P95", "agent.endpoint.latency.p95", 220.0, "ms", 300.0,
                        "INFO", now.minus(2, ChronoUnit.MINUTES),
                        Map.of("modelVersion", "v17")),
                obs(tenantId, environmentId, "urn:uacrf:feature-store:fraud-features", "AZURE",
                        "FEATURE_FRESHNESS", "feature_store.staleness_minutes", 18.0, "min", 5.0,
                        "WARNING", now.minus(18, ChronoUnit.MINUTES),
                        Map.of("affectedSource", "Fraud scoring diagnostics", "note", "Diagnostics delayed"))
        );

        List<DiscoveredEdgeDto> edges = List.of(
                edge(tenantId, environmentId, "urn:uacrf:step:start-checkout", "JourneyStep", "Start Checkout",
                        "PART_OF_JOURNEY", "urn:uacrf:journey:place-order", "CustomerJourney", "Place Order",
                        "MANDATORY", "CONFIRMED", "Catalog / Journey mapping", true, now),
                edge(tenantId, environmentId, "urn:uacrf:step:create-order", "JourneyStep", "Create Order",
                        "PART_OF_JOURNEY", "urn:uacrf:journey:place-order", "CustomerJourney", "Place Order",
                        "MANDATORY", "CONFIRMED", "Catalog / Journey mapping", true, now),
                edge(tenantId, environmentId, "urn:uacrf:svc:checkout", "Service", "Checkout",
                        "SUPPORTS_STEP", "urn:uacrf:step:start-checkout", "JourneyStep", "Start Checkout",
                        "MANDATORY", "CONFIRMED", "Distributed trace", true, now),
                edge(tenantId, environmentId, "urn:uacrf:svc:order-service", "Service", "Order service",
                        "SUPPORTS_STEP", "urn:uacrf:step:create-order", "JourneyStep", "Create Order",
                        "MANDATORY", "CONFIRMED", "Distributed trace", true, now),
                edge(tenantId, environmentId, "urn:uacrf:svc:checkout", "Service", "Checkout",
                        "DEPENDS_ON", "urn:uacrf:svc:payment", "Service", "Payment",
                        "MANDATORY", "CONFIRMED", "Distributed trace", false, now),
                edge(tenantId, environmentId, "urn:uacrf:svc:checkout", "Service", "Checkout",
                        "DEPENDS_ON", "urn:uacrf:svc:cache-service", "Service", "Cache service (GCP)",
                        "DEGRADABLE", "CONFIRMED", "GCP Service Mesh", true, now),
                edge(tenantId, environmentId, "urn:uacrf:svc:payment", "Service", "Payment",
                        "DEPENDS_ON", "urn:uacrf:external:fraud-api", "ExternalProvider", "Fraud API (Azure)",
                        "MANDATORY", "CONFIRMED", "Distributed trace", true, now),
                edge(tenantId, environmentId, "urn:uacrf:svc:checkout", "Service", "Checkout",
                        "DEPENDS_ON", "urn:uacrf:external:payments-gateway", "ExternalProvider", "Payments gateway",
                        "OPTIONAL", "UNCERTAIN", "Observed trace spike", false, now),
                // AI / Agent Workload Dependency Lineage (Appendix X & Screenshot 7)
                edge(tenantId, environmentId, "urn:uacrf:svc:payment", "Service", "Payment",
                        "CALLS", "urn:uacrf:agent:fraud-endpoint", "ModelEndpoint", "Fraud endpoint",
                        "DEGRADABLE", "CONFIRMED", "AI gateway trace", true, now),
                edge(tenantId, environmentId, "urn:uacrf:agent:fraud-endpoint", "ModelEndpoint", "Fraud endpoint",
                        "SERVES_MODEL", "urn:uacrf:model:fraud-v17", "ModelVersion", "Model v17",
                        "MANDATORY", "CONFIRMED", "Model registry", true, now),
                edge(tenantId, environmentId, "urn:uacrf:agent:fraud-endpoint", "ModelEndpoint", "Fraud endpoint",
                        "USES_FEATURES_FROM", "urn:uacrf:feature-store:fraud-features", "FeatureStore", "Feature store",
                        "MANDATORY", "CONFIRMED", "Feature client telemetry", true, now),
                edge(tenantId, environmentId, "urn:uacrf:svc:payment", "Service", "Payment",
                        "FALLBACK_TO", "urn:uacrf:fallback:rules-based", "ModelFallback", "Rules-based fallback",
                        "OPTIONAL", "UNCERTAIN", "Architecture candidate (untested)", false, now)
        );

        return new AdapterBatch(observations, edges);
    }

    private ObservationDto obs(UUID tenantId, UUID environmentId, String subjectCanonicalId, String provider,
                               String signalType, String metricName, Double value, String unit, Double threshold,
                               String severity, Instant occurredAt, Map<String, Object> attrs) {
        String digest = CanonicalJson.digestObject(Map.of(
                "subject", subjectCanonicalId,
                "signalType", signalType,
                "metricName", metricName,
                "value", value,
                "occurredAt", occurredAt.toString(),
                "attrs", attrs
        ));
        return new ObservationDto(
                UUID.randomUUID(),
                tenantId,
                environmentId,
                null,
                subjectCanonicalId,
                provider,
                signalType,
                metricName,
                value,
                unit,
                threshold,
                severity,
                SOURCE_TAG,
                digest,
                "sample://uacrf-raw/" + digest.substring(0, 16),
                occurredAt,
                Instant.now()
        );
    }

    private DiscoveredEdgeDto edge(UUID tenantId, UUID environmentId,
                                   String srcId, String srcLabel, String srcDisplay,
                                   String relType,
                                   String tgtId, String tgtLabel, String tgtDisplay,
                                   String importance, String edgeState, String obsSource,
                                   boolean ownerConfirmed, Instant now) {
        return new DiscoveredEdgeDto(
                UUID.randomUUID(), tenantId, environmentId,
                srcId, srcLabel, srcDisplay,
                relType,
                tgtId, tgtLabel, tgtDisplay,
                importance, edgeState, obsSource,
                ownerConfirmed, SOURCE_TAG, now.minus(20, ChronoUnit.SECONDS)
        );
    }
}
