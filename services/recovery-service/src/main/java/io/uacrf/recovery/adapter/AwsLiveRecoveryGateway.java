package io.uacrf.recovery.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.uacrf.contracts.authority.BoundedRecoveryAuthorization;
import io.uacrf.crypto.AwsSigV4SignerSupport;
import io.uacrf.crypto.AwsSigV4SignerSupport.AwsCredentials;
import io.uacrf.crypto.GcpOAuthAssertionSupport;
import io.uacrf.recovery.api.RecoveryDomainException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

/**
 * Toggle-gated AWS Live Recovery Execution Gateway (S11 / NFR-SEC-003).
 * Uses ONLY dedicated S11 execution credentials (UACRF_RECOVERY_AWS_*), strictly separated from S03 discovery,
 * signs live requests via AWS SigV4 (AWS4-HMAC-SHA256), and enforces P3 target/capacity bounds.
 */
@Component
public class AwsLiveRecoveryGateway {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final boolean enabled;
    private final String region;
    private final String ecsCluster;
    private final String accessKeyId;
    private final String secretAccessKey;
    private final String sessionToken;
    private final String credentialsFile;
    private final String profile;
    private final String autoscalingBaseUrl;
    private final String ecsBaseUrl;
    private RestClient restClient;

    @Autowired
    public AwsLiveRecoveryGateway(
            @Value("${uacrf.recovery.adapters.aws.enabled:false}") boolean enabled,
            @Value("${uacrf.recovery.adapters.aws.region:us-east-1}") String region,
            @Value("${uacrf.recovery.adapters.aws.ecs-cluster:default}") String ecsCluster,
            @Value("${uacrf.recovery.adapters.aws.access-key-id:}") String accessKeyId,
            @Value("${uacrf.recovery.adapters.aws.secret-access-key:}") String secretAccessKey,
            @Value("${uacrf.recovery.adapters.aws.session-token:}") String sessionToken,
            @Value("${uacrf.recovery.adapters.aws.credentials-file:}") String credentialsFile,
            @Value("${uacrf.recovery.adapters.aws.profile:uacrf-exec}") String profile,
            @Value("${uacrf.recovery.adapters.aws.autoscaling-base-url:}") String autoscalingBaseUrl,
            @Value("${uacrf.recovery.adapters.aws.ecs-base-url:}") String ecsBaseUrl) {
        this.enabled = enabled;
        this.region = region;
        this.ecsCluster = ecsCluster;
        this.accessKeyId = accessKeyId;
        this.secretAccessKey = secretAccessKey;
        this.sessionToken = sessionToken;
        this.credentialsFile = credentialsFile;
        this.profile = profile;
        this.autoscalingBaseUrl = stripTrailingSlash(isBlank(autoscalingBaseUrl)
                ? "https://autoscaling." + region + ".amazonaws.com" : autoscalingBaseUrl);
        this.ecsBaseUrl = stripTrailingSlash(isBlank(ecsBaseUrl)
                ? "https://ecs." + region + ".amazonaws.com" : ecsBaseUrl);
        this.restClient = RestClient.create();
    }

    public void setRestClientForTesting(RestClient customClient) {
        this.restClient = customClient;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String executeAwsMutation(BoundedRecoveryAuthorization auth, String targetCanonicalId, String targetVersion) {
        return dispatchAwsMutation(auth, targetCanonicalId, targetVersion, false);
    }

    public String compensateAwsMutation(BoundedRecoveryAuthorization auth, String targetCanonicalId, String previousVersion) {
        return dispatchAwsMutation(auth, targetCanonicalId, previousVersion, true);
    }

    private String dispatchAwsMutation(
            BoundedRecoveryAuthorization auth, String targetCanonicalId, String desiredVersion, boolean compensating) {
        if (!enabled) {
            throw new RecoveryDomainException(HttpStatus.PRECONDITION_FAILED, "AWS_LIVE_ADAPTER_DISABLED",
                    "AWS live execution adapter is disabled (uacrf.recovery.adapters.aws.enabled=false).");
        }
        AwsCredentials creds = resolveSeparateExecutionCredentials();
        String slug = extractResourceSlug(targetCanonicalId);
        String tag = compensating ? "[LIVE_AWS_COMPENSATED]" : "[LIVE_AWS_MUTATION]";

        if ("AWS_ASG_SCALE".equals(auth.actionType()) || "CLOUD_CAPACITY_SCALE".equals(auth.actionType())) {
            int requestedSize = parseIntOrDefault(desiredVersion, Math.max(1, auth.maximumTargets()));
            int boundedSize = Math.min(Math.max(1, auth.maximumTargets()), Math.max(1, requestedSize));
            URI uri = URI.create(autoscalingBaseUrl + "/");
            validateSafeEndpoint(uri.toString());
            String formBody = "Action=SetDesiredCapacity&Version=2011-01-01"
                    + "&AutoScalingGroupName=" + urlEnc(slug)
                    + "&DesiredCapacity=" + boundedSize
                    + "&HonorCooldown=false";
            byte[] bodyBytes = formBody.getBytes(StandardCharsets.UTF_8);
            var spec = restClient.post().uri(uri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED);
            AwsSigV4SignerSupport.signRequest(
                            "POST", uri, "autoscaling", region,
                            Map.of("Content-Type", "application/x-www-form-urlencoded"),
                            bodyBytes, creds, Instant.now())
                    .forEach(spec::header);
            spec.body(formBody).retrieve().toBodilessEntity();
            return tag + " Set AWS Auto Scaling Group [" + slug + "] in " + region
                    + " desiredCapacity=" + boundedSize + " (maxTargetsCap=" + auth.maximumTargets() + ").";
        }

        if ("AWS_ECS_ROLLBACK".equals(auth.actionType()) || "TRAFFIC_SHIFT_ROLLBACK".equals(auth.actionType())) {
            URI uri = URI.create(ecsBaseUrl + "/");
            validateSafeEndpoint(uri.toString());
            String jsonBody;
            try {
                jsonBody = objectMapper.writeValueAsString(Map.of(
                        "cluster", ecsCluster,
                        "service", slug,
                        "taskDefinition", desiredVersion,
                        "forceNewDeployment", false
                ));
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
            byte[] bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);
            var spec = restClient.post().uri(uri)
                    .contentType(MediaType.valueOf("application/x-amz-json-1.1"))
                    .header("X-Amz-Target", "AmazonEC2ContainerServiceV20141113.UpdateService");
            AwsSigV4SignerSupport.signRequest(
                            "POST", uri, "ecs", region,
                            Map.of(
                                    "Content-Type", "application/x-amz-json-1.1",
                                    "X-Amz-Target", "AmazonEC2ContainerServiceV20141113.UpdateService"
                            ),
                            bodyBytes, creds, Instant.now())
                    .forEach(spec::header);
            spec.body(jsonBody).retrieve().toBodilessEntity();
            return tag + " Updated AWS ECS service [" + slug + "] in cluster [" + ecsCluster + "/" + region
                    + "] to taskDefinition=" + desiredVersion + " (authId=" + auth.authorizationId() + ").";
        }

        throw new RecoveryDomainException(HttpStatus.PRECONDITION_FAILED, "UNSUPPORTED_LIVE_AWS_ACTION",
                "Action [" + auth.actionType() + "] is not supported for live AWS mutation.");
    }

    /**
     * Enforces NFR-SEC-003 by reading ONLY S11-dedicated AWS credentials (never env AWS_ACCESS_KEY_ID / S03 credentials).
     */
    private AwsCredentials resolveSeparateExecutionCredentials() {
        AwsCredentials creds = (!isBlank(accessKeyId) && !isBlank(secretAccessKey))
                ? new AwsCredentials(accessKeyId.trim(), secretAccessKey.trim(), sessionToken == null ? "" : sessionToken.trim())
                : (!isBlank(credentialsFile)
                        ? AwsSigV4SignerSupport.resolveCredentials("", "", "", credentialsFile, profile, Map.of(), "")
                        : new AwsCredentials("", "", ""));
        if (!creds.isComplete()) {
            throw new RecoveryDomainException(HttpStatus.PRECONDITION_FAILED, "MISSING_EXECUTION_CREDENTIALS",
                    "NFR-SEC-003: Separate AWS execution credentials (UACRF_RECOVERY_AWS_ACCESS_KEY_ID / SECRET_ACCESS_KEY or UACRF_RECOVERY_AWS_CREDENTIALS_FILE) are required.");
        }
        return creds;
    }

    public void validateSafeEndpoint(String url) {
        if (GcpOAuthAssertionSupport.isUnsafeEgressHost(url)) {
            String host = url == null ? "" : URI.create(url).getHost();
            throw new RecoveryDomainException(HttpStatus.FORBIDDEN, "SSRF_EGRESS_BLOCKED",
                    "Blocked unsafe AWS recovery target host [" + host + "] per SSRF egress policy.");
        }
    }

    private static String extractResourceSlug(String targetCanonicalId) {
        if (isBlank(targetCanonicalId)) {
            return "default";
        }
        int colonIdx = targetCanonicalId.lastIndexOf(':');
        int slashIdx = targetCanonicalId.lastIndexOf('/');
        int idx = Math.max(colonIdx, slashIdx);
        String slug = idx >= 0 && idx + 1 < targetCanonicalId.length() ? targetCanonicalId.substring(idx + 1) : targetCanonicalId;
        return slug.isBlank() ? "default" : slug;
    }

    private static int parseIntOrDefault(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception ex) {
            return fallback;
        }
    }

    private static String urlEnc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    private static String stripTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
