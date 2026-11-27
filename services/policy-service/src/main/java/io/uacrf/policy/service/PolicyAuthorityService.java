package io.uacrf.policy.service;

import com.nimbusds.jose.jwk.RSAKey;
import io.uacrf.contracts.CanonicalEnums.GraphDeltaClassification;
import io.uacrf.contracts.authority.BoundedRecoveryAuthorization;
import io.uacrf.crypto.CanonicalJson;
import io.uacrf.crypto.EnvelopeSigner;
import io.uacrf.policy.api.PolicyDomainException;
import io.uacrf.policy.api.PolicyDtos.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PolicyAuthorityService {

    private final PolicyRepository repository;
    private final PolicyEvaluator policyEvaluator;
    private final MaterialGraphDeltaClassifier deltaClassifier;
    private final RSAKey rsaKey = EnvelopeSigner.generateEphemeralRsaKey("uacrf-s10-auth-key-1");
    private final EnvelopeSigner signer = new EnvelopeSigner(rsaKey, Clock.systemUTC());

    public PolicyAuthorityService(PolicyRepository repository,
                                  PolicyEvaluator policyEvaluator,
                                  MaterialGraphDeltaClassifier deltaClassifier) {
        this.repository = repository;
        this.policyEvaluator = policyEvaluator;
        this.deltaClassifier = deltaClassifier;
    }

    public List<ProposalDto> listProposals(UUID tenantId, UUID environmentId) {
        return repository.listProposals(tenantId, environmentId);
    }

    public List<IssuedAuthorizationDto> listAuthorizations(UUID tenantId, UUID environmentId) {
        return repository.listAuthorizations(tenantId, environmentId);
    }

    @Transactional
    public ProposalDto createProposal(UUID actorId, CreateProposalRequest req) {
        var verdict = policyEvaluator.evaluate(
                req.environmentKind(), req.evidenceQualification(),
                req.reversible(), req.blastRadiusCount(), true
        );
        if ("DENY".equals(verdict.outcome())) {
            throw new PolicyDomainException(HttpStatus.UNPROCESSABLE_ENTITY, "POLICY_DENIED",
                    "OPA baseline policy denied recovery action: " + verdict.reasons());
        }

        ProposalDto dto = new ProposalDto(
                UUID.randomUUID(), req.tenantId(), req.environmentId(), req.serviceCanonicalId(),
                req.actionType(), req.summaryTitle(), req.targetVersion(), req.reversible(),
                req.blastRadiusCount(), actorId, req.proposerName(), null, null,
                "PREAUTHORIZED".equals(verdict.outcome()) ? "APPROVED" : "PENDING_APPROVAL",
                verdict.outcome(), verdict.reasons(), req.graphRevision(), req.subgraphDigest(),
                req.evidenceQualification(), req.rationale(), Instant.now()
        );
        repository.insertProposal(dto);
        repository.insertOutbox(req.tenantId(), req.environmentId(), "policy.proposal.created",
                "RecoveryProposal", dto.proposalId().toString(), UUID.randomUUID(), CanonicalJson.toJson(dto));
        return dto;
    }

    @Transactional
    public ProposalDto approveProposal(UUID proposalId, UUID actorId, ApproveProposalRequest req) {
        ProposalDto proposal = repository.findProposal(req.tenantId(), req.environmentId(), proposalId)
                .orElseThrow(() -> new PolicyDomainException(
                        HttpStatus.NOT_FOUND, "PROPOSAL_NOT_FOUND", "Recovery proposal not found: " + proposalId));

        if (actorId.equals(proposal.proposerId())) {
            throw new PolicyDomainException(
                    HttpStatus.FORBIDDEN,
                    "SELF_APPROVAL_FORBIDDEN",
                    "Separation of duties: a recovery proposer cannot approve their own proposal."
            );
        }

        String nextStatus = "APPROVE".equalsIgnoreCase(req.decision()) ? "APPROVED" : "REJECTED";
        repository.updateApproval(proposalId, actorId, req.approverName(), nextStatus);
        ProposalDto refreshed = repository.findProposal(req.tenantId(), req.environmentId(), proposalId).orElseThrow();
        repository.insertOutbox(req.tenantId(), req.environmentId(), "policy.proposal.decided",
                "RecoveryProposal", proposalId.toString(), UUID.randomUUID(), CanonicalJson.toJson(refreshed));
        return refreshed;
    }

    @Transactional
    public IssuedAuthorizationDto issueBoundedAuthorization(UUID proposalId, IssueAuthorizationRequest req) {
        ProposalDto proposal = repository.findProposal(req.tenantId(), req.environmentId(), proposalId)
                .orElseThrow(() -> new PolicyDomainException(
                        HttpStatus.NOT_FOUND, "PROPOSAL_NOT_FOUND", "Recovery proposal not found: " + proposalId));

        if (!"APPROVED".equalsIgnoreCase(proposal.approvalStatus())) {
            throw new PolicyDomainException(
                    HttpStatus.CONFLICT, "APPROVAL_REQUIRED",
                    "Proposal must be APPROVED by an independent principal before authorization can be issued.");
        }

        GraphDeltaClassification delta = deltaClassifier.classify(
                proposal.graphRevision(), req.currentGraphRevision(),
                proposal.subgraphDigest(), req.currentSubgraphDigest(),
                proposal.blastRadiusCount(), req.currentBlastRadiusCount(),
                req.fallbackChanged()
        );

        if (!deltaClassifier.permitsAuthorization(delta)) {
            throw new PolicyDomainException(
                    HttpStatus.CONFLICT,
                    "MATERIAL_GRAPH_DELTA_INVALIDATED",
                    "Pre-execution P2 graph check detected " + delta + "; proposal must be re-evaluated before execution."
            );
        }

        UUID authId = UUID.randomUUID();
        String nonce = UUID.randomUUID().toString();
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(5, ChronoUnit.MINUTES);

        BoundedRecoveryAuthorization envelope = new BoundedRecoveryAuthorization(
                "1.0",
                authId.toString(),
                proposalId.toString(),
                proposal.actionType(),
                List.of(proposal.serviceCanonicalId()),
                req.tenantId().toString(),
                req.environmentId().toString(),
                req.currentGraphRevision(),
                req.currentSubgraphDigest(),
                proposal.blastRadiusCount(),
                100,
                List.of("DELETE_DATA", "MODIFY_IAM", "CROSS_TENANT_EXECUTION"),
                "val-plan-e2e-place-order",
                "comp-rollback-v43",
                proposal.policyOutcome(),
                proposal.approverId() != null ? List.of(proposal.approverId().toString()) : List.of(),
                req.audience(),
                nonce,
                issuedAt,
                expiresAt
        );

        String jws = signer.sign(
                "uacrf-s10-authority",
                req.audience(),
                proposal.serviceCanonicalId(),
                nonce,
                expiresAt,
                Map.of(
                        "authorizationId", authId.toString(),
                        "graphRevision", req.currentGraphRevision(),
                        "target", proposal.serviceCanonicalId(),
                        "deltaClassification", delta.name()
                )
        );

        IssuedAuthorizationDto dto = new IssuedAuthorizationDto(
                authId, proposalId, nonce, req.audience(), delta.name(), false, jws, envelope, expiresAt
        );
        repository.insertAuthorization(dto);
        repository.insertOutbox(req.tenantId(), req.environmentId(), "policy.authorization.issued",
                "BoundedAuthorization", authId.toString(), UUID.randomUUID(), CanonicalJson.toJson(dto));
        return dto;
    }

    @Transactional
    public void consumeNonce(String nonce) {
        int updated = repository.consumeNonce(nonce);
        if (updated == 0) {
            throw new PolicyDomainException(
                    HttpStatus.CONFLICT, "NONCE_REPLAY_OR_EXPIRED",
                    "Bounded authorization nonce is already consumed or expired.");
        }
    }

    public RSAKey getPublicJwk() {
        return rsaKey.toPublicJWK();
    }
}
