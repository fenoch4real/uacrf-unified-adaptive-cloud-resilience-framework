package io.uacrf.evidence;

import io.uacrf.evidence.api.EvidenceDomainException;
import io.uacrf.evidence.api.EvidenceDtos.SubmitEvidenceRequest;
import io.uacrf.evidence.service.EvidenceQualificationService;
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

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class EvidenceServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static GenericContainer<?> s3 = new GenericContainer<>("localstack/localstack:3.8")
            .withExposedPorts(4566)
            .withEnv("SERVICES", "s3")
            .waitingFor(Wait.forHttp("/_localstack/health").forPort(4566));

    @DynamicPropertySource
    static void registerS3Props(DynamicPropertyRegistry registry) {
        registry.add("uacrf.evidence.s3.enabled", () -> "true");
        registry.add("uacrf.evidence.s3.object-lock-enabled", () -> "true");
        registry.add("uacrf.evidence.minio-endpoint", () -> "http://" + s3.getHost() + ":" + s3.getMappedPort(4566));
    }

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID ENV_ID = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID PKG_ID = UUID.fromString("66666666-6666-7666-8666-666666666601");

    @Autowired
    private EvidenceQualificationService service;

    @Test
    void enforcesNoSyntheticFallbackAndCompletesPackageWhenRealEvidenceSubmitted() {
        var initial = service.getPackage(TENANT_ID, ENV_ID, PKG_ID);
        assertEquals("MISSING_REQUIRED", initial.completenessStatus());
        assertEquals("UNKNOWN", initial.recoveryReadiness());

        // Reject attempting to satisfy e2e transaction probe with an approved runbook or agent summary
        EvidenceDomainException ex = assertThrows(EvidenceDomainException.class, () ->
                service.submitEvidence(PKG_ID, new SubmitEvidenceRequest(
                        TENANT_ID, ENV_ID, "e2e_transaction_probe", "KNOWLEDGE_DOC",
                        "Runbook says probe usually passes", "rev-sample-local-0001", 3600, Map.of()
                )));
        assertEquals("SYNTHETIC_FALLBACK_FORBIDDEN", ex.getCode());

        // Export draft includes missing disclosures
        var draftExport = service.exportPackage(TENANT_ID, ENV_ID, PKG_ID);
        assertEquals("DRAFT_WITH_DISCLOSURES", draftExport.exportKind());
        assertFalse(draftExport.limitations().isEmpty());

        // Submit valid transaction probe + rollback compatibility -> package becomes COMPLETE / QUALIFIED
        service.submitEvidence(PKG_ID, new SubmitEvidenceRequest(
                TENANT_ID, ENV_ID, "e2e_transaction_probe", "TRANSACTION_PROBE",
                "Synthetic Place Order probe succeeded 20/20", "rev-sample-local-0001", 3600,
                Map.of("probeSuccessRate", 1.0, "samples", 20)
        ));
        var completed = service.submitEvidence(PKG_ID, new SubmitEvidenceRequest(
                TENANT_ID, ENV_ID, "rollback_compatibility", "COMPATIBILITY_CHECK",
                "v42 schema & API backwards-compatibility verified by service owner", "rev-sample-local-0001", 3600,
                Map.of("targetVersion", "v42", "capacityRatio", 1.35)
        ));

        assertEquals("COMPLETE", completed.completenessStatus());
        assertEquals("QUALIFIED", completed.recoveryReadiness());

        var aiEvidence = service.listAiEvidence(TENANT_ID, ENV_ID, "urn:uacrf:svc:payment");
        assertEquals(6, aiEvidence.size());
    }
}
