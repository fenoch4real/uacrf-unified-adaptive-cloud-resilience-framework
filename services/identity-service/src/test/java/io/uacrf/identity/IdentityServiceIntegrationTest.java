package io.uacrf.identity;

import io.uacrf.identity.api.IdentityDomainException;
import io.uacrf.identity.api.IdentityDtos.*;
import io.uacrf.identity.service.IdentityAuthorityService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class IdentityServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private IdentityAuthorityService service;

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID ENV_ID = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID ADMIN_ID = UUID.fromString("33333333-3333-7333-8333-333333333301");
    private static final UUID APPROVER_ID = UUID.fromString("33333333-3333-7333-8333-333333333302");
    private static final UUID MAYA_ID = UUID.fromString("33333333-3333-7333-8333-333333333303");

    @Test
    void enforcesSeparationOfDutiesOwnerEligibilityAndDelegationExchange() {
        // 1. Admission creates no memberships
        var admitted = service.admitPrincipal(ADMIN_ID, "admit-1", new AdmitPrincipalRequest(
                TENANT_ID, ENV_ID, "HUMAN", "http://localhost:8180/realms/uacrf",
                "luis.romero", "Luis Romero", "luis@uacrf.local"
        ));
        assertEquals("NONE", admitted.runtimeEffect());
        assertTrue(service.listEligibleOwners(TENANT_ID, ENV_ID).stream()
                .noneMatch(p -> p.principalId().equals(admitted.principalId())));

        // 2. Self-approval is prohibited
        assertThrows(IdentityDomainException.class, () -> service.createGrantDecision(MAYA_ID,
                new CreateGrantDecisionRequest(TENANT_ID, ENV_ID, MAYA_ID, "owner.eligible", "GLOBAL",
                        "APPROVE", "self", Instant.now().plusSeconds(3600))));

        // 3. Independent approval + distinct manager membership grant succeeds
        var decision = service.createGrantDecision(APPROVER_ID, new CreateGrantDecisionRequest(
                TENANT_ID, ENV_ID, MAYA_ID, "owner.eligible", "GLOBAL",
                "APPROVE", "Verified accountable human owner", Instant.now().plusSeconds(900)));

        // Approver cannot also act as membership manager
        assertThrows(IdentityDomainException.class, () -> service.createMembership(APPROVER_ID, "mem-bad",
                new CreateMembershipRequest(TENANT_ID, ENV_ID, MAYA_ID, "owner.eligible", "GLOBAL",
                        decision.grantDecisionId(), Instant.now().plusSeconds(86400))));

        var membership = service.createMembership(ADMIN_ID, "mem-ok-1", new CreateMembershipRequest(
                TENANT_ID, ENV_ID, MAYA_ID, "owner.eligible", "GLOBAL",
                decision.grantDecisionId(), Instant.now().plusSeconds(86400)));
        assertNotNull(membership.membershipId());

        // Single-use grant decision cannot be reused with a new idempotency key
        assertThrows(IdentityDomainException.class, () -> service.createMembership(ADMIN_ID, "mem-ok-2",
                new CreateMembershipRequest(TENANT_ID, ENV_ID, MAYA_ID, "owner.eligible", "GLOBAL",
                        decision.grantDecisionId(), Instant.now().plusSeconds(86400))));

        // 4. Eligible owner appears; delegation exchange grants owned capability and denies unowned one
        assertTrue(service.listEligibleOwners(TENANT_ID, ENV_ID).stream()
                .anyMatch(p -> p.principalId().equals(MAYA_ID)));

        var delegation = service.exchangeDelegation(new DelegationExchangeRequest(
                TENANT_ID, ENV_ID, MAYA_ID, "uacrf-s02", "GLOBAL", List.of("owner.eligible")));
        assertNotNull(delegation.token());
        assertEquals("uacrf-s02", delegation.audience());

        assertThrows(IdentityDomainException.class, () -> service.exchangeDelegation(
                new DelegationExchangeRequest(TENANT_ID, ENV_ID, MAYA_ID, "uacrf-s02", "GLOBAL",
                        List.of("catalog.service.write"))));
    }
}
