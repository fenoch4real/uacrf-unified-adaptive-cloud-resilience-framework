package io.uacrf.objectives;

import io.uacrf.objectives.api.ObjectivesDtos.EvaluateRiskRequest;
import io.uacrf.objectives.service.ObjectivesControlService;
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
class ObjectivesServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID ENV_ID = UUID.fromString("22222222-2222-7222-8222-222222222222");

    @Autowired
    private ObjectivesControlService service;

    @Test
    void evaluatesMultiLayerRulesIntoRiskStatesAndFailClosedUnknownOnStaleTelemetry() {
        var baseline = service.listRiskEvaluations(TENANT_ID, ENV_ID);
        assertFalse(baseline.isEmpty());
        assertEquals("ELEVATED", baseline.getFirst().riskState());

        // Security anomaly + severe drop -> CRITICAL
        var critical = service.evaluateRisk(new EvaluateRiskRequest(
                TENANT_ID, ENV_ID, "urn:uacrf:svc:checkout", "Checkout service", "Place Order", "Maya Chen",
                70.2, 88.0, true, false, false
        ));
        assertEquals("CRITICAL", critical.riskState());
        assertTrue(critical.triggeredRules().stream().anyMatch(r -> "SEC-AUTH-001".equals(r.ruleId())));

        // Stale telemetry -> UNKNOWN (never defaults to NORMAL)
        var unknown = service.evaluateRisk(new EvaluateRiskRequest(
                TENANT_ID, ENV_ID, "urn:uacrf:svc:checkout", "Checkout service", "Place Order", "Maya Chen",
                99.9, 10.0, false, false, true
        ));
        assertEquals("UNKNOWN", unknown.riskState());
    }
}
