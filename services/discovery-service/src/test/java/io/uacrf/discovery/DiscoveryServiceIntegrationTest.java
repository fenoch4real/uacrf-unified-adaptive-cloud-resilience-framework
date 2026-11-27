package io.uacrf.discovery;

import io.uacrf.discovery.api.DiscoveryDtos.SampleScenarioRequest;
import io.uacrf.discovery.service.DiscoveryIngestionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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

    @Test
    void listsSeededConnectionsAndLoadsSampleLocalMultiCloudAndAiTelemetry() {
        var connections = service.listConnections(TENANT_ID, ENV_ID);
        assertEquals(5, connections.size());
        assertTrue(connections.stream().anyMatch(c -> "AZURE".equals(c.provider()) && "ATTENTION_NEEDED".equals(c.status())));

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
}
