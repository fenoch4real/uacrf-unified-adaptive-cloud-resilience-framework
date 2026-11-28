package io.uacrf.discovery.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.uacrf.crypto.AwsSigV4SignerSupport;
import io.uacrf.crypto.AwsSigV4SignerSupport.AwsCredentials;
import io.uacrf.crypto.CanonicalJson;
import io.uacrf.crypto.GcpOAuthAssertionSupport;
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

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Polls live AWS CloudWatch metrics and CloudWatch Logs error/audit entries via native AWS SigV4 signing
 * using the read-only AwsCredentialProvider (strictly separated from S11 recovery credentials).
 */
@Component
public class AwsRuntimeTelemetryPoller {

    private static final List<String[]> DEFAULT_METRICS = List.of(
            new String[]{"AWS/ECS", "CPUUtilization", "ServiceName"},
            new String[]{"AWS/ApplicationELB", "HTTPCode_Target_5XX_Count", "LoadBalancer"},
            new String[]{"AWS/RDS", "CPUUtilization", "DBInstanceIdentifier"},
            new String[]{"AWS/SQS", "ApproximateNumberOfMessagesVisible", "QueueName"}
    );

    private final PiiPhiScrubber scrubber;
    private final AwsCredentialProvider credentialProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final boolean enabled;
    private final String defaultRegion;
    private final String cloudwatchBaseUrl;
    private final String logsBaseUrl;
    private RestClient restClient;

    @Autowired
    public AwsRuntimeTelemetryPoller(
            PiiPhiScrubber scrubber,
            AwsCredentialProvider credentialProvider,
            @Value("${uacrf.discovery.adapters.aws.enabled:false}") boolean enabled,
            @Value("${uacrf.discovery.adapters.aws.region:us-east-1}") String defaultRegion,
            @Value("${uacrf.discovery.adapters.aws.cloudwatch-url:}") String cloudwatchBaseUrl,
            @Value("${uacrf.discovery.adapters.aws.logs-url:}") String logsBaseUrl) {
        this.scrubber = scrubber;
        this.credentialProvider = credentialProvider;
        this.enabled = enabled;
        this.defaultRegion = defaultRegion;
        this.cloudwatchBaseUrl = stripTrailingSlash(isBlank(cloudwatchBaseUrl)
                ? "https://monitoring." + defaultRegion + ".amazonaws.com" : cloudwatchBaseUrl);
        this.logsBaseUrl = stripTrailingSlash(isBlank(logsBaseUrl)
                ? "https://logs." + defaultRegion + ".amazonaws.com" : logsBaseUrl);
        this.restClient = RestClient.create();
    }

    public void setRestClientForTesting(RestClient customClient) {
        this.restClient = customClient;
    }

    public AwsRuntimeTelemetryPollResult poll(AwsRuntimeTelemetryPollRequest req) {
        if (!enabled) {
            throw new DiscoveryDomainException(HttpStatus.PRECONDITION_FAILED, "PROVIDER_ADAPTER_DISABLED",
                    "AWS discovery adapter is in standby (uacrf.discovery.adapters.aws.enabled=false).");
        }
        String region = isBlank(req.region()) ? defaultRegion : req.region().trim();
        validateSafeHost(cloudwatchBaseUrl);
        validateSafeHost(logsBaseUrl);

        AwsCredentials sigV4Creds = credentialProvider.resolveSigV4Credentials();
        String bearer = credentialProvider.getExplicitBearerToken();
        if (!sigV4Creds.isComplete() && isBlank(bearer)) {
            throw new DiscoveryDomainException(HttpStatus.PRECONDITION_FAILED, "MISSING_READ_ONLY_CREDENTIALS",
                    "Read-only AWS SigV4 or STS credentials are not configured for region [" + region + "].");
        }

        int windowMinutes = req.windowMinutes() != null && req.windowMinutes() > 0 ? req.windowMinutes() : 60;
        boolean includeMetrics = req.includeMetrics() == null || req.includeMetrics();
        boolean includeLogs = req.includeLogs() == null || req.includeLogs();

        List<ObservationDto> observations = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        int metricsCount = 0;
        int logsCount = 0;

        if (includeMetrics) {
            try {
                List<ObservationDto> metricObs = fetchCloudWatchMetrics(
                        req.tenantId(), req.environmentId(), region, sigV4Creds, bearer, windowMinutes);
                metricsCount = metricObs.size();
                observations.addAll(metricObs);
            } catch (org.springframework.web.client.HttpClientErrorException ex) {
                if (ex.getStatusCode().value() == 403 || ex.getStatusCode().value() == 404) {
                    notes.add("CloudWatch metrics skipped (" + ex.getStatusCode().value() + ")");
                } else {
                    throw ex;
                }
            }
        }

        if (includeLogs) {
            try {
                List<ObservationDto> logObs = fetchCloudWatchLogs(
                        req.tenantId(), req.environmentId(), region, req.logGroupName(),
                        sigV4Creds, bearer, windowMinutes);
                logsCount = logObs.size();
                observations.addAll(logObs);
            } catch (org.springframework.web.client.HttpClientErrorException ex) {
                if (ex.getStatusCode().value() == 400 || ex.getStatusCode().value() == 403 || ex.getStatusCode().value() == 404) {
                    notes.add("CloudWatch Logs skipped (" + ex.getStatusCode().value()
                            + "): ensure logs:FilterLogEvents and target logGroupName exist.");
                } else {
                    throw ex;
                }
            }
        }

        String note = notes.isEmpty()
                ? "Collected live AWS CloudWatch metrics & CloudWatch Logs signals cleanly via SigV4."
                : String.join(" | ", notes);

        return new AwsRuntimeTelemetryPollResult(
                "AWS", region, "LIVE_ADAPTER", metricsCount, logsCount, observations, note
        );
    }

    private List<ObservationDto> fetchCloudWatchMetrics(
            UUID tenantId, UUID envId, String region,
            AwsCredentials creds, String bearer, int windowMinutes) {
        Instant end = Instant.now();
        Instant start = end.minus(windowMinutes, ChronoUnit.MINUTES);
        String isoStart = DateTimeFormatter.ISO_INSTANT.format(start);
        String isoEnd = DateTimeFormatter.ISO_INSTANT.format(end);

        StringBuilder form = new StringBuilder("Action=GetMetricData&Version=2010-08-01")
                .append("&StartTime=").append(urlEnc(isoStart))
                .append("&EndTime=").append(urlEnc(isoEnd));
        for (int i = 0; i < DEFAULT_METRICS.size(); i++) {
            String[] m = DEFAULT_METRICS.get(i);
            int idx = i + 1;
            form.append("&MetricDataQueries.member.").append(idx).append(".Id=q").append(idx)
                    .append("&MetricDataQueries.member.").append(idx).append(".MetricStat.Metric.Namespace=").append(urlEnc(m[0]))
                    .append("&MetricDataQueries.member.").append(idx).append(".MetricStat.Metric.MetricName=").append(urlEnc(m[1]))
                    .append("&MetricDataQueries.member.").append(idx).append(".MetricStat.Period=300")
                    .append("&MetricDataQueries.member.").append(idx).append(".MetricStat.Stat=Average");
        }

        URI uri = URI.create(cloudwatchBaseUrl + "/");
        String bodyStr = form.toString();
        byte[] bodyBytes = bodyStr.getBytes(StandardCharsets.UTF_8);

        var spec = restClient.post().uri(uri)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        applyAuthHeaders(spec, "POST", uri, "monitoring", region,
                Map.of("Content-Type", "application/x-www-form-urlencoded", "Accept", "application/json"),
                bodyBytes, creds, bearer);
        String raw = spec.body(bodyStr).retrieve().body(String.class);
        return parseCloudWatchMetrics(tenantId, envId, region, raw);
    }

    public List<ObservationDto> parseCloudWatchMetrics(UUID tenantId, UUID envId, String region, String raw) {
        if (isBlank(raw)) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(raw);
            JsonNode results = root.path("GetMetricDataResponse").path("GetMetricDataResult").path("MetricDataResults");
            if (results.isMissingNode() || !results.isArray()) {
                results = root.path("MetricDataResults");
            }
            List<ObservationDto> out = new ArrayList<>();
            Instant now = Instant.now();
            for (JsonNode entry : results) {
                String label = entry.path("Label").asText(entry.path("Id").asText("aws-metric"));
                JsonNode vals = entry.path("Values");
                if (!vals.isArray() || vals.isEmpty()) {
                    continue;
                }
                double value = vals.get(0).asDouble(0.0);
                String subjectId = "urn:uacrf:aws:" + region + ":" + slug(label);
                String safeLabel = scrubber.scrubText(label).sanitizedText();
                String digest = CanonicalJson.digestObject(Map.of(
                        "provider", "AWS", "region", region, "metric", safeLabel, "value", value));
                out.add(new ObservationDto(
                        UUID.randomUUID(), tenantId, envId, null, subjectId, "AWS", "METRIC_SAMPLE",
                        safeLabel, value, "count", null, value > 0 && safeLabel.contains("5XX") ? "WARN" : "INFO",
                        "LIVE_ADAPTER", digest, safeLabel, now, now
                ));
            }
            return out;
        } catch (Exception ex) {
            throw new DiscoveryDomainException(HttpStatus.BAD_GATEWAY, "INVALID_AWS_TELEMETRY_JSON",
                    "Failed to parse CloudWatch metrics response: " + ex.getMessage());
        }
    }

    private List<ObservationDto> fetchCloudWatchLogs(
            UUID tenantId, UUID envId, String region, String logGroupName,
            AwsCredentials creds, String bearer, int windowMinutes) {
        long startMillis = Instant.now().minus(windowMinutes, ChronoUnit.MINUTES).toEpochMilli();
        Map<String, Object> payload = new LinkedHashMap<>();
        if (!isBlank(logGroupName)) {
            payload.put("logGroupName", logGroupName.trim());
        } else {
            payload.put("logGroupNamePattern", "/aws/");
        }
        payload.put("startTime", startMillis);
        payload.put("limit", 25);

        String jsonBody;
        try {
            jsonBody = objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        URI uri = URI.create(logsBaseUrl + "/");
        byte[] bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);

        var spec = restClient.post().uri(uri)
                .contentType(MediaType.valueOf("application/x-amz-json-1.1"))
                .header("X-Amz-Target", "Logs_20140328.FilterLogEvents");
        applyAuthHeaders(spec, "POST", uri, "logs", region,
                Map.of("Content-Type", "application/x-amz-json-1.1", "X-Amz-Target", "Logs_20140328.FilterLogEvents"),
                bodyBytes, creds, bearer);
        String raw = spec.body(jsonBody).retrieve().body(String.class);
        return parseCloudWatchLogs(tenantId, envId, region, raw);
    }

    public List<ObservationDto> parseCloudWatchLogs(UUID tenantId, UUID envId, String region, String raw) {
        if (isBlank(raw)) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(raw);
            List<ObservationDto> out = new ArrayList<>();
            Instant now = Instant.now();
            for (JsonNode ev : root.path("events")) {
                String stream = ev.path("logStreamName").asText("aws-log");
                String msg = scrubber.scrubText(ev.path("message").asText("")).sanitizedText();
                long ts = ev.path("timestamp").asLong(now.toEpochMilli());
                Instant occurred = Instant.ofEpochMilli(ts);
                String severity = msg.toUpperCase(Locale.ROOT).contains("ERROR") ? "ERROR"
                        : (msg.toUpperCase(Locale.ROOT).contains("WARN") ? "WARN" : "INFO");
                String subjectId = "urn:uacrf:aws:" + region + ":" + slug(stream);
                String compactPointer = "cloudwatch-logs://" + region + "/" + stream;
                String detail = msg.length() > 140 ? msg.substring(0, 140) : msg;
                String digest = CanonicalJson.digestObject(Map.of(
                        "provider", "AWS", "pointer", compactPointer, "ts", ts));
                out.add(new ObservationDto(
                        UUID.randomUUID(), tenantId, envId, null, subjectId, "AWS", "LOG_SIGNAL",
                        compactPointer, 1.0, "event", null, severity, "LIVE_ADAPTER", digest, detail, occurred, now
                ));
            }
            return out;
        } catch (Exception ex) {
            throw new DiscoveryDomainException(HttpStatus.BAD_GATEWAY, "INVALID_AWS_TELEMETRY_JSON",
                    "Failed to parse CloudWatch Logs response: " + ex.getMessage());
        }
    }

    private void applyAuthHeaders(
            RestClient.RequestBodySpec spec,
            String method, URI uri, String service, String region,
            Map<String, String> extraHeaders, byte[] bodyBytes,
            AwsCredentials creds, String bearer) {
        if (creds != null && creds.isComplete()) {
            AwsSigV4SignerSupport.signRequest(method, uri, service, region, extraHeaders, bodyBytes, creds, Instant.now())
                    .forEach(spec::header);
        } else {
            spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
        }
    }

    public void validateSafeHost(String url) {
        if (GcpOAuthAssertionSupport.isUnsafeEgressHost(url)) {
            String host = url == null ? "" : URI.create(url).getHost();
            throw new DiscoveryDomainException(HttpStatus.FORBIDDEN, "SSRF_EGRESS_BLOCKED",
                    "Blocked unsafe AWS telemetry host [" + host + "] per SSRF egress policy.");
        }
    }

    private static String urlEnc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String stripTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String slug(String value) {
        String clean = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return clean.isBlank() ? "aws-resource" : (clean.length() > 64 ? clean.substring(0, 64) : clean);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
