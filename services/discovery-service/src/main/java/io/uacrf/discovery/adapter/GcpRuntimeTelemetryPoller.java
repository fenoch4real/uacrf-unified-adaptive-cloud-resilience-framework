package io.uacrf.discovery.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.uacrf.crypto.CanonicalJson;
import io.uacrf.discovery.api.DiscoveryDomainException;
import io.uacrf.discovery.api.DiscoveryDtos.*;
import io.uacrf.discovery.service.PiiPhiScrubber;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Polls live GCP Cloud Monitoring v3 timeSeries metrics and Cloud Logging v2 audit/error entries
 * using the shared read-only GcpCredentialProvider (roles/monitoring.viewer + roles/logging.viewer).
 */
@Component
public class GcpRuntimeTelemetryPoller {

    private static final List<String> DEFAULT_METRIC_TYPES = List.of(
            "storage.googleapis.com/storage/total_bytes",
            "storage.googleapis.com/api/request_count",
            "run.googleapis.com/request_count",
            "run.googleapis.com/request_latencies",
            "compute.googleapis.com/instance/cpu/utilization",
            "pubsub.googleapis.com/subscription/num_undelivered_messages",
            "serviceruntime.googleapis.com/api/request_count"
    );

    private final PiiPhiScrubber scrubber;
    private final GcpCredentialProvider credentialProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final boolean enabled;
    private final String defaultProjectId;
    private final String monitoringBaseUrl;
    private final String loggingBaseUrl;
    private RestClient restClient;

    @Autowired
    public GcpRuntimeTelemetryPoller(
            PiiPhiScrubber scrubber,
            GcpCredentialProvider credentialProvider,
            @Value("${uacrf.discovery.adapters.gcp.enabled:false}") boolean enabled,
            @Value("${uacrf.discovery.adapters.gcp.project-id:uacrf-unconfigured}") String defaultProjectId,
            @Value("${uacrf.discovery.adapters.gcp.monitoring-url:https://monitoring.googleapis.com}") String monitoringBaseUrl,
            @Value("${uacrf.discovery.adapters.gcp.logging-url:https://logging.googleapis.com}") String loggingBaseUrl) {
        this.scrubber = scrubber;
        this.credentialProvider = credentialProvider;
        this.enabled = enabled;
        this.defaultProjectId = defaultProjectId;
        this.monitoringBaseUrl = stripTrailingSlash(monitoringBaseUrl);
        this.loggingBaseUrl = stripTrailingSlash(loggingBaseUrl);
        this.restClient = RestClient.create();
    }

    public void setRestClientForTesting(RestClient customClient) {
        this.restClient = customClient;
    }

    public RuntimeTelemetryPollResult poll(GcpRuntimeTelemetryPollRequest req) {
        if (!enabled) {
            throw new DiscoveryDomainException(HttpStatus.PRECONDITION_FAILED, "PROVIDER_ADAPTER_DISABLED",
                    "GCP discovery adapter is in standby (uacrf.discovery.adapters.gcp.enabled=false).");
        }
        String projectId = isBlank(req.projectId()) ? defaultProjectId : req.projectId().trim();
        validateSafeHost(monitoringBaseUrl);
        validateSafeHost(loggingBaseUrl);

        String token = credentialProvider.resolveAccessToken();
        if (isBlank(token)) {
            throw new DiscoveryDomainException(HttpStatus.PRECONDITION_FAILED, "MISSING_READ_ONLY_CREDENTIALS",
                    "Read-only GCP OAuth2 credential is not configured for project [" + projectId + "].");
        }

        int windowMinutes = req.windowMinutes() != null && req.windowMinutes() > 0 ? req.windowMinutes() : 60;
        boolean includeMetrics = req.includeMetrics() == null || req.includeMetrics();
        boolean includeLogs = req.includeLogs() == null || req.includeLogs();

        List<ObservationDto> observations = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        int metricsCount = 0;
        int logsCount = 0;

        if (includeMetrics) {
            List<String> filters = isBlank(req.metricFilter())
                    ? DEFAULT_METRIC_TYPES.stream().map(m -> "metric.type = \"" + m + "\"").toList()
                    : List.of(req.metricFilter().trim());
            for (String filter : filters) {
                try {
                    List<ObservationDto> metricObs = fetchMonitoringMetrics(
                            req.tenantId(), req.environmentId(), projectId, token, filter, windowMinutes);
                    metricsCount += metricObs.size();
                    observations.addAll(metricObs);
                } catch (org.springframework.web.client.HttpClientErrorException ex) {
                    if (ex.getStatusCode().value() == 403 || ex.getStatusCode().value() == 404) {
                        notes.add("Metrics skipped (" + ex.getStatusCode().value() + ") for [" + filter + "]");
                    } else {
                        throw ex;
                    }
                }
            }
        }

        if (includeLogs) {
            try {
                List<ObservationDto> logObs = fetchLoggingEntries(
                        req.tenantId(), req.environmentId(), projectId, token, windowMinutes);
                logsCount = logObs.size();
                observations.addAll(logObs);
            } catch (org.springframework.web.client.HttpClientErrorException ex) {
                if (ex.getStatusCode().value() == 403 || ex.getStatusCode().value() == 404) {
                    notes.add("Cloud Logging unavailable (" + ex.getStatusCode().value()
                            + "): grant roles/logging.viewer and enable logging.googleapis.com to include audit logs.");
                } else {
                    throw ex;
                }
            }
        }

        String summaryNote = notes.isEmpty()
                ? "Collected live GCP Monitoring v3 timeSeries & Cloud Logging v2 entries cleanly via read-only OAuth2."
                : String.join(" | ", notes);

        return new RuntimeTelemetryPollResult(
                "GCP",
                projectId,
                "LIVE_ADAPTER",
                metricsCount,
                logsCount,
                observations,
                summaryNote
        );
    }

    public List<ObservationDto> parseMonitoringResponse(UUID tenantId, UUID environmentId, String projectId, JsonNode root) {
        if (root == null || root.isMissingNode()) {
            return List.of();
        }
        Instant now = Instant.now();
        List<ObservationDto> result = new ArrayList<>();
        for (JsonNode ts : root.path("timeSeries")) {
            String metricType = ts.path("metric").path("type").asText("custom.googleapis.com/unknown");
            String resourceType = ts.path("resource").path("type").asText("global");
            JsonNode labels = ts.path("resource").path("labels");
            String resourceName = extractResourceName(projectId, resourceType, labels);
            String subjectCanonicalId = scrubber.scrubText("urn:uacrf:gcp:" + slug(resourceName)).sanitizedText();

            Double metricValue = null;
            Instant occurredAt = now;
            JsonNode points = ts.path("points");
            if (points.isArray() && !points.isEmpty()) {
                JsonNode latest = points.get(0);
                JsonNode valNode = latest.path("value");
                if (valNode.has("doubleValue")) {
                    metricValue = valNode.path("doubleValue").asDouble();
                } else if (valNode.has("int64Value")) {
                    metricValue = Double.parseDouble(valNode.path("int64Value").asText("0"));
                }
                String endStr = latest.path("interval").path("endTime").asText("");
                if (!endStr.isBlank()) {
                    try {
                        occurredAt = Instant.parse(endStr);
                    } catch (Exception ignored) {
                    }
                }
            }

            String unit = ts.path("unit").asText("1");
            String signalType = classifyMetricSignal(metricType);
            String digest = CanonicalJson.digestObject(Map.of(
                    "provider", "GCP", "projectId", projectId, "metricType", metricType,
                    "resourceName", resourceName, "occurredAt", occurredAt.toString()
            ));

            result.add(new ObservationDto(
                    UUID.randomUUID(), tenantId, environmentId, null,
                    subjectCanonicalId, "GCP", signalType, metricType,
                    metricValue != null ? metricValue : 0.0, unit, null,
                    "INFO", "LIVE_ADAPTER", digest,
                    scrubber.scrubText("monitoring://" + projectId + "/" + resourceType + "/" + resourceName).sanitizedText(),
                    occurredAt, now
            ));
        }
        return result;
    }

    public List<ObservationDto> parseLoggingResponse(UUID tenantId, UUID environmentId, String projectId, JsonNode root) {
        if (root == null || root.isMissingNode()) {
            return List.of();
        }
        Instant now = Instant.now();
        List<ObservationDto> result = new ArrayList<>();
        for (JsonNode entry : root.path("entries")) {
            String severity = normalizeSeverity(entry.path("severity").asText("NOTICE"));
            String resourceType = entry.path("resource").path("type").asText("audited_resource");
            String method = entry.path("protoPayload").path("methodName").asText("");
            String serviceName = entry.path("protoPayload").path("serviceName").asText("cloud.googleapis.com");
            String logName = entry.path("logName").asText("projects/" + projectId + "/logs/cloudaudit");
            String textPayload = entry.path("textPayload").asText("");
            String metricName = !method.isBlank() ? method : (!textPayload.isBlank() ? "log.entry" : serviceName);

            Instant occurredAt = now;
            String tsStr = entry.path("timestamp").asText("");
            if (!tsStr.isBlank()) {
                try {
                    occurredAt = Instant.parse(tsStr);
                } catch (Exception ignored) {
                }
            }

            String subjectCanonicalId = scrubber.scrubText("urn:uacrf:gcp:log:" + slug(projectId + "-" + resourceType)).sanitizedText();
            String safeRef = scrubber.scrubText("logging://" + logName + (method.isBlank() ? "" : "#" + method)).sanitizedText();
            String signalType = classifyLogSignal(method, severity);
            String digest = CanonicalJson.digestObject(Map.of(
                    "provider", "GCP", "projectId", projectId, "metricName", metricName,
                    "severity", severity, "occurredAt", occurredAt.toString(), "ref", safeRef
            ));

            result.add(new ObservationDto(
                    UUID.randomUUID(), tenantId, environmentId, null,
                    subjectCanonicalId, "GCP", signalType,
                    scrubber.scrubText(metricName).sanitizedText(),
                    1.0, "event", null, severity, "LIVE_ADAPTER", digest, safeRef, occurredAt, now
            ));
        }
        return result;
    }

    private List<ObservationDto> fetchMonitoringMetrics(
            UUID tenantId, UUID environmentId, String projectId, String token, String filter, int windowMinutes) {
        Instant end = Instant.now();
        Instant start = end.minus(windowMinutes, ChronoUnit.MINUTES);
        URI uri = UriComponentsBuilder.fromUriString(monitoringBaseUrl + "/v3/projects/" + projectId + "/timeSeries")
                .queryParam("filter", filter)
                .queryParam("interval.startTime", start.toString())
                .queryParam("interval.endTime", end.toString())
                .queryParam("view", "FULL")
                .build().encode().toUri();

        String raw = restClient.get().uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve().body(String.class);
        return parseMonitoringResponse(tenantId, environmentId, projectId, readTree(raw));
    }

    private List<ObservationDto> fetchLoggingEntries(
            UUID tenantId, UUID environmentId, String projectId, String token, int windowMinutes) {
        Instant start = Instant.now().minus(windowMinutes, ChronoUnit.MINUTES);
        String logFilter = "timestamp >= \"" + start.toString() + "\"";
        URI uri = URI.create(loggingBaseUrl + "/v2/entries:list");

        String raw = restClient.post().uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "resourceNames", List.of("projects/" + projectId),
                        "filter", logFilter,
                        "orderBy", "timestamp desc",
                        "pageSize", 25
                ))
                .retrieve().body(String.class);
        return parseLoggingResponse(tenantId, environmentId, projectId, readTree(raw));
    }

    private JsonNode readTree(String raw) {
        try {
            return raw == null || raw.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(raw);
        } catch (Exception ex) {
            throw new DiscoveryDomainException(HttpStatus.BAD_GATEWAY, "INVALID_GCP_TELEMETRY_JSON",
                    "Failed to parse GCP runtime telemetry JSON: " + ex.getMessage());
        }
    }

    private void validateSafeHost(String url) {
        URI uri = URI.create(url);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.equals("169.254.169.254") || host.equals("metadata.google.internal")
                || host.startsWith("169.254.") || host.equals("localhost") || host.equals("127.0.0.1")) {
            throw new DiscoveryDomainException(HttpStatus.FORBIDDEN, "SSRF_EGRESS_BLOCKED",
                    "Blocked unsafe GCP telemetry host [" + host + "] per SSRF egress policy.");
        }
    }

    private static String extractResourceName(String projectId, String resourceType, JsonNode labels) {
        for (String key : List.of("service_name", "bucket_name", "instance_name", "topic_id", "subscription_id", "instance_id")) {
            String val = labels.path(key).asText("");
            if (!val.isBlank()) {
                return val;
            }
        }
        return projectId + "-" + resourceType;
    }

    private static String classifyMetricSignal(String metricType) {
        String lower = metricType.toLowerCase(Locale.ROOT);
        if (lower.contains("latency") || lower.contains("latencies")) return "LATENCY_P95";
        if (lower.contains("error") || lower.contains("5xx")) return "ERROR_RATE";
        if (lower.contains("cpu") || lower.contains("utilization")) return "CPU_UTILIZATION";
        if (lower.contains("byte") || lower.contains("object_count")) return "STORAGE_METRIC";
        if (lower.contains("backlog") || lower.contains("undelivered")) return "QUEUE_BACKLOG";
        return "METRIC_OBSERVATION";
    }

    private static String classifyLogSignal(String method, String severity) {
        String lower = method.toLowerCase(Locale.ROOT);
        if (lower.contains("create") || lower.contains("update") || lower.contains("deploy") || lower.contains("delete")) {
            return "DEPLOYMENT_CHANGE";
        }
        if ("CRITICAL".equals(severity) || "HIGH".equals(severity) || lower.contains("iam")) {
            return "SECURITY_OR_ERROR_EVENT";
        }
        return "AUDIT_LOG_EVENT";
    }

    private static String normalizeSeverity(String raw) {
        return switch (raw.toUpperCase(Locale.ROOT)) {
            case "EMERGENCY", "ALERT", "CRITICAL" -> "CRITICAL";
            case "ERROR" -> "HIGH";
            case "WARNING" -> "WARNING";
            default -> "INFO";
        };
    }

    private static String slug(String value) {
        String clean = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        clean = clean.replaceAll("^-+|-+$", "");
        return clean.isBlank() ? "resource" : (clean.length() > 64 ? clean.substring(0, 64) : clean);
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
