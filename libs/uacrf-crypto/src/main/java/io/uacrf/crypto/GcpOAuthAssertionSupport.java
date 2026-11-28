package io.uacrf.crypto;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
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
 * Shared Google Cloud OAuth2 credential-path resolution, token-URI SSRF validation,
 * and RS256 service-account JWT assertion signing utilities.
 */
public final class GcpOAuthAssertionSupport {

    public static final String CLOUD_PLATFORM_SCOPE = "https://www.googleapis.com/auth/cloud-platform";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GcpOAuthAssertionSupport() {
    }

    /**
     * Resolves GCP credentials file path in order:
     * explicit property -> GOOGLE_APPLICATION_CREDENTIALS -> ~/.config/gcloud/application_default_credentials.json.
     */
    public static Path resolveCredentialsPath(String explicitCredentialsFile, String envCredentialsPath, String userHome) {
        if (explicitCredentialsFile != null && !explicitCredentialsFile.isBlank()) {
            return Path.of(explicitCredentialsFile.trim());
        }
        if (envCredentialsPath != null && !envCredentialsPath.isBlank()) {
            return Path.of(envCredentialsPath.trim());
        }
        if (userHome != null && !userHome.isBlank()) {
            return Path.of(userHome.trim(), ".config", "gcloud", "application_default_credentials.json");
        }
        return null;
    }

    /**
     * Identifies unsafe SSRF target hosts (cloud metadata endpoints and local loopback).
     */
    public static boolean isUnsafeEgressHost(String url) {
        if (url == null || url.isBlank()) {
            return true;
        }
        URI uri = URI.create(url);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        return host.isBlank()
                || host.equals("169.254.169.254")
                || host.equals("metadata.google.internal")
                || host.startsWith("169.254.")
                || host.equals("localhost")
                || host.equals("127.0.0.1");
    }

    /**
     * Signs an RS256 OAuth2 JWT bearer assertion for a Google Cloud service account key.
     */
    public static String signServiceAccountAssertion(
            String clientEmail,
            String privateKeyPem,
            String scope,
            String audience,
            Instant issuedAt) throws Exception {
        long now = issuedAt.getEpochSecond();
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String header = b64.encodeToString(MAPPER.writeValueAsBytes(Map.of("alg", "RS256", "typ", "JWT")));
        String payload = b64.encodeToString(MAPPER.writeValueAsBytes(Map.of(
                "iss", clientEmail,
                "scope", scope == null || scope.isBlank() ? CLOUD_PLATFORM_SCOPE : scope,
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
}
