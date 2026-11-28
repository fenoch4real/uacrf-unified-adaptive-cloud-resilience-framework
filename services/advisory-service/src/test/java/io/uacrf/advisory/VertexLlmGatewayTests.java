package io.uacrf.advisory;

import io.uacrf.advisory.api.AdvisoryDomainException;
import io.uacrf.advisory.service.EnterpriseLlmGateway;
import io.uacrf.advisory.service.GcpVertexCredentialResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
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

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Verifies Google Vertex AI OAuth2 service-account/ADC credential resolution, PII scrubbing,
 * structured Gemini candidate extraction, SSRF metadata blocking, and deterministic fallback in S09.
 */
class VertexLlmGatewayTests {

    @Test
    void authenticatesWithServiceAccountJwtAndExtractsVertexCandidateText(@TempDir Path tempDir) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(kp.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";

        Path saFile = tempDir.resolve("vertex-sa.json");
        Files.writeString(saFile, """
                {
                  "type": "service_account",
                  "client_email": "uacrf-vertex@uacrf-sandbox-123.iam.gserviceaccount.com",
                  "private_key": "%s",
                  "token_uri": "https://oauth2.googleapis.com/token"
                }
                """.formatted(pem.replace("\n", "\\n")));

        RestClient.Builder oauthBuilder = RestClient.builder();
        MockRestServiceServer oauthServer = MockRestServiceServer.bindTo(oauthBuilder).build();
        oauthServer.expect(requestTo("https://oauth2.googleapis.com/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"access_token":"vertex-oauth-token-123","expires_in":3600}
                        """, MediaType.APPLICATION_JSON));

        GcpVertexCredentialResolver resolver = new GcpVertexCredentialResolver(
                saFile.toString(), "https://oauth2.googleapis.com/token");
        resolver.setRestClientForTesting(oauthBuilder.build());

        RestClient.Builder vertexBuilder = RestClient.builder();
        MockRestServiceServer vertexServer = MockRestServiceServer.bindTo(vertexBuilder).build();
        vertexServer.expect(requestTo(
                "https://us-central1-aiplatform.googleapis.com/v1/projects/uacrf-sandbox-123/locations/us-central1/publishers/google/models/gemini-2.0-flash:generateContent"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer vertex-oauth-token-123"))
                .andExpect(content().string(containsString("[REDACTED_EMAIL]")))
                .andRespond(withSuccess("""
                        {
                          "candidates": [
                            {
                              "content": {
                                "parts": [
                                  { "text": "Cloud Run latency spike traced to revision v43; empirical readiness check required." }
                                ]
                              }
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        EnterpriseLlmGateway gateway = new EnterpriseLlmGateway(
                true, "GOOGLE_VERTEX", "",
                "https://api.openai.com/v1/chat/completions", "gpt-4o-mini",
                "us-east-1", "anthropic.claude-3-5-sonnet-20240620-v1:0", "",
                "https://example.openai.azure.com/openai/deployments/uacrf-gpt4o/chat/completions", "2024-10-21", "uacrf-gpt4o",
                "uacrf-sandbox-123", "us-central1", "gemini-2.0-flash", "",
                "us-chicago-1", "", "cohere.command-r-plus", "",
                resolver
        );
        gateway.setRestClientForTesting(vertexBuilder.build());

        var outcome = gateway.synthesize("Analyze checkout failure reported by ops@example.com", "GR-1044");
        assertEquals("GOOGLE_VERTEX", outcome.engine());
        assertEquals("Cloud Run latency spike traced to revision v43; empirical readiness check required.", outcome.body());
        assertFalse(outcome.title().contains("ops@example.com"));

        // Second call hits in-memory token cache without another OAuth2 exchange
        vertexServer.reset();
        vertexServer.expect(requestTo(
                "https://us-central1-aiplatform.googleapis.com/v1/projects/uacrf-sandbox-123/locations/us-central1/publishers/google/models/gemini-2.0-flash:generateContent"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        var fallback = gateway.synthesize("Follow up latency check", "GR-1044");
        assertEquals("GOOGLE_VERTEX_FALLBACK", fallback.engine());
        assertTrue(fallback.body().contains("deterministic grounded summary preserved"));

        oauthServer.verify();
        vertexServer.verify();
    }

    @Test
    void blocksSsrfTokenUriAndFallsBackToDeterministicWhenUnconfigured(@TempDir Path tempDir) throws Exception {
        Path badSa = tempDir.resolve("bad-sa.json");
        Files.writeString(badSa, """
                {
                  "type": "service_account",
                  "client_email": "bad@example.com",
                  "private_key": "unused",
                  "token_uri": "http://169.254.169.254/computeMetadata/v1/"
                }
                """);

        GcpVertexCredentialResolver badResolver = new GcpVertexCredentialResolver(
                badSa.toString(), "https://oauth2.googleapis.com/token");
        AdvisoryDomainException ssrfEx = assertThrows(AdvisoryDomainException.class,
                () -> badResolver.resolveAccessToken(""));
        assertEquals("SSRF_EGRESS_BLOCKED", ssrfEx.getCode());
        assertEquals(HttpStatus.FORBIDDEN, ssrfEx.getStatus());

        GcpVertexCredentialResolver missingResolver = new GcpVertexCredentialResolver(
                tempDir.resolve("missing.json").toString(), "https://oauth2.googleapis.com/token");
        EnterpriseLlmGateway gateway = new EnterpriseLlmGateway(
                true, "GOOGLE_VERTEX", "",
                "https://api.openai.com/v1/chat/completions", "gpt-4o-mini",
                "us-east-1", "anthropic.claude-3-5-sonnet-20240620-v1:0", "",
                "https://example.openai.azure.com/openai/deployments/uacrf-gpt4o/chat/completions", "2024-10-21", "uacrf-gpt4o",
                "uacrf-sandbox-123", "us-central1", "gemini-2.0-flash", "",
                "us-chicago-1", "", "cohere.command-r-plus", "",
                missingResolver
        );
        var deterministic = gateway.synthesize("Check service readiness", "GR-1044");
        assertEquals("DETERMINISTIC_GROUNDED", deterministic.engine());
    }
}
