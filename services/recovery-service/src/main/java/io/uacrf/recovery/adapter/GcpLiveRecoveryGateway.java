package io.uacrf.recovery.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.uacrf.contracts.authority.BoundedRecoveryAuthorization;
import io.uacrf.recovery.api.RecoveryDomainException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Toggle-gated GCP Live Recovery Execution Gateway (S11 / NFR-SEC-003).
 * Uses ONLY dedicated execution credentials (UACRF_RECOVERY_GCP_CREDENTIALS_FILE / bearer token)
 * strictly separate from S03 read-only discovery credentials, and enforces P3 traffic & target caps.
 */
@Component
public class GcpLiveRecoveryGateway {

    private static final String CLOUD_PLATFORM_SCOPE = "https://www.googleapis.com/auth/cloud-platform";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final boolean enabled;
    private final String projectId;
    private final String region;
    private final String zone;
    private final String credentialsFile;
    private final String explicitToken;
    private final String oauthTokenUrl;
    private final String runBaseUrl;
    private final String computeBaseUrl;
    private RestClient restClient;

    @Autowired
    public GcpLiveRecoveryGateway(
            @Value("${uacrf.recovery.adapters.gcp.enabled:false}") boolean enabled,
            @Value("${uacrf.recovery.adapters.gcp.project-id:uacrf-unconfigured}") String projectId,
            @Value("${uacrf.recovery.adapters.gcp.region:us-central1}") String region,
            @Value("${uacrf.recovery.adapters.gcp.zone:us-central1-a}") String zone,
            @Value("${uacrf.recovery.adapters.gcp.credentials-file:}") String credentialsFile,
            @Value("${uacrf.recovery.adapters.gcp.bearer-token:}") String explicitToken,
            @Value("${uacrf.recovery.adapters.gcp.oauth-token-url:https://oauth2.googleapis.com/token}") String oauthTokenUrl,
            @Value("${uacrf.recovery.adapters.gcp.run-base-url:https://run.googleapis.com}") String runBaseUrl,
            @Value("${uacrf.recovery.adapters.gcp.compute-base-url:https://compute.googleapis.com}") String computeBaseUrl) {
        this.enabled = enabled;
        this.projectId = projectId;
        this.region = region;
        this.zone = zone;
        this.credentialsFile = credentialsFile;
        this.explicitToken = explicitToken;
        this.oauthTokenUrl = stripTrailingSlash(oauthTokenUrl);
        this.runBaseUrl = stripTrailingSlash(runBaseUrl);
        this.computeBaseUrl = stripTrailingSlash(computeBaseUrl);
        this.restClient = RestClient.builder().requestFactory(new JdkClientHttpRequestFactory()).build();
    }

    public void setRestClientForTesting(RestClient customClient) {
        this.restClient = customClient;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String executeGcpMutation(BoundedRecoveryAuthorization auth, String targetCanonicalId, String targetVersion) {
        ensureLiveReady();
        String token = resolveSeparateExecutionToken();
        String serviceSlug = extractResourceSlug(targetCanonicalId);
        int boundedPercent = Math.min(100, Math.max(1, auth.maximumTrafficPercentage()));

        if ("CLOUD_RUN_TRAFFIC_SPLIT".equals(auth.actionType()) || "TRAFFIC_SHIFT_ROLLBACK".equals(auth.actionType())) {
            String url = runBaseUrl + "/v2/projects/" + projectId + "/locations/" + region
                    + "/services/" + serviceSlug + "?updateMask=traffic";
            validateSafeEndpoint(url);
            Map<String, Object> payload = Map.of(
                    "traffic", List.of(Map.of(
                            "type", "TRAFFIC_TARGET_ALLOCATION_TYPE_REVISION",
                            "revision", targetVersion,
                            "percent", boundedPercent
                    ))
            );
            restClient.patch().uri(url)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve().toBodilessEntity();
            return "[LIVE_GCP_MUTATION] Updated Cloud Run service [" + serviceSlug + "] in " + projectId + "/" + region
                    + " to revision=" + targetVersion + " (trafficCap=" + boundedPercent + "%, authId=" + auth.authorizationId() + ").";
        }

        if ("GCP_MIG_ROLLBACK".equals(auth.actionType()) || "CLOUD_CAPACITY_SCALE".equals(auth.actionType())) {
            int requestedSize = parseIntOrDefault(targetVersion, Math.max(1, auth.maximumTargets()));
            int boundedSize = Math.min(Math.max(1, auth.maximumTargets()), Math.max(1, requestedSize));
            String url = computeBaseUrl + "/compute/v1/projects/" + projectId + "/zones/" + zone
                    + "/instanceGroupManagers/" + serviceSlug + "/resize?size=" + boundedSize;
            validateSafeEndpoint(url);
            restClient.post().uri(url)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve().toBodilessEntity();
            return "[LIVE_GCP_MUTATION] Resized Compute MIG [" + serviceSlug + "] in " + projectId + "/" + zone
                    + " to size=" + boundedSize + " (maxTargetsCap=" + auth.maximumTargets() + ").";
        }

        throw new RecoveryDomainException(HttpStatus.PRECONDITION_FAILED, "UNSUPPORTED_LIVE_GCP_ACTION",
                "Action [" + auth.actionType() + "] is not enabled for live GCP mutation.");
    }

    public String compensateGcpMutation(BoundedRecoveryAuthorization auth, String targetCanonicalId, String previousVersion) {
        ensureLiveReady();
        String token = resolveSeparateExecutionToken();
        String serviceSlug = extractResourceSlug(targetCanonicalId);
        int restorePercent = Math.min(100, Math.max(1, auth.maximumTrafficPercentage()));

        if ("CLOUD_RUN_TRAFFIC_SPLIT".equals(auth.actionType()) || "TRAFFIC_SHIFT_ROLLBACK".equals(auth.actionType())) {
            String url = runBaseUrl + "/v2/projects/" + projectId + "/locations/" + region
                    + "/services/" + serviceSlug + "?updateMask=traffic";
            validateSafeEndpoint(url);
            Map<String, Object> payload = Map.of(
                    "traffic", List.of(Map.of(
                            "type", "TRAFFIC_TARGET_ALLOCATION_TYPE_REVISION",
                            "revision", previousVersion,
                            "percent", restorePercent
                    ))
            );
            restClient.patch().uri(url)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve().toBodilessEntity();
            return "[LIVE_GCP_COMPENSATED] Restored Cloud Run service [" + serviceSlug + "] back to prior revision=" + previousVersion + ".";
        }

        int fallbackSize = parseIntOrDefault(previousVersion, 1);
        int boundedFallback = Math.min(Math.max(1, auth.maximumTargets()), Math.max(1, fallbackSize));
        String url = computeBaseUrl + "/compute/v1/projects/" + projectId + "/zones/" + zone
                + "/instanceGroupManagers/" + serviceSlug + "/resize?size=" + boundedFallback;
        validateSafeEndpoint(url);
        restClient.post().uri(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve().toBodilessEntity();
        return "[LIVE_GCP_COMPENSATED] Restored Compute MIG [" + serviceSlug + "] back to size=" + boundedFallback + ".";
    }

    private void ensureLiveReady() {
        if (!enabled) {
            throw new RecoveryDomainException(HttpStatus.PRECONDITION_FAILED, "GCP_LIVE_ADAPTER_DISABLED",
                    "GCP live execution adapter is disabled (uacrf.recovery.adapters.gcp.enabled=false).");
        }
        if (projectId == null || projectId.isBlank() || "uacrf-unconfigured".equals(projectId)) {
            throw new RecoveryDomainException(HttpStatus.PRECONDITION_FAILED, "GCP_PROJECT_UNCONFIGURED",
                    "Set uacrf.recovery.adapters.gcp.project-id before executing live GCP recovery.");
        }
    }

    /**
     * Enforces NFR-SEC-003 by reading ONLY S11-dedicated execution credentials, never S03 discovery credentials.
     */
    private String resolveSeparateExecutionToken() {
        if (explicitToken != null && !explicitToken.isBlank()) {
            return explicitToken.trim();
        }
        if (credentialsFile == null || credentialsFile.isBlank()) {
            throw new RecoveryDomainException(HttpStatus.PRECONDITION_FAILED, "MISSING_EXECUTION_CREDENTIALS",
                    "NFR-SEC-003: Separate GCP execution credentials (UACRF_RECOVERY_GCP_CREDENTIALS_FILE or UACRF_RECOVERY_GCP_TOKEN) are required.");
        }
        Path path = Path.of(credentialsFile.trim());
        if (!Files.isRegularFile(path)) {
            throw new RecoveryDomainException(HttpStatus.PRECONDITION_FAILED, "MISSING_EXECUTION_CREDENTIALS",
                    "Configured GCP execution credentials file not found: " + credentialsFile);
        }
        try {
            JsonNode creds = objectMapper.readTree(Files.readString(path));
            if (!"service_account".equals(creds.path("type").asText(""))) {
                throw new RecoveryDomainException(HttpStatus.PRECONDITION_FAILED, "UNSUPPORTED_EXECUTION_CREDENTIAL_TYPE",
                        "Live GCP execution requires a dedicated service_account credential.");
            }
            String tokenUri = !creds.path("token_uri").asText("").isBlank()
                    ? creds.path("token_uri").asText()
                    : oauthTokenUrl;
            validateSafeEndpoint(tokenUri);
            String assertion = signServiceAccountAssertion(
                    creds.path("client_email").asText(), creds.path("private_key").asText(), tokenUri);
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
            form.add("assertion", assertion);
            String raw = restClient.post().uri(tokenUri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve().body(String.class);
            String accessToken = objectMapper.readTree(raw).path("access_token").asText("");
            if (accessToken.isBlank()) {
                throw new RecoveryDomainException(HttpStatus.BAD_GATEWAY, "GCP_TOKEN_EXCHANGE_FAILED",
                        "OAuth2 token endpoint returned no access_token for recovery execution.");
            }
            return accessToken;
        } catch (RecoveryDomainException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RecoveryDomainException(HttpStatus.PRECONDITION_FAILED, "INVALID_EXECUTION_CREDENTIALS",
                    "Failed to resolve live GCP execution OAuth2 token: " + ex.getMessage());
        }
    }

    public void validateSafeEndpoint(String url) {
        URI uri = URI.create(url);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.equals("169.254.169.254") || host.equals("metadata.google.internal")
                || host.startsWith("169.254.") || host.equals("localhost") || host.equals("127.0.0.1")) {
            throw new RecoveryDomainException(HttpStatus.FORBIDDEN, "SSRF_EGRESS_BLOCKED",
                    "Blocked unsafe GCP recovery target host [" + host + "] per SSRF egress policy.");
        }
    }

    private String signServiceAccountAssertion(String clientEmail, String privateKeyPem, String audience) throws Exception {
        long now = Instant.now().getEpochSecond();
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String header = b64.encodeToString(objectMapper.writeValueAsBytes(Map.of("alg", "RS256", "typ", "JWT")));
        String payload = b64.encodeToString(objectMapper.writeValueAsBytes(Map.of(
                "iss", clientEmail,
                "scope", CLOUD_PLATFORM_SCOPE,
                "aud", audience,
                "iat", now,
                "exp", now + 300
        )));
        String signingInput = header + "." + payload;

        String cleanPem = privateKeyPem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] keyBytes = Base64.getDecoder().decode(cleanPem);
        PrivateKey privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));

        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(privateKey);
        sig.update(signingInput.getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + b64.encodeToString(sig.sign());
    }

    private static String extractResourceSlug(String canonicalId) {
        int idx = canonicalId.lastIndexOf(':');
        return idx >= 0 && idx + 1 < canonicalId.length() ? canonicalId.substring(idx + 1) : canonicalId;
    }

    private static int parseIntOrDefault(String raw, int fallback) {
        if (raw == null) return fallback;
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.isBlank()) return fallback;
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
