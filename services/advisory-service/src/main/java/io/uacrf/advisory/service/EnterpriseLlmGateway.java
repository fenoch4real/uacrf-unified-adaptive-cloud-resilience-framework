package io.uacrf.advisory.service;

import io.uacrf.advisory.api.AdvisoryDomainException;
import io.uacrf.crypto.GcpOAuthAssertionSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Sandboxed Enterprise Multi-Provider LLM Gateway SPI for S09.
 * Supports DETERMINISTIC (default), OPENAI, AMAZON_BEDROCK, AZURE_OPENAI, GOOGLE_VERTEX, and OCI_GENAI
 * with uniform SSRF egress checks, PII prompt scrubbing, and strict non-authoritative boundaries.
 */
@Component
public class EnterpriseLlmGateway {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern SSN_PATTERN = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");
    private static final Pattern BEARER_PATTERN = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9\\-._~+/]+=*");
    private static final String SYSTEM_BOUNDARY_PROMPT =
            "You are UACRF S09 Evidence Assistant. Summarize grounded facts only; never authorize recovery.";

    private final boolean externalEnabled;
    private final String activeProvider;
    private final String apiKey;
    private final GcpVertexCredentialResolver gcpVertexCredentialResolver;
    private final Map<String, LlmProviderProfile> profiles;
    private RestClient restClient;

    public EnterpriseLlmGateway(
            String openAiUrl, String openAiModel,
            String bedrockRegion, String bedrockModelId, String bedrockUrlOverride,
            String azureUrl, String azureApiVersion, String azureDeployment,
            String gcpProject, String gcpLocation, String gcpModel, String gcpUrlOverride,
            String ociRegion, String ociCompartment, String ociModelId, String ociUrlOverride,
            boolean externalEnabled, String activeProvider, String apiKey) {
        this(externalEnabled, activeProvider, apiKey, openAiUrl, openAiModel,
                bedrockRegion, bedrockModelId, bedrockUrlOverride,
                azureUrl, azureApiVersion, azureDeployment,
                gcpProject, gcpLocation, gcpModel, gcpUrlOverride,
                ociRegion, ociCompartment, ociModelId, ociUrlOverride,
                new GcpVertexCredentialResolver("", "https://oauth2.googleapis.com/token"));
    }

    @Autowired
    public EnterpriseLlmGateway(
            @Value("${uacrf.advisory.llm.enabled:false}") boolean externalEnabled,
            @Value("${uacrf.advisory.llm.provider:DETERMINISTIC}") String activeProvider,
            @Value("${uacrf.advisory.llm.api-key:}") String apiKey,
            @Value("${uacrf.advisory.llm.openai.endpoint-url:https://api.openai.com/v1/chat/completions}") String openAiUrl,
            @Value("${uacrf.advisory.llm.openai.model:gpt-4o-mini}") String openAiModel,
            @Value("${uacrf.advisory.llm.bedrock.region:us-east-1}") String bedrockRegion,
            @Value("${uacrf.advisory.llm.bedrock.model-id:anthropic.claude-3-5-sonnet-20240620-v1:0}") String bedrockModelId,
            @Value("${uacrf.advisory.llm.bedrock.endpoint-url:}") String bedrockUrlOverride,
            @Value("${uacrf.advisory.llm.azure.endpoint-url:https://example.openai.azure.com/openai/deployments/uacrf-gpt4o/chat/completions}") String azureUrl,
            @Value("${uacrf.advisory.llm.azure.api-version:2024-10-21}") String azureApiVersion,
            @Value("${uacrf.advisory.llm.azure.deployment:uacrf-gpt4o}") String azureDeployment,
            @Value("${uacrf.advisory.llm.google.project-id:uacrf-project}") String gcpProject,
            @Value("${uacrf.advisory.llm.google.location:us-central1}") String gcpLocation,
            @Value("${uacrf.advisory.llm.google.model:gemini-2.0-flash}") String gcpModel,
            @Value("${uacrf.advisory.llm.google.endpoint-url:}") String gcpUrlOverride,
            @Value("${uacrf.advisory.llm.oci.region:us-chicago-1}") String ociRegion,
            @Value("${uacrf.advisory.llm.oci.compartment-ocid:}") String ociCompartment,
            @Value("${uacrf.advisory.llm.oci.model-id:cohere.command-r-plus}") String ociModelId,
            @Value("${uacrf.advisory.llm.oci.endpoint-url:}") String ociUrlOverride,
            GcpVertexCredentialResolver gcpVertexCredentialResolver) {
        this.externalEnabled = externalEnabled;
        this.activeProvider = activeProvider.trim().toUpperCase(Locale.ROOT);
        this.apiKey = apiKey;
        this.gcpVertexCredentialResolver = gcpVertexCredentialResolver;
        this.restClient = RestClient.create();

        String resolvedBedrockUrl = (bedrockUrlOverride != null && !bedrockUrlOverride.isBlank())
                ? bedrockUrlOverride
                : "https://bedrock-runtime." + bedrockRegion + ".amazonaws.com/model/" + bedrockModelId + "/converse";
        String resolvedAzureUrl = azureUrl.contains("api-version=")
                ? azureUrl
                : azureUrl + "?api-version=" + azureApiVersion;
        String resolvedVertexUrl = (gcpUrlOverride != null && !gcpUrlOverride.isBlank())
                ? gcpUrlOverride
                : "https://" + gcpLocation + "-aiplatform.googleapis.com/v1/projects/" + gcpProject
                + "/locations/" + gcpLocation + "/publishers/google/models/" + gcpModel + ":generateContent";
        String resolvedOciUrl = (ociUrlOverride != null && !ociUrlOverride.isBlank())
                ? ociUrlOverride
                : "https://inference.generativeai." + ociRegion + ".oci.oraclecloud.com/20231130/actions/chat";

        this.profiles = Map.of(
                "OPENAI", new LlmProviderProfile("OPENAI", openAiModel, openAiUrl, "BEARER", Map.of()),
                "AMAZON_BEDROCK", new LlmProviderProfile("AMAZON_BEDROCK", bedrockModelId, resolvedBedrockUrl, "BEARER", Map.of("region", bedrockRegion)),
                "AZURE_OPENAI", new LlmProviderProfile("AZURE_OPENAI", azureDeployment, resolvedAzureUrl, "API_KEY_HEADER", Map.of("apiVersion", azureApiVersion)),
                "GOOGLE_VERTEX", new LlmProviderProfile("GOOGLE_VERTEX", gcpModel, resolvedVertexUrl, "OAUTH2_OR_BEARER", Map.of("projectId", gcpProject, "location", gcpLocation)),
                "OCI_GENAI", new LlmProviderProfile("OCI_GENAI", ociModelId, resolvedOciUrl, "BEARER", Map.of("region", ociRegion, "compartmentOcid", ociCompartment))
        );
    }

    public void setRestClientForTesting(RestClient customClient) {
        this.restClient = customClient;
    }

    public Map<String, Object> getGatewayStatus() {
        boolean vertexCredsPresent = (apiKey != null && !apiKey.isBlank())
                || gcpVertexCredentialResolver.hasLocalCredentialsConfigured();
        String effectiveEngine = (!externalEnabled || "DETERMINISTIC".equals(activeProvider))
                ? "DETERMINISTIC_GROUNDED"
                : activeProvider;
        return Map.of(
                "externalEnabled", externalEnabled,
                "configuredProvider", activeProvider,
                "effectiveEngine", effectiveEngine,
                "vertexCredentialsAvailable", vertexCredsPresent
        );
    }

    public List<LlmProviderProfile> listProviderProfiles() {
        return List.of("OPENAI", "AMAZON_BEDROCK", "AZURE_OPENAI", "GOOGLE_VERTEX", "OCI_GENAI")
                .stream().map(profiles::get).toList();
    }

    public LlmProviderProfile getProfile(String providerKey) {
        String norm = providerKey.trim().toUpperCase(Locale.ROOT);
        LlmProviderProfile profile = profiles.get(norm);
        if (profile == null) {
            throw new AdvisoryDomainException(HttpStatus.BAD_REQUEST, "UNKNOWN_LLM_PROVIDER",
                    "Unsupported LLM provider [" + providerKey + "]. Supported: OPENAI, AMAZON_BEDROCK, AZURE_OPENAI, GOOGLE_VERTEX, OCI_GENAI.");
        }
        return profile;
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
        if (GcpOAuthAssertionSupport.isUnsafeEgressHost(url)) {
            String host = url == null ? "" : java.net.URI.create(url).getHost();
            throw new AdvisoryDomainException(HttpStatus.FORBIDDEN, "SSRF_EGRESS_BLOCKED",
                    "Blocked unsafe LLM gateway target host [" + host + "] per SSRF egress policy.");
        }
    }

    public Map<String, Object> buildProviderPayload(String providerKey, String safePrompt, String contextRef) {
        LlmProviderProfile profile = getProfile(providerKey);
        String userContent = safePrompt + " [context=" + contextRef + "]";
        return switch (profile.provider()) {
            case "AMAZON_BEDROCK" -> Map.of(
                    "system", List.of(Map.of("text", SYSTEM_BOUNDARY_PROMPT)),
                    "messages", List.of(Map.of("role", "user", "content", List.of(Map.of("text", userContent))))
            );
            case "GOOGLE_VERTEX" -> Map.of(
                    "systemInstruction", Map.of("parts", List.of(Map.of("text", SYSTEM_BOUNDARY_PROMPT))),
                    "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", userContent))))
            );
            case "OCI_GENAI" -> Map.of(
                    "compartmentId", profile.metadata().getOrDefault("compartmentOcid", ""),
                    "servingMode", Map.of("servingType", "ON_DEMAND", "modelId", profile.model()),
                    "chatRequest", Map.of("apiFormat", "GENERIC", "messages", List.of(
                            Map.of("role", "SYSTEM", "content", List.of(Map.of("type", "TEXT", "text", SYSTEM_BOUNDARY_PROMPT))),
                            Map.of("role", "USER", "content", List.of(Map.of("type", "TEXT", "text", userContent)))
                    ))
            );
            default -> Map.of( // OPENAI & AZURE_OPENAI share Chat Completions schema
                    "model", profile.model(),
                    "messages", List.of(
                            Map.of("role", "system", "content", SYSTEM_BOUNDARY_PROMPT),
                            Map.of("role", "user", "content", userContent)
                    )
            );
        };
    }

    public SynthesisOutcome synthesize(String queryPrompt, String contextRef) {
        String safePrompt = scrubOutboundPrompt(queryPrompt);
        if (!externalEnabled || "DETERMINISTIC".equals(activeProvider)) {
            return deterministicOutcome(safePrompt, contextRef);
        }
        LlmProviderProfile profile = getProfile(activeProvider);
        validateSafeEndpoint(profile.endpointUrl());

        String authToken;
        try {
            authToken = "GOOGLE_VERTEX".equals(profile.provider())
                    ? gcpVertexCredentialResolver.resolveAccessToken(apiKey)
                    : (apiKey == null ? "" : apiKey.trim());
        } catch (AdvisoryDomainException ex) {
            if ("SSRF_EGRESS_BLOCKED".equals(ex.getCode())) {
                throw ex;
            }
            return fallbackOutcome(profile.provider(), safePrompt, ex);
        }

        if (authToken.isBlank()) {
            return deterministicOutcome(safePrompt, contextRef);
        }

        try {
            var spec = restClient.post().uri(profile.endpointUrl());
            if ("API_KEY_HEADER".equals(profile.authMode())) {
                spec.header("api-key", authToken);
            } else {
                spec.header("Authorization", "Bearer " + authToken);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> response = spec.body(buildProviderPayload(profile.provider(), safePrompt, contextRef))
                    .retrieve()
                    .body(Map.class);
            String summary = extractResponseText(profile.provider(), response, safePrompt);
            return new SynthesisOutcome(profile.provider(), "LLM draft (" + profile.provider() + "): " + safePrompt, summary);
        } catch (Exception ex) {
            return fallbackOutcome(profile.provider(), safePrompt, ex);
        }
    }

    private SynthesisOutcome deterministicOutcome(String safePrompt, String contextRef) {
        return new SynthesisOutcome(
                "DETERMINISTIC_GROUNDED",
                "Source-backed draft for: " + safePrompt,
                "Advisory draft synthesized against approved runbooks and graph context " + contextRef
                        + ". Empirical probes remain required."
        );
    }

    private SynthesisOutcome fallbackOutcome(String provider, String safePrompt, Exception ex) {
        return new SynthesisOutcome(
                provider + "_FALLBACK",
                "Source-backed fallback draft for: " + safePrompt,
                "External LLM unavailable (" + ex.getClass().getSimpleName()
                        + "); deterministic grounded summary preserved. Empirical verification still required."
        );
    }

    String extractResponseText(String provider, Map<String, Object> response, String fallbackPrompt) {
        if (response == null) {
            return fallbackPrompt;
        }
        String extracted = switch (provider) {
            case "GOOGLE_VERTEX" -> firstNestedText(response, List.of("candidates", 0, "content", "parts", 0, "text"));
            case "AMAZON_BEDROCK" -> firstNestedText(response, List.of("output", "message", "content", 0, "text"));
            case "OCI_GENAI" -> firstNestedText(response, List.of("chatResponse", "text"));
            default -> firstNestedText(response, List.of("choices", 0, "message", "content"));
        };
        return (extracted != null && !extracted.isBlank()) ? extracted.trim() : String.valueOf(response);
    }

    private String firstNestedText(Object current, List<Object> path) {
        Object cursor = current;
        for (Object step : path) {
            if (cursor == null) {
                return null;
            }
            if (step instanceof String key && cursor instanceof Map<?, ?> map) {
                cursor = map.get(key);
            } else if (step instanceof Integer idx && cursor instanceof List<?> list) {
                cursor = (idx >= 0 && idx < list.size()) ? list.get(idx) : null;
            } else {
                return null;
            }
        }
        return cursor instanceof String str ? str : null;
    }

    public record LlmProviderProfile(
            String provider,
            String model,
            String endpointUrl,
            String authMode,
            Map<String, String> metadata
    ) {
    }

    public record SynthesisOutcome(String engine, String title, String body) {
    }
}
