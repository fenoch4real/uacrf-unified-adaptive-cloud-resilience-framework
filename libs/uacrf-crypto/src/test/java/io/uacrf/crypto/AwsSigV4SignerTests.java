package io.uacrf.crypto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AwsSigV4SignerTests {

    @Test
    void signsDeterministicSigV4HeadersIncludingSessionTokenAndSortedQuery() {
        var creds = new AwsSigV4SignerSupport.AwsCredentials("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "sts-token-1");
        Instant ts = Instant.parse("2026-11-28T06:00:00Z");

        Map<String, String> headersA = AwsSigV4SignerSupport.signRequest(
                "POST",
                URI.create("https://resource-explorer-2.us-east-1.amazonaws.com/Search?b=2&a=1"),
                "resource-explorer-2",
                "us-east-1",
                Map.of("Content-Type", "application/json"),
                "{\"QueryString\":\"*\"}".getBytes(StandardCharsets.UTF_8),
                creds,
                ts
        );
        Map<String, String> headersB = AwsSigV4SignerSupport.signRequest(
                "POST",
                URI.create("https://resource-explorer-2.us-east-1.amazonaws.com/Search?a=1&b=2"),
                "resource-explorer-2",
                "us-east-1",
                Map.of("content-type", "application/json"),
                "{\"QueryString\":\"*\"}".getBytes(StandardCharsets.UTF_8),
                creds,
                ts
        );

        assertEquals(headersA.get("Authorization"), headersB.get("Authorization"));
        assertTrue(headersA.get("Authorization").startsWith(
                "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20261128/us-east-1/resource-explorer-2/aws4_request, SignedHeaders=content-type;host;x-amz-content-sha256;x-amz-date;x-amz-security-token, Signature="));
        assertEquals("20261128T060000Z", headersA.get("x-amz-date"));
        assertEquals("sts-token-1", headersA.get("x-amz-security-token"));
        assertEquals(64, headersA.get("x-amz-content-sha256").length());
    }

    @Test
    void resolvesCredentialsFromIniProfileAndEnvFallback(@TempDir Path tempDir) throws Exception {
        Path ini = tempDir.resolve("aws-creds");
        Files.writeString(ini, """
                [default]
                aws_access_key_id = AKIA_DEFAULT
                aws_secret_access_key = SECRET_DEFAULT

                [profile uacrf-ro]
                aws_access_key_id = AKIA_PROFILE
                aws_secret_access_key = SECRET_PROFILE
                aws_session_token = SESSION_PROFILE
                """);

        var resolvedProfile = AwsSigV4SignerSupport.resolveCredentials(
                "", "", "", ini.toString(), "uacrf-ro", Map.of(), "");
        assertTrue(resolvedProfile.isComplete());
        assertEquals("AKIA_PROFILE", resolvedProfile.accessKeyId());
        assertEquals("SESSION_PROFILE", resolvedProfile.sessionToken());

        var explicitWins = AwsSigV4SignerSupport.resolveCredentials(
                "AKIA_EXPLICIT", "SECRET_EXPLICIT", "", ini.toString(), "uacrf-ro", Map.of(), "");
        assertEquals("AKIA_EXPLICIT", explicitWins.accessKeyId());
    }
}
