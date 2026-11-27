package io.uacrf.assessment.service;

import com.nimbusds.jose.jwk.RSAKey;
import io.uacrf.assessment.api.AssessmentDomainException;
import io.uacrf.assessment.api.AssessmentDtos.*;
import io.uacrf.crypto.CanonicalJson;
import io.uacrf.crypto.EnvelopeSigner;
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
public class AssessmentEngineService {

    private static final String ENVELOPE_AUDIENCE = "uacrf-replay-verifier";

    private final AssessmentRepository repository;
    private final PathIndependenceCertifier certifier;
    private final RSAKey rsaKey = EnvelopeSigner.generateEphemeralRsaKey("uacrf-s07-key-1");
    private final EnvelopeSigner signer = new EnvelopeSigner(rsaKey, Clock.systemUTC());

    public AssessmentEngineService(AssessmentRepository repository, PathIndependenceCertifier certifier) {
        this.repository = repository;
        this.certifier = certifier;
    }

    public List<AssessmentDto> listAssessments(UUID tenantId, UUID environmentId) {
        return repository.listAssessments(tenantId, environmentId);
    }

    public AssessmentDto getAssessment(UUID tenantId, UUID environmentId, UUID assessmentId) {
        return repository.findAssessment(tenantId, environmentId, assessmentId)
                .orElseThrow(() -> new AssessmentDomainException(
                        HttpStatus.NOT_FOUND, "ASSESSMENT_NOT_FOUND", "Assessment not found: " + assessmentId));
    }

    @Transactional
    public PathCertificationDto certifyRecoveryPaths(CertifyPathsRequest req) {
        var outcome = certifier.certify(req.primaryCorrelationDomains(), req.alternateCorrelationDomains());
        var cert = repository.insertCertification(
                req.tenantId(), req.environmentId(), req.assessmentId(),
                req.serviceCanonicalId(), req.primaryPathId(), req.alternatePathId(),
                outcome.status(), outcome.sharedDomains(), outcome.witnessSummary(), req.graphRevision()
        );
        repository.insertOutbox(
                req.tenantId(), req.environmentId(), "assessment.path_certification.completed",
                "PathCertification", cert.certificationId().toString(),
                UUID.randomUUID(), CanonicalJson.toJson(cert)
        );
        return cert;
    }

    @Transactional
    public AssessmentDto recordReview(UUID assessmentId, UUID actorId, ReviewDecisionRequest req) {
        AssessmentDto assessment = getAssessment(req.tenantId(), req.environmentId(), assessmentId);

        if ("INDEPENDENT".equalsIgnoreCase(req.reviewType())) {
            if (actorId.equals(assessment.assessorId()) || actorId.equals(assessment.serviceOwnerId())) {
                throw new AssessmentDomainException(
                        HttpStatus.FORBIDDEN,
                        "SELF_REVIEW_FORBIDDEN",
                        "Independent review requires a distinct principal from both assessor and service owner."
                );
            }
            repository.updateIndependentReview(assessmentId, actorId, req.reviewerName(), req.outcome());
        } else {
            repository.updateTechnicalReview(assessmentId, req.reviewerName(), req.outcome());
        }

        AssessmentDto refreshed = getAssessment(req.tenantId(), req.environmentId(), assessmentId);
        if ("APPROVED".equalsIgnoreCase(refreshed.technicalReviewStatus())
                && "APPROVED".equalsIgnoreCase(refreshed.independentReviewStatus())) {
            repository.issueReport(assessmentId, "CONDITIONALLY_READY",
                    "Report reviewed & issued with disclosed recovery test limitation.", "ISSUED");
        }
        return getAssessment(req.tenantId(), req.environmentId(), assessmentId);
    }

    @Transactional
    public DecisionEnvelopeDto sealDecisionEnvelope(UUID tenantId, UUID environmentId, UUID assessmentId, String actorName) {
        AssessmentDto assessment = getAssessment(tenantId, environmentId, assessmentId);
        Map<String, Object> manifest = Map.of(
                "assessmentId", assessment.assessmentId().toString(),
                "journeyCanonicalId", assessment.journeyCanonicalId(),
                "graphRevision", assessment.graphRevision(),
                "conclusionStatus", assessment.conclusionStatus(),
                "reportStatus", assessment.reportStatus(),
                "findingsCount", assessment.findings().size(),
                "pathCertificationsCount", assessment.pathCertifications().size()
        );
        String payloadJson = CanonicalJson.toJson(manifest);
        String sha256 = CanonicalJson.sha256Hex(CanonicalJson.canonicalizeBytes(manifest));
        String token = signer.sign(
                "uacrf-s07-assessment",
                ENVELOPE_AUDIENCE,
                assessmentId.toString(),
                UUID.randomUUID().toString(),
                Instant.now().plus(30, ChronoUnit.DAYS),
                Map.of("payloadSha256", sha256, "graphRevision", assessment.graphRevision())
        );

        DecisionEnvelopeDto sealed = repository.insertEnvelope(
                assessmentId, tenantId, environmentId, assessment.graphRevision(),
                sha256, token, payloadJson, actorName
        );
        repository.insertOutbox(
                tenantId, environmentId, "assessment.decision_envelope.sealed",
                "DecisionEnvelope", sealed.envelopeId().toString(),
                UUID.randomUUID(), payloadJson
        );
        return sealed;
    }

    public ReplayVerificationResult verifyReplay(VerifyReplayRequest req) {
        var row = repository.findEnvelope(req.tenantId(), req.environmentId(), req.envelopeId())
                .orElseThrow(() -> new AssessmentDomainException(
                        HttpStatus.NOT_FOUND, "ENVELOPE_NOT_FOUND", "Decision envelope not found: " + req.envelopeId()));

        boolean sigOk = false;
        String signedDigest = null;
        try {
            var claims = signer.verify(row.jwsToken(), rsaKey.toPublicJWK(), ENVELOPE_AUDIENCE);
            signedDigest = claims.getStringClaim("payloadSha256");
            sigOk = true;
        } catch (Exception ignored) {
            sigOk = false;
        }

        String candidateDigest = req.overridePayloadSha256() != null
                ? req.overridePayloadSha256()
                : row.payloadSha256();
        boolean digestOk = sigOk && row.payloadSha256().equals(signedDigest) && row.payloadSha256().equals(candidateDigest);

        return new ReplayVerificationResult(
                row.envelopeId(),
                sigOk,
                digestOk,
                (sigOk && digestOk) ? "VERIFIED" : "TAMPER_DETECTED",
                (sigOk && digestOk)
                        ? "RS256 signature and canonical SHA-256 chain-of-custody verified."
                        : "Cryptographic verification failed: SHA-256 mismatch or signature invalid.",
                Instant.now()
        );
    }
}
