package io.uacrf.discovery;

import io.uacrf.discovery.adapter.CloudInventoryPollerGateway;
import io.uacrf.discovery.adapter.GcpCredentialProvider;
import io.uacrf.discovery.adapter.GcpRuntimeTelemetryPoller;
import io.uacrf.discovery.api.DiscoveryDomainException;
import io.uacrf.discovery.api.DiscoveryDtos.GcpRuntimeTelemetryPollRequest;
import io.uacrf.discovery.api.DiscoveryDtos.RawCloudAssetRecord;
import io.uacrf.discovery.service.PiiPhiScrubber;
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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Verifies multi-cloud catalog & inventory polling contracts, SSRF blocks, fail-closed disabled standby,
 * GCP ADC/Service-Account OAuth2 exchanges, and AWS / GCP / Azure / OCI wire-payload parsing without live cloud credentials.
 */
class CloudInventoryPollerTests {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID ENV_ID = UUID.fromString("22222222-2222-7222-8222-222222222222");

    private CloudInventoryPollerGateway buildStandbyGateway() {
        return new CloudInventoryPollerGateway(
                new PiiPhiScrubber(),
                new GcpCredentialProvider("", "", "https://oauth2.googleapis.com/token"),
                false, "us-east-1", "IAM_ASSUME_ROLE", "", "", "",
                false, "uacrf-gcp", "WORKLOAD_IDENTITY", "", "",
                false, "uacrf-az", "MANAGED_IDENTITY", "", "",
                false, "ocid1.tenancy.oc1..demo", "us-ashburn-1", "INSTANCE_PRINCIPAL", "", ""
        );
    }

    @Test
    void listsAllFourProvidersInStandbyAndAllTwentyResourceFamilies() {
        CloudInventoryPollerGateway gateway = buildStandbyGateway();
        var profiles = gateway.listProfiles();
        assertEquals(4, profiles.size());
        assertTrue(profiles.stream().noneMatch(p -> p.enabled()));

        var families = gateway.listResourceFamilies();
        assertEquals(20, families.size());
        assertTrue(families.stream().anyMatch(f -> "INF-CMP".equals(f.ruleFamily()) && "ComputeNode".equals(f.canonicalNodeLabel())));
        assertTrue(families.stream().anyMatch(f -> "AI-MODEL".equals(f.ruleFamily()) && "ModelEndpoint".equals(f.canonicalNodeLabel())));
    }

    @Test
    void failsClosedWhenAdapterDisabledOrCredentialsMissingAndBlocksSsrfHosts() {
        CloudInventoryPollerGateway gateway = buildStandbyGateway();

        DiscoveryDomainException disabledEx = assertThrows(DiscoveryDomainException.class,
                () -> gateway.pollAndNormalize(TENANT_ID, ENV_ID, "AWS", "*"));
        assertEquals("PROVIDER_ADAPTER_DISABLED", disabledEx.getCode());
        assertEquals(HttpStatus.PRECONDITION_FAILED, disabledEx.getStatus());

        DiscoveryDomainException ssrfEx = assertThrows(DiscoveryDomainException.class,
                () -> gateway.validateSafeEndpoint("http://169.254.169.254/latest/meta-data/"));
        assertEquals("SSRF_EGRESS_BLOCKED", ssrfEx.getCode());
        assertEquals(HttpStatus.FORBIDDEN, ssrfEx.getStatus());

        DiscoveryDomainException gcpSsrf = assertThrows(DiscoveryDomainException.class,
                () -> gateway.validateSafeEndpoint("http://metadata.google.internal/computeMetadata/v1/"));
        assertEquals("SSRF_EGRESS_BLOCKED", gcpSsrf.getCode());
    }

    @Test
    void pollsAndNormalizesMockedAwsGcpAzureAndOciApiResponsesWithoutLiveCredentials() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();

        CloudInventoryPollerGateway gateway = new CloudInventoryPollerGateway(
                new PiiPhiScrubber(),
                new GcpCredentialProvider("mock-gcp-token", "", "https://oauth2.googleapis.com/token"),
                true, "us-east-1", "IAM_ASSUME_ROLE", "arn:aws:iam::123456789012:role/uacrf-ro",
                "https://resource-explorer-2.us-east-1.amazonaws.com/Search", "mock-aws-token",
                true, "uacrf-prod", "WORKLOAD_IDENTITY",
                "https://cloudasset.googleapis.com/v1/projects/uacrf-prod:searchAllResources", "mock-gcp-token",
                true, "sub-123", "MANAGED_IDENTITY",
                "https://management.azure.com/providers/Microsoft.ResourceGraph/resources?api-version=2022-10-01", "mock-azure-token",
                true, "ocid1.tenancy.oc1..demo", "us-ashburn-1", "INSTANCE_PRINCIPAL",
                "https://query.us-ashburn-1.oci.oraclecloud.com/20180409/resources", "mock-oci-token"
        );
        gateway.setRestClientForTesting(builder.build());

        mockServer.expect(requestTo("https://resource-explorer-2.us-east-1.amazonaws.com/Search"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"Resources":[{"Arn":"arn:aws:eks:us-east-1:123:cluster/payments-eks","ResourceType":"AWS::EKS::Cluster","Region":"us-east-1"}]}
                        """, MediaType.APPLICATION_JSON));

        mockServer.expect(requestTo("https://cloudasset.googleapis.com/v1/projects/uacrf-prod:searchAllResources"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"results":[{"name":"//container.googleapis.com/projects/uacrf-prod/locations/us-central1/clusters/gke-checkout","assetType":"container.googleapis.com/Cluster","location":"us-central1","displayName":"gke-checkout"}]}
                        """, MediaType.APPLICATION_JSON));

        mockServer.expect(requestTo("https://management.azure.com/providers/Microsoft.ResourceGraph/resources?api-version=2022-10-01"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"data":[{"id":"/subscriptions/sub-1/resourceGroups/rg/providers/Microsoft.DocumentDB/databaseAccounts/cosmos-orders","type":"Microsoft.DocumentDB/databaseAccounts","name":"cosmos-orders","location":"eastus"}]}
                        """, MediaType.APPLICATION_JSON));

        mockServer.expect(requestTo("https://query.us-ashburn-1.oci.oraclecloud.com/20180409/resources"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"items":[{"identifier":"ocid1.autonomousdatabase.oc1.us-ashburn-1.demo","resourceType":"oci:database:autonomousdatabase","displayName":"ledger-adb","availabilityDomain":"AD-1"}]}
                        """, MediaType.APPLICATION_JSON));

        var aws = gateway.pollAndNormalize(TENANT_ID, ENV_ID, "AWS", "*");
        assertEquals(1, aws.assetsNormalized());
        assertEquals(1, aws.computePlacements().size());
        assertEquals("EKS", aws.computePlacements().getFirst().runtimeKind());
        assertEquals("Cluster", aws.edges().getFirst().targetLabel());

        var gcp = gateway.pollAndNormalize(TENANT_ID, ENV_ID, "GCP", "*");
        assertEquals(1, gcp.assetsNormalized());
        assertEquals("GKE", gcp.computePlacements().getFirst().runtimeKind());

        var azure = gateway.pollAndNormalize(TENANT_ID, ENV_ID, "AZURE", "*");
        assertEquals("Database", azure.edges().getFirst().targetLabel());

        var oci = gateway.pollAndNormalize(TENANT_ID, ENV_ID, "OCI", "*");
        assertTrue(oci.aliases().getFirst().sourceSystem().contains("OCI"));
        assertTrue(oci.aliases().getFirst().providerResourceId().contains("us-ashburn-1"));
        assertEquals("Database", oci.edges().getFirst().targetLabel());

        mockServer.verify();
    }

    @Test
    void normalizesMixedMultiCloudComputeStorageAndAiAssetsWithPiiScrubbing() {
        CloudInventoryPollerGateway gateway = buildStandbyGateway();
        var batch = gateway.normalizeBatch(TENANT_ID, ENV_ID, "AWS", "MOCKED_FIXTURE", List.of(
                new RawCloudAssetRecord("arn:aws:autoscaling:us-east-1:111:autoScalingGroup/checkout-asg-alice@example.com",
                        "AWS::EC2::AutoScalingGroup", "checkout-asg", "urn:uacrf:svc:checkout",
                        "us-east-1", "us-east-1a", "fd-aws-us-east-1", "ASG", 2, 12, 4, Map.of()),
                new RawCloudAssetRecord("arn:aws:sagemaker:us-east-1:111:endpoint/fraud-v17",
                        "AWS::SageMaker::Endpoint", "fraud-v17", "urn:uacrf:svc:payment",
                        "us-east-1", "us-east-1b", "fd-aws-us-east-1", null, null, null, null, Map.of())
        ));
        assertEquals(2, batch.assetsNormalized());
        assertEquals(4, batch.edges().size());
        assertEquals(1, batch.computePlacements().size());
        assertTrue(batch.aliases().stream().noneMatch(a -> a.providerResourceId().contains("alice@example.com")));

        var gcpIamAndBilling = gateway.normalizeBatch(TENANT_ID, ENV_ID, "GCP", "CLOUD_ASSET_FIXTURE", List.of(
                new RawCloudAssetRecord("//cloudbilling.googleapis.com/projects/uacrf-sandbox-123/billingInfo",
                        "cloudbilling.googleapis.com/ProjectBillingInfo", "billingInfo", null,
                        "global", null, null, null, null, null, null, Map.of()),
                new RawCloudAssetRecord("//iam.googleapis.com/projects/uacrf-sandbox-123/serviceAccounts/123",
                        "iam.googleapis.com/ServiceAccount", "uacrf-discovery-ro", null,
                        "global", null, null, null, null, null, null, Map.of())
        ));
        assertEquals("CloudResource", gcpIamAndBilling.edges().get(0).targetLabel());
        assertEquals("Identity", gcpIamAndBilling.edges().get(2).targetLabel());
    }

    @Test
    void exchangesAuthorizedUserAdcAndServiceAccountJwtForGcpAccessTokens(@TempDir Path tempDir) throws Exception {
        // 1. Authorized User ADC refresh flow (gcloud auth application-default login output format)
        Path adcFile = tempDir.resolve("adc_user.json");
        Files.writeString(adcFile, """
                {"type":"authorized_user","client_id":"cid-1","client_secret":"csec-1","refresh_token":"rtok-1"}
                """);
        RestClient.Builder adcBuilder = RestClient.builder();
        MockRestServiceServer adcMock = MockRestServiceServer.bindTo(adcBuilder).build();
        adcMock.expect(requestTo("https://oauth2.googleapis.com/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"ya29.adc-refreshed-token\"}", MediaType.APPLICATION_JSON));

        GcpCredentialProvider userAdc = new GcpCredentialProvider("", adcFile.toString(), "https://oauth2.googleapis.com/token");
        userAdc.setRestClientForTesting(adcBuilder.build());
        assertEquals("ya29.adc-refreshed-token", userAdc.resolveAccessToken());
        adcMock.verify();

        // 2. Service Account JWT assertion flow
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";
        Path saFile = tempDir.resolve("sa_key.json");
        Files.writeString(saFile, "{\"type\":\"service_account\",\"client_email\":\"uacrf-discovery-ro@sandbox.iam.gserviceaccount.com\","
                + "\"private_key\":\"" + pem.replace("\n", "\\n") + "\",\"token_uri\":\"https://oauth2.googleapis.com/token\"}");

        RestClient.Builder saBuilder = RestClient.builder();
        MockRestServiceServer saMock = MockRestServiceServer.bindTo(saBuilder).build();
        saMock.expect(requestTo("https://oauth2.googleapis.com/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"ya29.sa-jwt-token\"}", MediaType.APPLICATION_JSON));

        GcpCredentialProvider saCreds = new GcpCredentialProvider("", saFile.toString(), "https://oauth2.googleapis.com/token");
        saCreds.setRestClientForTesting(saBuilder.build());
        assertEquals("ya29.sa-jwt-token", saCreds.resolveAccessToken());
        saMock.verify();
    }

    @Test
    void pollsMockedGcpMonitoringMetricsAndCloudLoggingAuditEntriesWithPiiScrubbing() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();

        GcpRuntimeTelemetryPoller poller = new GcpRuntimeTelemetryPoller(
                new PiiPhiScrubber(),
                new GcpCredentialProvider("mock-gcp-token", "", "https://oauth2.googleapis.com/token"),
                true, "uacrf-sandbox-123",
                "https://monitoring.googleapis.com",
                "https://logging.googleapis.com"
        );
        poller.setRestClientForTesting(builder.build());

        mockServer.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "https://monitoring.googleapis.com/v3/projects/uacrf-sandbox-123/timeSeries")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"timeSeries":[{"metric":{"type":"storage.googleapis.com/storage/total_bytes"},"resource":{"type":"gcs_bucket","labels":{"bucket_name":"sample_gcp_bucket_uacrf"}},"unit":"By","points":[{"interval":{"endTime":"2026-11-28T04:00:00Z"},"value":{"doubleValue":1024.0}}]}]}
                        """, MediaType.APPLICATION_JSON));

        mockServer.expect(requestTo("https://logging.googleapis.com/v2/entries:list"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"entries":[{"logName":"projects/uacrf-sandbox-123/logs/cloudaudit.googleapis.com%2Factivity","severity":"NOTICE","timestamp":"2026-11-28T04:01:00Z","resource":{"type":"gcs_bucket"},"protoPayload":{"methodName":"storage.buckets.create","serviceName":"storage.googleapis.com"}}]}
                        """, MediaType.APPLICATION_JSON));

        var result = poller.poll(new GcpRuntimeTelemetryPollRequest(
                TENANT_ID, ENV_ID, "uacrf-sandbox-123", "metric.type = \"storage.googleapis.com/storage/total_bytes\"",
                true, true, 60, false));
        assertEquals("GCP", result.provider());
        assertEquals(1, result.metricsCount());
        assertEquals(1, result.logsCount());
        assertEquals(2, result.observations().size());
        assertTrue(result.observations().stream().anyMatch(o -> "STORAGE_METRIC".equals(o.signalType()) && o.metricValue() == 1024.0));
        assertTrue(result.observations().stream().anyMatch(o -> "DEPLOYMENT_CHANGE".equals(o.signalType())));
        mockServer.verify();
    }
}
