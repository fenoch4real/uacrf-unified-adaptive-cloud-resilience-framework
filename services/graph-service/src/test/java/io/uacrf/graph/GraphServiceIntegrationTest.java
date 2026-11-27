package io.uacrf.graph;

import io.uacrf.graph.api.GraphDtos.ProjectEdgeInput;
import io.uacrf.graph.api.GraphDtos.ProjectEdgesRequest;
import io.uacrf.graph.service.KnowledgeGraphService;
import io.uacrf.graph.service.Neo4jGraphGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class GraphServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static Neo4jContainer<?> neo4j = new Neo4jContainer<>("neo4j:5.26")
            .withoutAuthentication();

    @DynamicPropertySource
    static void registerNeo4jProps(DynamicPropertyRegistry registry) {
        registry.add("uacrf.graph.neo4j.enabled", () -> "true");
        registry.add("uacrf.graph.neo4j.uri", neo4j::getBoltUrl);
        registry.add("uacrf.graph.neo4j.username", () -> "neo4j");
        registry.add("uacrf.graph.neo4j.password", () -> "");
    }

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID ENV_ID = UUID.fromString("22222222-2222-7222-8222-222222222222");

    @Autowired
    private KnowledgeGraphService graphService;

    @Autowired
    private Neo4jGraphGateway neo4jGateway;

    @Test
    void enforcesOntologyAndProjectsBoundedDependenciesAndAiLineageIntoRealNeo4j526() {
        // Invalid ontology label outside canonical 58 must be rejected
        assertThrows(IllegalArgumentException.class, () ->
                graphService.projectEdges(new ProjectEdgesRequest(TENANT_ID, ENV_ID, List.of(
                        new ProjectEdgeInput("urn:a", "BogusNonCanonicalLabel", "A", "DEPENDS_ON",
                                "urn:b", "Service", "B", "MANDATORY", "CONFIRMED", "test", true)
                ))));

        // Valid canonical batch projects cleanly to PostgreSQL and real Neo4j 5.26
        var revision = graphService.projectEdges(new ProjectEdgesRequest(TENANT_ID, ENV_ID, List.of(
                new ProjectEdgeInput("urn:uacrf:svc:checkout", "Service", "Checkout", "DEPENDS_ON",
                        "urn:uacrf:svc:payment", "Service", "Payment", "MANDATORY", "CONFIRMED", "Distributed trace", false),
                new ProjectEdgeInput("urn:uacrf:svc:payment", "Service", "Payment", "CALLS",
                        "urn:uacrf:agent:fraud-endpoint", "ModelEndpoint", "Fraud endpoint", "DEGRADABLE", "CONFIRMED", "AI gateway trace", true)
        )));

        assertNotNull(revision.revisionDigest());
        assertTrue(neo4jGateway.countProjectedNodesInNeo4j(TENANT_ID, ENV_ID) >= 3);

        // Uncertain edges remain visible when requested and hidden when toggled off
        var withUncertain = graphService.getBoundedDependencies(TENANT_ID, ENV_ID, "urn:uacrf:svc:checkout", 2, true);
        assertTrue(withUncertain.edges().stream().anyMatch(e -> "UNCERTAIN".equals(e.edgeState())));
        assertEquals("Dependency shows reliance. It does not prove the cause of this incident.", withUncertain.nonCausationNotice());

        var withoutUncertain = graphService.getBoundedDependencies(TENANT_ID, ENV_ID, "urn:uacrf:svc:checkout", 2, false);
        assertFalse(withoutUncertain.edges().stream().anyMatch(e -> "UNCERTAIN".equals(e.edgeState())));

        // AI Lineage view returns protected AI endpoint, ModelVersion, FeatureStore, ModelFallback
        var aiLineage = graphService.getAiLineage(TENANT_ID, ENV_ID, "urn:uacrf:svc:payment");
        assertTrue(aiLineage.nodes().stream().anyMatch(n -> "ModelEndpoint".equals(n.nodeLabel())));
        assertTrue(aiLineage.edges().stream().anyMatch(e -> "FALLBACK_TO".equals(e.relationshipType())));
    }
}
