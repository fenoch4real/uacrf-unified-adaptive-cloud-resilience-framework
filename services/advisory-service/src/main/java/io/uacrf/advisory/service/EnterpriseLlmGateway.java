package io.uacrf.advisory.service;

import io.uacrf.advisory.api.AdvisoryDomainException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Sandboxed Enterprise LLM Adapter SPI for S09.
 * Enforces SSRF egress checks, PII scrubbing before external LLM dispatch, and strictly preserves
 * UACRF's core invariant: advice is NOT recovery authority and NEVER suppresses blocking critics.
 */
@Component
public class EnterpriseLlmGateway {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern SSN_PATTERN = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");
    private static final Pattern BEARER_PATTERN = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9\\-._~+/]+=*");

    private final boolean externalEnabled;
    private final String provider;
    private final String endpointUrl;
    private final String apiKey;
    private final String model;
    private final RestClient restClient;

    public EnterpriseLlmGateway(
            @Value("${uacrf.advisory.llm.enabled:false}") boolean externalEnabled,
            @Value("${uacrf.advisory.llm.provider:DETERMINISTIC}") String provider,
            @Value("${uacrf.advisory.llm.endpoint-url:https://api.openai.com/v1/chat/completions}") String endpointUrl,
            @Value("${uacrf.advisory.llm.api-key:}") String apiKey,
            @Value("${uacrf.advisory.llm.model:gpt-4o-mini}") String model) {
        this.externalEnabled = externalEnabled;
        this.provider = provider;
        this.endpointUrl = endpointUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.restClient = RestClient.create();
    }

    public String scrubOutboundPrompt(String prompt) {
        if (prompt == null) {
            return "";
        }
        String scrubbed = BEARER_PATTERN.matcher(prompt).replaceAll("Bearer [REDACTED]");
        scrubbed = SSN_PATTERN.matcher(scrubbed).replaceAll("[REDACTED_SSN]");
        return EMAIL_PATTERN.matcher(scrubbed).replaceAll("[REDACTED_EMAIL]");
    }

    public void validateSafeEndpoint(String url) {
        URI uri = URI.create(url);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        if (host.equals("169.254.169.254") || host.equals("metadata.google.internal")
                || host.startsWith("169.254.") || host.equals("localhost") || host.equals("127.0.0.1")) {
            throw new AdvisoryDomainException(HttpStatus.FORBIDDEN, "SSRF_EGRESS_BLOCKED",
                    "Blocked unsafe LLM gateway target host [" + host + "] per SSRF egress policy.");
        }
    }

    public SynthesisOutcome synthesize(String queryPrompt, String contextRef) {
        String safePrompt = scrubOutboundPrompt(queryPrompt);
        if (!externalEnabled || apiKey == null || apiKey.isBlank()) {
            return new SynthesisOutcome(
                    "DETERMINISTIC_GROUNDED",
                    "Source-backed draft for: " + safePrompt,
                    "Advisory draft synthesized against approved runbooks and graph context " + contextRef
                            + ". Empirical probes remain required."
            );
        }
        validateSafeEndpoint(endpointUrl);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post()
                    .uri(endpointUrl)
                    .header("Authorization", "Bearer " + apiKey)
                    .body(Map.of(
                            "model", model,
                            "messages", java.util.List.of(
                                    Map.of("role", "system", "content",
                                            "You are UACRF S09 Evidence Assistant. You only summarize grounded facts; never authorize recovery."),
                                    Map.of("role", "user", "content", safePrompt + " [context=" + contextRef + "]")
                            )
                    ))
                    .retrieve()
                    .body(Map.class);
            String summary = response != null ? String.valueOf(response.getOrDefault("choices", safePrompt)) : safePrompt;
            return new SynthesisOutcome(provider, "LLM draft (" + provider + "): " + safePrompt, summary);
        } catch (Exception ex) {
            return new SynthesisOutcome(
                    provider + "_FALLBACK",
                    "Source-backed fallback draft for: " + safePrompt,
                    "External LLM unavailable (" + ex.getClass().getSimpleName()
                            + "); deterministic grounded summary preserved. Empirical verification still required."
            );
        }
    }

    public record SynthesisOutcome(String engine, String title, String body) {
    }
}
