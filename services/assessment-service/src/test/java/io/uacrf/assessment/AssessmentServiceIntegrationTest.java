package io.uacrf.assessment;

import io.uacrf.assessment.api.AssessmentDomainException;
import io.uacrf.assessment.api.AssessmentDtos.*;
import io.uacrf.assessment.service.AssessmentEngineService;
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
class AssessmentServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID ENV_ID = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID ASSESSMENT_ID = UUID.fromString("aaaa1111-1111-7111-8111-111111111101");
    private static final UUID ASSESSOR_ID = UUID.fromString("33333333-3333-7333-8333-333333333302");
    private static final UUID INDEPENDENT_ID = UUID.fromString("33333333-3333-7333-8333-333333333304");

    @Autowired
    private AssessmentEngineService service;

    @Test
    void enforcesPathCertificationSeparationOfDutiesAndCryptographicEnvelopeReplay() {
        // P5 Path Independence: shared IDP & KMS_KEY produces NOT_INDEPENDENT
        var notIndependent = service.certifyRecoveryPaths(new CertifyPathsRequest(
                TENANT_ID, ENV_ID, ASSESSMENT_ID, "urn:uacrf:svc:checkout",
                "gke-us-central", "ecs-us-east",
                Map.of("REGION", "us-central1", "IDP", "corp-sso", "KMS_KEY", "cmk-payments-01"),
                Map.of("REGION", "us-east-1", "IDP", "corp-sso", "KMS_KEY", "cmk-payments-01"),
                "rev-sample-local-0001"
        ));
        assertEquals("NOT_INDEPENDENT", notIndependent.certificationStatus());
        assertTrue(notIndependent.sharedDomains().contains("IDP"));
        assertTrue(notIndependent.sharedDomains().contains("KMS_KEY"));

        // Separation of duties: assessor cannot approve own assessment as independent reviewer
        AssessmentDomainException selfEx = assertThrows(AssessmentDomainException.class, () ->
                service.recordReview(ASSESSMENT_ID, ASSESSOR_ID,
                        new ReviewDecisionRequest(TENANT_ID, ENV_ID, "INDEPENDENT", "Daniel Reed", "APPROVED")));
        assertEquals("SELF_REVIEW_FORBIDDEN", selfEx.getCode());

        // Distinct technical & independent reviewers issue the report
        service.recordReview(ASSESSMENT_ID, UUID.fromString("33333333-3333-7333-8333-333333333303"),
                new ReviewDecisionRequest(TENANT_ID, ENV_ID, "TECHNICAL", "Maya Chen", "APPROVED"));
        var issued = service.recordReview(ASSESSMENT_ID, INDEPENDENT_ID,
                new ReviewDecisionRequest(TENANT_ID, ENV_ID, "INDEPENDENT", "Aisha Patel", "APPROVED"));
        assertEquals("ISSUED", issued.reportStatus());

        // P1 Cryptographic Sealing & Replay verification + Tamper detection
        var sealed = service.sealDecisionEnvelope(TENANT_ID, ENV_ID, ASSESSMENT_ID, "Aisha Patel");
        var verified = service.verifyReplay(new VerifyReplayRequest(TENANT_ID, ENV_ID, sealed.envelopeId(), null));
        assertEquals("VERIFIED", verified.status());
        assertTrue(verified.signatureValid());
        assertTrue(verified.digestValid());

        var tampered = service.verifyReplay(new VerifyReplayRequest(
                TENANT_ID, ENV_ID, sealed.envelopeId(), "deadbeef-tampered-digest"));
        assertEquals("TAMPER_DETECTED", tampered.status());
        assertFalse(tampered.digestValid());
    }
}
