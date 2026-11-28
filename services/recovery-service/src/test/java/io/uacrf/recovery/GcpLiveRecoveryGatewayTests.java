package io.uacrf.recovery;

import io.uacrf.contracts.authority.BoundedRecoveryAuthorization;
import io.uacrf.recovery.adapter.CloudComputeScalingAdapter;
import io.uacrf.recovery.adapter.GcpLiveRecoveryGateway;
import io.uacrf.recovery.api.RecoveryDomainException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Verifies toggle-gated GCP live recovery execution (Cloud Run v2 PATCH traffic split & Compute MIG resize),
 * strict separation of execution credentials (NFR-SEC-003), SSRF blocks, and safe default DRY_RUN behavior.
 */
class GcpLiveRecoveryGatewayTests {

    private static BoundedRecoveryAuthorization sampleAuth(String actionType, int maxTargets, int maxTrafficPct) {
        return new BoundedRecoveryAuthorization(
                "1.0", UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                actionType, List.of("urn:uacrf:svc:checkout"),
                "11111111-1111-7111-8111-111111111111", "22222222-2222-7222-8222-222222222222",
                "rev-sample-001", "sha256:sub", maxTargets, maxTrafficPct, List.of("DELETE_DATA"),
                "val-plan", "comp-plan", "APPROVAL_REQUIRED",
                List.of("33333333-3333-7333-8333-333333333303"),
                "multi-cloud-compute-adapter", UUID.randomUUID().toString(),
                Instant.now(), Instant.now().plus(5, ChronoUnit.MINUTES)
        );
    }

    @Test
    void defaultsToDryRunAndFailsClosedWhenEnabledWithoutSeparateExecutionCredentials() {
        GcpLiveRecoveryGateway disabledGateway = new GcpLiveRecoveryGateway(
                false, "uacrf-sandbox-123", "us-central1", "us-central1-a",
                "", "", "https://oauth2.googleapis.com/token",
                "https://run.googleapis.com", "https://compute.googleapis.com"
        );
        CloudComputeScalingAdapter adapter = new CloudComputeScalingAdapter(disabledGateway);
        var auth = sampleAuth("CLOUD_RUN_TRAFFIC_SPLIT", 1, 100);

        // Toggle OFF -> safe simulation
        var dryOutcome = adapter.execute(auth, "urn:uacrf:svc:checkout", "checkout-v42", false);
        assertTrue(dryOutcome.succeeded());
        assertTrue(dryOutcome.summary().startsWith("[DRY_RUN_SIMULATED]"));

        // Toggle ON while provider gateway disabled -> fail closed
        DiscoveryCheck: {
            RecoveryDomainException disabledEx = assertThrows(RecoveryDomainException.class,
                    () -> adapter.execute(auth, "urn:uacrf:svc:checkout", "checkout-v42", true));
            assertEquals("GCP_LIVE_ADAPTER_DISABLED", disabledEx.getCode());
            assertEquals(HttpStatus.PRECONDITION_FAILED, disabledEx.getStatus());
        }

        // Gateway enabled but no S11 credentials -> fail closed (never borrows S03 discovery credentials)
        GcpLiveRecoveryGateway missingCreds = new GcpLiveRecoveryGateway(
                true, "uacrf-sandbox-123", "us-central1", "us-central1-a",
                "", "", "https://oauth2.googleapis.com/token",
                "https://run.googleapis.com", "https://compute.googleapis.com"
        );
        RecoveryDomainException credsEx = assertThrows(RecoveryDomainException.class,
                () -> missingCreds.executeGcpMutation(auth, "urn:uacrf:svc:checkout", "checkout-v42"));
        assertEquals("MISSING_EXECUTION_CREDENTIALS", credsEx.getCode());
        assertThrows(RecoveryDomainException.class,
                () -> missingCreds.validateSafeEndpoint("http://metadata.google.internal/computeMetadata/v1/"));
    }

    @Test
    void executesBoundedCloudRunPatchAndMigResizeWithSeparateServiceAccountAndCompensation(@TempDir Path tempDir) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";
        Path saKeyFile = tempDir.resolve("uacrf-exec-sa.json");
        Files.writeString(saKeyFile, "{\"type\":\"service_account\",\"client_email\":\"uacrf-exec@uacrf-sandbox-123.iam.gserviceaccount.com\","
                + "\"private_key\":\"" + pem.replace("\n", "\\n") + "\",\"token_uri\":\"https://oauth2.googleapis.com/token\"}");

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();

        GcpLiveRecoveryGateway gateway = new GcpLiveRecoveryGateway(
                true, "uacrf-sandbox-123", "us-central1", "us-central1-a",
                saKeyFile.toString(), "", "https://oauth2.googleapis.com/token",
                "https://run.googleapis.com", "https://compute.googleapis.com"
        );
        gateway.setRestClientForTesting(builder.build());

        // Cloud Run PATCH execute + compensate
        mockServer.expect(requestTo("https://oauth2.googleapis.com/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"ya29.exec-token-1\"}", MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo("https://run.googleapis.com/v2/projects/uacrf-sandbox-123/locations/us-central1/services/checkout?updateMask=traffic"))
                .andExpect(method(HttpMethod.PATCH))
                .andRespond(withSuccess("{\"name\":\"projects/uacrf-sandbox-123/locations/us-central1/services/checkout\"}", MediaType.APPLICATION_JSON));

        mockServer.expect(requestTo("https://oauth2.googleapis.com/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"ya29.exec-token-2\"}", MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo("https://run.googleapis.com/v2/projects/uacrf-sandbox-123/locations/us-central1/services/checkout?updateMask=traffic"))
                .andExpect(method(HttpMethod.PATCH))
                .andRespond(withSuccess("{\"name\":\"projects/uacrf-sandbox-123/locations/us-central1/services/checkout\"}", MediaType.APPLICATION_JSON));

        // Compute MIG resize capped at maxTargets=2
        mockServer.expect(requestTo("https://oauth2.googleapis.com/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"ya29.exec-token-3\"}", MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo("https://compute.googleapis.com/compute/v1/projects/uacrf-sandbox-123/zones/us-central1-a/instanceGroupManagers/checkout-mig/resize?size=2"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"status\":\"RUNNING\"}", MediaType.APPLICATION_JSON));

        CloudComputeScalingAdapter adapter = new CloudComputeScalingAdapter(gateway);
        var runAuth = sampleAuth("CLOUD_RUN_TRAFFIC_SPLIT", 1, 50);
        var execRes = adapter.execute(runAuth, "urn:uacrf:svc:checkout", "checkout-v42", true);
        assertTrue(execRes.summary().contains("[LIVE_GCP_MUTATION]"));
        assertTrue(execRes.summary().contains("trafficCap=50%"));

        var compRes = adapter.compensate(runAuth, "urn:uacrf:svc:checkout", "checkout-v43", true);
        assertTrue(compRes.summary().contains("[LIVE_GCP_COMPENSATED]"));

        var migAuth = sampleAuth("GCP_MIG_ROLLBACK", 2, 100);
        var migRes = adapter.execute(migAuth, "urn:uacrf:mig:checkout-mig", "10", true);
        assertTrue(migRes.summary().contains("size=2"));

        mockServer.verify();
    }
}
