package io.uacrf.policy;

import io.uacrf.policy.api.PolicyDomainException;
import io.uacrf.policy.api.PolicyDtos.ApproveProposalRequest;
import io.uacrf.policy.api.PolicyDtos.IssueAuthorizationRequest;
import io.uacrf.policy.service.PolicyAuthorityService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class PolicyServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static GenericContainer<?> opa = new GenericContainer<>("openpolicyagent/opa:1.0.0")
            .withCopyFileToContainer(MountableFile.forHostPath("../../policies/baseline.rego"), "/policies/baseline.rego")
            .withCommand("run --server --addr=0.0.0.0:8181 /policies/baseline.rego")
            .withExposedPorts(8181)
            .waitingFor(Wait.forHttp("/health").forPort(8181));

    @DynamicPropertySource
    static void registerOpaProps(DynamicPropertyRegistry registry) {
        registry.add("uacrf.policy.opa.enabled", () -> "true");
        registry.add("uacrf.policy.opa.url",
                () -> "http://" + opa.getHost() + ":" + opa.getMappedPort(8181) + "/v1/data/uacrf/authz/decision");
    }

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID ENV_ID = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID PROPOSAL_ID = UUID.fromString("dddd1111-1111-7111-8111-111111111101");
    private static final UUID PROPOSER_ID = UUID.fromString("33333333-3333-7333-8333-333333333301");
    private static final UUID OWNER_ID = UUID.fromString("33333333-3333-7333-8333-333333333303");

    @Autowired
    private PolicyAuthorityService service;

    @Autowired
    private io.uacrf.policy.service.BreakGlassService breakGlassService;

    @Test
    void enforcesSeparationOfDutiesP2GraphDeltaInvalidationAndP3SingleUseNonce() {
        // Proposer cannot approve own proposal
        PolicyDomainException selfEx = assertThrows(PolicyDomainException.class, () ->
                service.approveProposal(PROPOSAL_ID, PROPOSER_ID,
                        new ApproveProposalRequest(TENANT_ID, ENV_ID, "Luis Romero", "APPROVE")));
        assertEquals("SELF_APPROVAL_FORBIDDEN", selfEx.getCode());

        // Distinct owner approves proposal
        var approved = service.approveProposal(PROPOSAL_ID, OWNER_ID,
                new ApproveProposalRequest(TENANT_ID, ENV_ID, "Maya Chen", "APPROVE"));
        assertEquals("APPROVED", approved.approvalStatus());

        // P2 Material Graph Delta: widened blast radius invalidates authorization issuance
        PolicyDomainException deltaEx = assertThrows(PolicyDomainException.class, () ->
                service.issueBoundedAuthorization(PROPOSAL_ID, new IssueAuthorizationRequest(
                        TENANT_ID, ENV_ID, "rev-sample-local-0002", "sha256:sub-widened",
                        3, false, "k8s-rollback-adapter"
                )));
        assertEquals("MATERIAL_GRAPH_DELTA_INVALIDATED", deltaEx.getCode());

        // Immaterial delta issues RS256 single-use BoundedRecoveryAuthorization
        var auth = service.issueBoundedAuthorization(PROPOSAL_ID, new IssueAuthorizationRequest(
                TENANT_ID, ENV_ID, "rev-sample-local-0001", "sha256:sub-checkout-v43",
                1, false, "k8s-rollback-adapter"
        ));
        assertEquals("IMMATERIAL", auth.deltaClassification());
        assertNotNull(auth.jwsToken());

        // Single-use nonce consumes cleanly once and rejects replay
        service.consumeNonce(auth.nonce());
        PolicyDomainException replayEx = assertThrows(PolicyDomainException.class, () ->
                service.consumeNonce(auth.nonce()));
        assertEquals("NONCE_REPLAY_OR_EXPIRED", replayEx.getCode());

        // Live OPA Container evaluates baseline.rego for PREAUTHORIZED nonprod pattern
        var opaProposal = service.createProposal(PROPOSER_ID, new io.uacrf.policy.api.PolicyDtos.CreateProposalRequest(
                TENANT_ID, ENV_ID, "urn:uacrf:svc:checkout", "K8S_ROLLBACK_DEPLOYMENT",
                "Rollback nonprod", "v42", true, 1, "nonproduction", "Luis Romero",
                "rev-sample-local-0001", "sha256:sub-checkout-v43", "QUALIFIED", "Evaluated by live OPA"
        ));
        assertEquals("PREAUTHORIZED", opaProposal.policyOutcome());
        assertTrue(opaProposal.policyReasons().contains("NONPROD_REVERSIBLE_CERTIFIED_PATTERN"));

        // FR-APR-002 Break-Glass requires MFA step-up & locks post-incident review
        PolicyDomainException mfaEx = assertThrows(PolicyDomainException.class, () ->
                breakGlassService.issueBreakGlass(new io.uacrf.policy.service.BreakGlassService.BreakGlassRequest(
                        TENANT_ID, ENV_ID, "urn:uacrf:svc:checkout", "K8S_ROLLBACK_DEPLOYMENT",
                        "Maya Chen", "Emergency Sev1 checkout outage", false, 30)));
        assertEquals("MFA_STEP_UP_REQUIRED", mfaEx.getCode());

        var bg = breakGlassService.issueBreakGlass(new io.uacrf.policy.service.BreakGlassService.BreakGlassRequest(
                TENANT_ID, ENV_ID, "urn:uacrf:svc:checkout", "K8S_ROLLBACK_DEPLOYMENT",
                "Maya Chen", "Emergency Sev1 checkout outage", true, 30));
        assertEquals("REQUIRED_OPEN", bg.postIncidentReviewStatus());
        assertNotNull(bg.signedJwsGrant());
    }
}
