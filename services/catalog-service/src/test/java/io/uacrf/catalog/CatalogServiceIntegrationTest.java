package io.uacrf.catalog;

import io.uacrf.catalog.api.CatalogDomainException;
import io.uacrf.catalog.api.CatalogDtos.*;
import io.uacrf.catalog.service.CatalogService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class CatalogServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private CatalogService catalogService;

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID ENV_ID = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID OWNER_ID = UUID.fromString("33333333-3333-7333-8333-333333333303");
    private static final UUID REVIEWER_ID = UUID.fromString("33333333-3333-7333-8333-333333333304");
    private static final UUID AGENT_RUNTIME_ID = UUID.fromString("44444444-4444-7444-8444-444444444401");

    @Test
    void enforcesSeparateInventoriesAgentActivationAndImpactProfileReviewSeparation() {
        // 1. Register conventional service and standalone agent workload
        var checkout = catalogService.registerService(OWNER_ID, "svc-1", new CreateServiceRequest(
                TENANT_ID, ENV_ID, "checkout-service", "commerce", "Core checkout API", "P0", OWNER_ID));

        var supportAgent = catalogService.registerAgentWorkload(OWNER_ID, "agt-1", new RegisterAgentWorkloadRequest(
                TENANT_ID, ENV_ID, null, "order-support-agent", "SINGLE_AGENT",
                "Assists order lookup and policy guidance", OWNER_ID, AGENT_RUNTIME_ID, "P1"));

        // Conventional service list must NOT double-count the internal AGENT_ANCHOR
        var conventionalServices = catalogService.listServices(TENANT_ID, ENV_ID);
        assertEquals(1, conventionalServices.size());
        assertEquals(checkout.serviceId(), conventionalServices.get(0).serviceId());

        // Independent activation of immutable agent workload version
        var ver1 = catalogService.registerWorkloadVersion(OWNER_ID, supportAgent.workloadProfileId(),
                new RegisterWorkloadVersionRequest(TENANT_ID, ENV_ID, Map.of("model", "gemini-pro", "tools", List.of("order_lookup"))));
        assertThrows(CatalogDomainException.class, () -> catalogService.activateWorkloadVersion(
                OWNER_ID, supportAgent.workloadProfileId(),
                new ActivateWorkloadVersionRequest(TENANT_ID, ENV_ID, ver1.versionNumber(), 1L)));

        var activated = catalogService.activateWorkloadVersion(
                REVIEWER_ID, supportAgent.workloadProfileId(),
                new ActivateWorkloadVersionRequest(TENANT_ID, ENV_ID, ver1.versionNumber(), 1L));
        assertEquals("ACTIVE", activated.status());

        // 2. Customer journey + ordered steps + capability links + Impact Profile review separation
        var journey = catalogService.createJourney(OWNER_ID, "jny-1", new CreateJourneyRequest(
                TENANT_ID, ENV_ID, "Place Order", "Customer completes and confirms order", OWNER_ID, "24x7"));

        var step1 = catalogService.addJourneyStep(OWNER_ID, journey.journeyId(), new AddJourneyStepRequest(
                TENANT_ID, ENV_ID, 1, "Start Checkout", "Cart validated and checkout session created", 15, "DEGRADE"));

        catalogService.linkCapability(OWNER_ID, "lnk-1", journey.journeyId(), new LinkJourneyCapabilityRequest(
                TENANT_ID, ENV_ID, step1.stepId(), "SERVICE", checkout.serviceId(), "REQUIRED",
                "Checkout API serves Start Checkout", "owner-attestation-1"));

        var draft = catalogService.saveImpactProfileDraft(OWNER_ID, journey.journeyId(), new UpsertImpactProfileRequest(
                TENANT_ID, ENV_ID, "Customers cannot complete purchases", "Retail shoppers", "P0",
                new BigDecimal("15"), "MINUTES", "Maximum tolerable outage per business continuity policy",
                "Validated by journey owner",
                List.of(new ImpactDimensionInput("CUSTOMER", "Blocked transaction completion", "CRITICAL"))));

        catalogService.submitImpactProfile(OWNER_ID, journey.journeyId(), draft.revision(), TENANT_ID, ENV_ID);

        // Self-review must fail
        assertThrows(CatalogDomainException.class, () -> catalogService.reviewImpactProfile(
                OWNER_ID, journey.journeyId(), draft.revision(),
                new ReviewImpactProfileRequest(TENANT_ID, ENV_ID, "APPROVE", "Self review")));

        var approved = catalogService.reviewImpactProfile(
                REVIEWER_ID, journey.journeyId(), draft.revision(),
                new ReviewImpactProfileRequest(TENANT_ID, ENV_ID, "APPROVE", "Independent impact review complete"));
        assertEquals("APPROVED", approved.assessmentStatus());

        var detail = catalogService.getJourneyDetail(TENANT_ID, ENV_ID, journey.journeyId());
        assertEquals(1, detail.steps().size());
        assertEquals(1, detail.capabilities().size());
        assertEquals("APPROVED", detail.latestImpactProfile().assessmentStatus());
    }
}
