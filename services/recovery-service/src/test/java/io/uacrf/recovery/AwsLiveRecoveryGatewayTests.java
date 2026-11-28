package io.uacrf.recovery;

import io.uacrf.contracts.authority.BoundedRecoveryAuthorization;
import io.uacrf.recovery.adapter.AwsLiveRecoveryGateway;
import io.uacrf.recovery.adapter.CloudComputeScalingAdapter;
import io.uacrf.recovery.api.RecoveryDomainException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Verifies toggle-gated AWS live recovery (Auto Scaling SetDesiredCapacity & ECS UpdateService)
 * using native SigV4 signing, strict NFR-SEC-003 credential isolation, and P3 cap enforcement.
 */
class AwsLiveRecoveryGatewayTests {

    private static BoundedRecoveryAuthorization sampleAuth(String actionType, int maxTargets) {
        return new BoundedRecoveryAuthorization(
                "1.0", UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                actionType, List.of("arn:aws:ecs:us-east-1:123456789012:service/checkout-svc"),
                "11111111-1111-7111-8111-111111111111", "22222222-2222-7222-8222-222222222222",
                "rev-sample-001", "sha256:sub", maxTargets, 100, List.of("DELETE_DATA"),
                "val-plan", "comp-plan", "APPROVAL_REQUIRED",
                List.of("33333333-3333-7333-8333-333333333303"),
                "multi-cloud-compute-adapter", UUID.randomUUID().toString(),
                Instant.now(), Instant.now().plus(5, ChronoUnit.MINUTES)
        );
    }

    @Test
    void defaultsToDryRunAndFailsClosedWithoutSeparateAwsExecutionCredentials() {
        AwsLiveRecoveryGateway disabled = new AwsLiveRecoveryGateway(
                false, "us-east-1", "prod-cluster",
                "", "", "", "", "uacrf-exec", "", ""
        );
        CloudComputeScalingAdapter adapter = new CloudComputeScalingAdapter(null, disabled);
        var auth = sampleAuth("AWS_ECS_ROLLBACK", 1);

        var dry = adapter.execute(auth, "arn:aws:ecs:us-east-1:123456789012:service/checkout-svc", "checkout:42", false);
        assertTrue(dry.succeeded());
        assertTrue(dry.summary().startsWith("[DRY_RUN_SIMULATED]"));

        RecoveryDomainException disabledEx = assertThrows(RecoveryDomainException.class,
                () -> disabled.executeAwsMutation(auth, "arn:aws:ecs:us-east-1:123456789012:service/checkout-svc", "checkout:42"));
        assertEquals("AWS_LIVE_ADAPTER_DISABLED", disabledEx.getCode());

        AwsLiveRecoveryGateway missingCreds = new AwsLiveRecoveryGateway(
                true, "us-east-1", "prod-cluster",
                "", "", "", "", "uacrf-exec", "", ""
        );
        RecoveryDomainException credsEx = assertThrows(RecoveryDomainException.class,
                () -> missingCreds.executeAwsMutation(auth, "arn:aws:ecs:us-east-1:123456789012:service/checkout-svc", "checkout:42"));
        assertEquals("MISSING_EXECUTION_CREDENTIALS", credsEx.getCode());
        assertEquals(HttpStatus.PRECONDITION_FAILED, credsEx.getStatus());

        assertThrows(RecoveryDomainException.class,
                () -> missingCreds.validateSafeEndpoint("http://169.254.169.254/latest/meta-data/"));
    }

    @Test
    void signsAndExecutesBoundedEcsRollbackAndAsgScaleWithCompensation() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();

        AwsLiveRecoveryGateway gateway = new AwsLiveRecoveryGateway(
                true, "us-east-1", "prod-cluster",
                "AKIA_EXEC_KEY", "SECRET_EXEC_KEY", "EXEC_SESSION",
                "", "uacrf-exec",
                "https://autoscaling.us-east-1.amazonaws.com",
                "https://ecs.us-east-1.amazonaws.com"
        );
        gateway.setRestClientForTesting(builder.build());

        mockServer.expect(requestTo("https://ecs.us-east-1.amazonaws.com/"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Amz-Target", "AmazonEC2ContainerServiceV20141113.UpdateService"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, startsWith("AWS4-HMAC-SHA256 Credential=AKIA_EXEC_KEY/")))
                .andExpect(header("x-amz-security-token", "EXEC_SESSION"))
                .andExpect(content().string(containsString("checkout:42")))
                .andRespond(withSuccess("{\"service\":{\"serviceName\":\"checkout-svc\"}}", MediaType.APPLICATION_JSON));

        mockServer.expect(requestTo("https://ecs.us-east-1.amazonaws.com/"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("checkout:43")))
                .andRespond(withSuccess("{\"service\":{\"serviceName\":\"checkout-svc\"}}", MediaType.APPLICATION_JSON));

        mockServer.expect(requestTo("https://autoscaling.us-east-1.amazonaws.com/"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, startsWith("AWS4-HMAC-SHA256 Credential=AKIA_EXEC_KEY/")))
                .andExpect(content().string(containsString("DesiredCapacity=2")))
                .andRespond(withSuccess("<SetDesiredCapacityResponse/>", MediaType.APPLICATION_XML));

        CloudComputeScalingAdapter adapter = new CloudComputeScalingAdapter(null, gateway);
        var ecsAuth = sampleAuth("AWS_ECS_ROLLBACK", 1);
        var execRes = adapter.execute(ecsAuth, "arn:aws:ecs:us-east-1:123456789012:service/checkout-svc", "checkout:42", true);
        assertTrue(execRes.summary().contains("[LIVE_AWS_MUTATION]"));

        var compRes = adapter.compensate(ecsAuth, "arn:aws:ecs:us-east-1:123456789012:service/checkout-svc", "checkout:43", true);
        assertTrue(compRes.summary().contains("[LIVE_AWS_COMPENSATED]"));

        var asgAuth = sampleAuth("AWS_ASG_SCALE", 2);
        var asgRes = adapter.execute(asgAuth, "urn:uacrf:aws:asg:checkout-asg", "10", true);
        assertTrue(asgRes.summary().contains("desiredCapacity=2"));

        mockServer.verify();
    }
}
