package io.uacrf.discovery.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.uacrf.discovery.api.DiscoveryDomainException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import java.util.Locale;
import java.util.Map;

/**
 * Resolves short-lived read-only Google Cloud OAuth2 access tokens for Cloud Asset Inventory polling.
 * Resolution order: explicit bearer token -> configured GOOGLE_APPLICATION_CREDENTIALS file ->
 * standard gcloud ADC file (~/.config/gcloud/application_default_credentials.json).
 */
@Component
public class GcpCredentialProvider {

    private static final String CLOUD_PLATFORM_SCOPE = "https://www.googleapis.com/auth/cloud-platform";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String explicitToken;
    private final String credentialsFile;
    private final String oauthTokenUrl;
    private RestClient restClient;

    @Autowired
    public GcpCredentialProvider(
            @Value("${uacrf.discovery.adapters.gcp.bearer-token:}") String explicitToken,
            @Value("${uacrf.discovery.adapters.gcp.credentials-file:}") String credentialsFile,
            @Value("${uacrf.discovery.adapters.gcp.oauth-token-url:https://oauth2.googleapis.com/token}") String oauthTokenUrl) {
        this.explicitToken = explicitToken;
        this.credentialsFile = credentialsFile;
        this.oauthTokenUrl = oauthTokenUrl;
        this.restClient = RestClient.create();
    }

    public void setRestClientForTesting(RestClient customClient) {
        this.restClient = customClient;
    }

    public String resolveAccessToken() {
        if (explicitToken != null && !explicitToken.isBlank()) {
            return explicitToken.trim();
        }
        Path credsPath = resolveCredentialsPath();
        if (credsPath == null || !Files.isRegularFile(credsPath)) {
            return "";
        }
        try {
            JsonNode creds = objectMapper.readTree(Files.readString(credsPath));
            String type = creds.path("type").asText("");
            String tokenUri = !creds.path("token_uri").asText("").isBlank()
                    ? creds.path("token_uri").asText()
                    : oauthTokenUrl;
            validateSafeTokenEndpoint(tokenUri);
            return switch (type) {
                case "authorized_user" -> exchangeRefreshToken(creds, tokenUri);
                case "service_account" -> exchangeServiceAccountJwt(creds, tokenUri);
                default -> "";
            };
        } catch (DiscoveryDomainException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new DiscoveryDomainException(HttpStatus.PRECONDITION_FAILED, "INVALID_GCP_CREDENTIALS",
                    "Failed to resolve GCP OAuth2 access token from ADC credentials: " + ex.getMessage());
        }
    }

    private Path resolveCredentialsPath() {
        if (credentialsFile != null && !credentialsFile.isBlank()) {
            return Path.of(credentialsFile.trim());
        }
        String envCreds = System.getenv("GOOGLE_APPLICATION_CREDENTIALS");
        if (envCreds != null && !envCreds.isBlank()) {
            return Path.of(envCreds.trim());
        }
        String home = System.getProperty("user.home", "");
        if (!home.isBlank()) {
            return Path.of(home, ".config", "gcloud", "application_default_credentials.json");
        }
        return null;
    }

    private String exchangeRefreshToken(JsonNode creds, String tokenUri) throws Exception {
        String clientId = creds.path("client_id").asText("");
        String clientSecret = creds.path("client_secret").asText("");
        String refreshToken = creds.path("refresh_token").asText("");
        if (clientId.isBlank() || clientSecret.isBlank() || refreshToken.isBlank()) {
            return "";
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("refresh_token", refreshToken);
        return executeTokenRequest(tokenUri, form);
    }

    private String exchangeServiceAccountJwt(JsonNode creds, String tokenUri) throws Exception {
        String clientEmail = creds.path("client_email").asText("");
        String privateKeyPem = creds.path("private_key").asText("");
        if (clientEmail.isBlank() || privateKeyPem.isBlank()) {
            return "";
        }
        String jwtAssertion = signServiceAccountAssertion(clientEmail, privateKeyPem, tokenUri);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
        form.add("assertion", jwtAssertion);
        return executeTokenRequest(tokenUri, form);
    }

    private String executeTokenRequest(String tokenUri, MultiValueMap<String, String> form) throws Exception {
        String raw = restClient.post().uri(tokenUri)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve().body(String.class);
        if (raw == null || raw.isBlank()) {
            return "";
        }
        JsonNode json = objectMapper.readTree(raw);
        return json.path("access_token").asText("");
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
                "exp", now + 3600
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

    private void validateSafeTokenEndpoint(String url) {
        URI uri = URI.create(url);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.equals("169.254.169.254") || host.equals("metadata.google.internal")
                || host.startsWith("169.254.") || host.equals("localhost") || host.equals("127.0.0.1")) {
            throw new DiscoveryDomainException(HttpStatus.FORBIDDEN, "SSRF_EGRESS_BLOCKED",
                    "Blocked unsafe OAuth2 token host [" + host + "].");
        }
    }
}
