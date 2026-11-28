package io.uacrf.advisory.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.uacrf.advisory.api.AdvisoryDomainException;
import io.uacrf.crypto.GcpOAuthAssertionSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Resolves short-lived Google Cloud OAuth2 access tokens for Vertex AI advisory calls (S09).
 * Resolution order: explicit bearer/API token -> uacrf.advisory.llm.google.credentials-file ->
 * GOOGLE_APPLICATION_CREDENTIALS -> ~/.config/gcloud/application_default_credentials.json.
 */
@Component
public class GcpVertexCredentialResolver {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String credentialsFile;
    private final String oauthTokenUrl;
    private final AtomicReference<CachedToken> cachedToken = new AtomicReference<>();
    private RestClient restClient;

    @Autowired
    public GcpVertexCredentialResolver(
            @Value("${uacrf.advisory.llm.google.credentials-file:}") String credentialsFile,
            @Value("${uacrf.advisory.llm.google.oauth-token-url:https://oauth2.googleapis.com/token}") String oauthTokenUrl) {
        this.credentialsFile = credentialsFile;
        this.oauthTokenUrl = oauthTokenUrl;
        this.restClient = RestClient.create();
    }

    public void setRestClientForTesting(RestClient customClient) {
        this.restClient = customClient;
        this.cachedToken.set(null);
    }

    public boolean hasLocalCredentialsConfigured() {
        Path path = resolveCredentialsPath();
        return path != null && Files.isRegularFile(path);
    }

    public String resolveAccessToken(String explicitToken) {
        if (explicitToken != null && !explicitToken.isBlank()) {
            return explicitToken.trim();
        }
        CachedToken current = cachedToken.get();
        Instant now = Instant.now();
        if (current != null && current.expiresAt().isAfter(now.plusSeconds(30))) {
            return current.accessToken();
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
            if (GcpOAuthAssertionSupport.isUnsafeEgressHost(tokenUri)) {
                throw new AdvisoryDomainException(HttpStatus.FORBIDDEN, "SSRF_EGRESS_BLOCKED",
                        "Blocked unsafe Vertex OAuth2 token host in URI [" + tokenUri + "].");
            }
            CachedToken exchanged = switch (type) {
                case "authorized_user" -> exchangeRefreshToken(creds, tokenUri);
                case "service_account" -> exchangeServiceAccountJwt(creds, tokenUri);
                default -> null;
            };
            if (exchanged == null || exchanged.accessToken().isBlank()) {
                return "";
            }
            cachedToken.set(exchanged);
            return exchanged.accessToken();
        } catch (AdvisoryDomainException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AdvisoryDomainException(HttpStatus.PRECONDITION_FAILED, "INVALID_GCP_CREDENTIALS",
                    "Failed to resolve Vertex AI OAuth2 access token from GCP credentials: " + ex.getMessage());
        }
    }

    private Path resolveCredentialsPath() {
        return GcpOAuthAssertionSupport.resolveCredentialsPath(
                credentialsFile,
                System.getenv("GOOGLE_APPLICATION_CREDENTIALS"),
                System.getProperty("user.home", "")
        );
    }

    private CachedToken exchangeRefreshToken(JsonNode creds, String tokenUri) throws Exception {
        String clientId = creds.path("client_id").asText("");
        String clientSecret = creds.path("client_secret").asText("");
        String refreshToken = creds.path("refresh_token").asText("");
        if (clientId.isBlank() || clientSecret.isBlank() || refreshToken.isBlank()) {
            return null;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("refresh_token", refreshToken);
        return executeTokenRequest(tokenUri, form);
    }

    private CachedToken exchangeServiceAccountJwt(JsonNode creds, String tokenUri) throws Exception {
        String clientEmail = creds.path("client_email").asText("");
        String privateKeyPem = creds.path("private_key").asText("");
        if (clientEmail.isBlank() || privateKeyPem.isBlank()) {
            return null;
        }
        String jwtAssertion = GcpOAuthAssertionSupport.signServiceAccountAssertion(
                clientEmail,
                privateKeyPem,
                GcpOAuthAssertionSupport.CLOUD_PLATFORM_SCOPE,
                tokenUri,
                Instant.now()
        );
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
        form.add("assertion", jwtAssertion);
        return executeTokenRequest(tokenUri, form);
    }

    private CachedToken executeTokenRequest(String tokenUri, MultiValueMap<String, String> form) throws Exception {
        String raw = restClient.post().uri(tokenUri)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String.class);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        JsonNode json = objectMapper.readTree(raw);
        String accessToken = json.path("access_token").asText("");
        long expiresIn = json.path("expires_in").asLong(3000L);
        return new CachedToken(accessToken, Instant.now().plusSeconds(Math.max(60L, expiresIn - 60L)));
    }

    private record CachedToken(String accessToken, Instant expiresAt) {
    }
}
