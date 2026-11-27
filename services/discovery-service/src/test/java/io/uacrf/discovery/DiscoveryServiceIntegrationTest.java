package io.uacrf.discovery;

import io.uacrf.discovery.api.DiscoveryDtos.SampleScenarioRequest;
import io.uacrf.discovery.api.DiscoveryDtos.TelemetryIngestRequest;
import io.uacrf.discovery.service.DiscoveryIngestionService;
import io.uacrf.discovery.service.PiiPhiScrubber;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class DiscoveryServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID ENV_ID = UUID.fromString("22222222-2222-7222-8222-222222222222");

    @Autowired
    private DiscoveryIngestionService service;

    @Autowired
    private PiiPhiScrubber scrubber;

    @Test
    void listsSeededConnectionsAndLoadsSampleLocalMultiCloudAndAiTelemetry() {
        var connections = service.listConnections(TENANT_ID, ENV_ID);
        assertEquals(6, connections.size());
        assertTrue(connections.stream().anyMatch(c -> "AZURE".equals(c.provider()) && "ATTENTION_NEEDED".equals(c.status())));
        assertTrue(connections.stream().anyMatch(c -> "OCI".equals(c.provider()) && "CONNECTED".equals(c.status())));

        var result = service.loadSampleLocalScenario(new SampleScenarioRequest(TENANT_ID, ENV_ID, "checkout-v43"));
        assertEquals("SAMPLE_LOCAL", result.telemetrySource());
        assertEquals("SAMPLE_ONLY", result.provenanceClass());
        assertEquals(6, result.observationsCreated());
        assertEquals(12, result.edgesDiscovered());

        var edges = service.listDiscoveredEdges(TENANT_ID, ENV_ID);
        assertEquals(12, edges.size());
        assertTrue(edges.stream().anyMatch(e -> "UNCERTAIN".equals(e.edgeState()) && "Payments gateway".equals(e.targetDisplay())));
        assertTrue(edges.stream().anyMatch(e -> "USES_FEATURES_FROM".equals(e.relationshipType())));
    }

    @Test
    void registersAllMultiCloudAdaptersAndScrubsPiiSecretsBeforeStorage() {
        var adapters = service.listRegisteredAdapters();
        var keys = adapters.stream().map(m -> m.get("providerKey")).collect(java.util.stream.Collectors.toSet());
        assertTrue(keys.containsAll(java.util.Set.of("AWS", "GCP", "AZURE", "OCI", "OTEL", "MULTI_CLOUD_SAMPLE")));

        var scrubbed = scrubber.scrubText("user=alice@example.com ssn=123-45-6789 token=Bearer secret123");
        assertEquals(3, scrubbed.redactionsApplied());
        assertFalse(scrubbed.sanitizedText().contains("alice@example.com"));
        assertFalse(scrubbed.sanitizedText().contains("123-45-6789"));
        assertFalse(scrubbed.sanitizedText().contains("secret123"));

        var obs = service.ingestObservation(new TelemetryIngestRequest(
                TENANT_ID, ENV_ID, null, "urn:uacrf:svc:checkout:alice@example.com",
                "OCI", "LOG", "auth_failure", 1.0, "count", 0.0, "HIGH",
                "OCI_MONITORING_LIVE", Map.of("patientSsn", "123-45-6789")
        ));
        assertFalse(obs.subjectCanonicalId().contains("alice@example.com"));
        assertTrue(obs.subjectCanonicalId().contains("[REDACTED_PII_EMAIL]"));
    }
}
