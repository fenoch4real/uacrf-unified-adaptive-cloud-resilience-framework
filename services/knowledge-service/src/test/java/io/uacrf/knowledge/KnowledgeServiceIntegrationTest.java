package io.uacrf.knowledge;

import io.uacrf.knowledge.api.KnowledgeDomainException;
import io.uacrf.knowledge.api.KnowledgeDtos.*;
import io.uacrf.knowledge.service.KnowledgeGovernanceService;
import io.uacrf.knowledge.service.QuantitativeShadowEngine;
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
class KnowledgeServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID ENV_ID = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID AUTHOR_ID = UUID.fromString("33333333-3333-7333-8333-333333333301");
    private static final UUID REVIEWER_ID = UUID.fromString("33333333-3333-7333-8333-333333333303");
    private static final UUID LEARNING_ID = UUID.fromString("eeee2222-2222-7222-8222-222222222201");

    @Autowired
    private KnowledgeGovernanceService service;

    @Autowired
    private QuantitativeShadowEngine quantitative;

    @Test
    void enforcesKnowledgeQuarantineLearningClosureExperimentAbortAndQuantitativeBenchmarks() {
        // S08 Knowledge quarantine + separation of duties
        var art = service.submitArticle(AUTHOR_ID, new CreateArticleRequest(
                TENANT_ID, ENV_ID, "OPS-3001", "RUNBOOK", "Payment gateway failover check",
                "Validate idempotent retry keys.", "Luis Romero"
        ));
        assertEquals("QUARANTINED", art.status());

        KnowledgeDomainException selfEx = assertThrows(KnowledgeDomainException.class, () ->
                service.reviewArticle(art.articleId(), AUTHOR_ID,
                        new ReviewArticleRequest(TENANT_ID, ENV_ID, "Luis Romero", "APPROVE")));
        assertEquals("SELF_APPROVAL_FORBIDDEN", selfEx.getCode());

        var approved = service.reviewArticle(art.articleId(), REVIEWER_ID,
                new ReviewArticleRequest(TENANT_ID, ENV_ID, "Maya Chen", "APPROVE"));
        assertEquals("APPROVED", approved.status());

        // S12 Learning closure blocked without verification digest
        KnowledgeDomainException lrnEx = assertThrows(KnowledgeDomainException.class, () ->
                service.closeLearningItem(LEARNING_ID, new CloseLearningRequest(TENANT_ID, ENV_ID, "")));
        assertEquals("VERIFICATION_REQUIRED", lrnEx.getCode());

        var closed = service.closeLearningItem(LEARNING_ID, new CloseLearningRequest(TENANT_ID, ENV_ID, "sha256:canary-gate-verified"));
        assertEquals("CLOSED", closed.getFirst().status());

        // S13 Fault experiment auto-aborts when SLO drops below threshold
        var exp = service.runExperiment(new RunExperimentRequest(
                TENANT_ID, ENV_ID, "Inject 250ms Payment latency", "LATENCY",
                "urn:uacrf:svc:payment", 1, 98.0, 91.2
        ));
        assertEquals("ABORTED_BY_GUARDRAIL", exp.status());

        // Paper Section 10.5 Quantitative Shadow Model & Ablations
        var bench = quantitative.runSection105Benchmark();
        assertEquals("SHADOW_NON_AUTHORITATIVE", bench.sampleShadowState().mode());
        assertEquals(4, bench.benchmarks().size());
        assertTrue(bench.benchmarks().getFirst().effectiveResilience() > bench.benchmarks().getLast().effectiveResilience());
    }
}
