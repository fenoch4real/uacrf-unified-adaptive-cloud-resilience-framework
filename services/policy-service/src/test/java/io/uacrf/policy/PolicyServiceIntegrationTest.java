package io.uacrf.policy;

import io.uacrf.policy.api.PolicyDomainException;
import io.uacrf.policy.api.PolicyDtos.ApproveProposalRequest;
import io.uacrf.policy.api.PolicyDtos.IssueAuthorizationRequest;
import io.uacrf.policy.service.PolicyAuthorityService;
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
class PolicyServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID ENV_ID = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID PROPOSAL_ID = UUID.fromString("dddd1111-1111-7111-8111-111111111101");
    private static final UUID PROPOSER_ID = UUID.fromString("33333333-3333-7333-8333-333333333301");
    private static final UUID OWNER_ID = UUID.fromString("33333333-3333-7333-8333-333333333303");

    @Autowired
    private PolicyAuthorityService service;

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
    }
}
