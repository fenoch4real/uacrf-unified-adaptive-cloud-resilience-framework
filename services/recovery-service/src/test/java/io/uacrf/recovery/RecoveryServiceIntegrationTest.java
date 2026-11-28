package io.uacrf.recovery;

import io.uacrf.contracts.authority.BoundedRecoveryAuthorization;
import io.uacrf.recovery.api.RecoveryDomainException;
import io.uacrf.recovery.api.RecoveryDtos.ExecuteRecoveryRequest;
import io.uacrf.recovery.api.RecoveryDtos.UpdateKillSwitchRequest;
import io.uacrf.recovery.api.RecoveryDtos.UpdateLiveExecutionPolicyRequest;
import io.uacrf.recovery.service.RecoveryOrchestrationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class RecoveryServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID ENV_ID = UUID.fromString("22222222-2222-7222-8222-222222222222");

    @Autowired
    private RecoveryOrchestrationService service;

    private BoundedRecoveryAuthorization validAuth(String nonce) {
        return new BoundedRecoveryAuthorization(
                "1.0", UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                "K8S_ROLLBACK_DEPLOYMENT", List.of("urn:uacrf:svc:checkout"),
                TENANT_ID.toString(), ENV_ID.toString(), "rev-sample-local-0001", "sha256:sub",
                1, 100, List.of("DELETE_DATA"), "val-plan", "comp-plan",
                "APPROVAL_REQUIRED", List.of("33333333-3333-7333-8333-333333333303"),
                "k8s-rollback-adapter", nonce, Instant.now(), Instant.now().plus(5, ChronoUnit.MINUTES)
        );
    }

    @Test
    void enforcesP3TargetBoundsNonceReplayKillSwitchAndP6ValidationCompensation() {
        // 1. Target substitution rejected
        RecoveryDomainException targetEx = assertThrows(RecoveryDomainException.class, () ->
                service.executeRecovery(new ExecuteRecoveryRequest(
                        TENANT_ID, ENV_ID, validAuth("nonce-target-test"),
                        "urn:uacrf:svc:unauthorized-billing", "v42", "k8s-rollback-adapter", "Maya Chen", false
                )));
        assertEquals("TARGET_SUBSTITUTION_FORBIDDEN", targetEx.getCode());

        // 2. Valid execution passes 8-layer P6 validation DAG -> SUCCEEDED
        var auth1 = validAuth("nonce-exec-1");
        var succeeded = service.executeRecovery(new ExecuteRecoveryRequest(
                TENANT_ID, ENV_ID, auth1,
                "urn:uacrf:svc:checkout", "v42", "k8s-rollback-adapter", "Maya Chen", false
        ));
        assertEquals("SUCCEEDED", succeeded.workflowState());
        assertFalse(succeeded.compensated());
        assertEquals(8, succeeded.validationSteps().size());

        // 3. Replaying same nonce is blocked
        RecoveryDomainException replayEx = assertThrows(RecoveryDomainException.class, () ->
                service.executeRecovery(new ExecuteRecoveryRequest(
                        TENANT_ID, ENV_ID, auth1,
                        "urn:uacrf:svc:checkout", "v42", "k8s-rollback-adapter", "Maya Chen", false
                )));
        assertEquals("NONCE_REPLAY_DETECTED", replayEx.getCode());

        // 4. Mandatory P6 Journey Probe failure triggers automatic bounded compensation -> COMPENSATED
        var compExec = service.executeRecovery(new ExecuteRecoveryRequest(
                TENANT_ID, ENV_ID, validAuth("nonce-comp-2"),
                "urn:uacrf:svc:checkout", "v42", "k8s-rollback-adapter", "Maya Chen", true
        ));
        assertEquals("COMPENSATED", compExec.workflowState());
        assertTrue(compExec.compensated());

        // 5. Default live-execution policy is DISABLED (DRY_RUN_SIMULATED) & respects server lock
        var defaultPolicy = service.getLiveExecutionPolicy(TENANT_ID, ENV_ID);
        assertFalse(defaultPolicy.tenantToggleEnabled());
        assertFalse(defaultPolicy.serverConfigEnabled());
        assertEquals("DRY_RUN_SIMULATED", defaultPolicy.effectiveMode());
        assertTrue(succeeded.adapterOutput().startsWith("[DRY_RUN_SIMULATED]"));

        var updatedPolicy = service.setLiveExecutionPolicy(new UpdateLiveExecutionPolicyRequest(
                TENANT_ID, ENV_ID, true, "Requested live check", "Maya Chen"));
        assertTrue(updatedPolicy.tenantToggleEnabled());
        assertEquals("DRY_RUN_SIMULATED", updatedPolicy.effectiveMode());

        // 6. Operator kill switch fail-closed blocks execution
        service.setKillSwitch(new UpdateKillSwitchRequest(TENANT_ID, ENV_ID, true, "Incident freeze", "Aisha Patel"));
        RecoveryDomainException ksEx = assertThrows(RecoveryDomainException.class, () ->
                service.executeRecovery(new ExecuteRecoveryRequest(
                        TENANT_ID, ENV_ID, validAuth("nonce-ks-3"),
                        "urn:uacrf:svc:checkout", "v42", "k8s-rollback-adapter", "Maya Chen", false
                )));
        assertEquals("KILL_SWITCH_ACTIVE", ksEx.getCode());
    }
}
