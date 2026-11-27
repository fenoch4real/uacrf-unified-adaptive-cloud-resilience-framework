package io.uacrf.reporting;

import io.uacrf.reporting.api.ReportingDomainException;
import io.uacrf.reporting.api.ReportingDtos.CreateAssuranceRoomRequest;
import io.uacrf.reporting.api.ReportingDtos.RecordMeteringRequest;
import io.uacrf.reporting.service.ComplianceAssuranceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class ReportingServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID ENV_ID = UUID.fromString("22222222-2222-7222-8222-222222222222");

    @Autowired
    private ComplianceAssuranceService service;

    @Test
    void recordsCommercialMeteringCrosswalkAndWatermarkedAuditorAssuranceRooms() {
        var metered = service.recordMetering(new RecordMeteringRequest(
                TENANT_ID, ENV_ID, "BOUNDED_RECOVERY", "AZURE", 3, "executions"
        ));
        assertTrue(metered.eventDigest().startsWith("sha256:"));
        assertTrue(service.listMetering(TENANT_ID, ENV_ID).size() >= 4);

        var soc2Controls = service.getComplianceCrosswalk("SOC 2");
        assertEquals(3, soc2Controls.size());

        var room = service.createAssuranceRoom(new CreateAssuranceRoomRequest(
                TENANT_ID, ENV_ID, "Deloitte Assurance LLP", "auditor@deloitte.example",
                List.of("SOC 2", "ISO 27001", "EU DORA"), 24, "Maya Chen"
        ));
        assertTrue(room.watermarkText().contains("AUDITOR=DELOITTE ASSURANCE LLP"));
        assertTrue(room.bundleSha256().startsWith("sha256:"));

        var bundle = service.accessAssuranceBundle(room.roomId(), room.oneTimeAccessToken());
        assertEquals(room.bundleSha256(), bundle.bundleSha256());
        assertFalse(bundle.controls().isEmpty());

        ReportingDomainException badTokenEx = assertThrows(ReportingDomainException.class, () ->
                service.accessAssuranceBundle(room.roomId(), "wrong-token"));
        assertEquals("INVALID_ASSURANCE_TOKEN", badTokenEx.getCode());
    }
}
