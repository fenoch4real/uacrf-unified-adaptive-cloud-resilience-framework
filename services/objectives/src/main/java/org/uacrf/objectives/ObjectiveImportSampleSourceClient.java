package org.uacrf.objectives;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
class ObjectiveImportSampleSourceClient {
  private static final String PURPOSE = "objectives.import.synthetic-source";
  private static final Duration TIMEOUT = Duration.ofSeconds(2);
  private static final Set<String> SOURCE_FIELDS = Set.of(
      "connection_id", "connection_revision", "binding_id", "binding_revision",
      "target_workload_id", "source_system", "external_objective_id", "source_version",
      "payload", "payload_digest", "verification");
  private final boolean enabled;
  private final URI identity;
  private final URI connections;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT)
      .followRedirects(HttpClient.Redirect.NEVER).build();
  private final JsonMapper json = JsonMapper.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

  ObjectiveImportSampleSourceClient(
      @Value("${uacrf.objectives.sample-source.enabled:false}") boolean enabled,
      @Value("${uacrf.objectives.sample-source.identity-base-url:}") String identityUrl,
      @Value("${uacrf.objectives.sample-source.connections-base-url:}") String connectionsUrl,
      @Value("${uacrf.objectives.sample-source.allow-loopback-http:false}") boolean allowLoopbackHttp) {
    this.enabled = enabled;
    identity = enabled ? ObjectiveWorkloadAuthorityClient.endpoint(identityUrl, allowLoopbackHttp) : null;
    connections = enabled ? ObjectiveWorkloadAuthorityClient.endpoint(connectionsUrl, allowLoopbackHttp) : null;
  }

  Inspection inspect(Jwt jwt, ObjectivesService.SampleSourceRequest source) {
    return fetch(jwt, source).inspection();
  }

  Fetched fetch(Jwt jwt, ObjectivesService.SampleSourceRequest source) {
    if (!enabled) throw unavailable();
    if (!source.connectionId().matches("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$")) {
      throw new ObjectivesException(HttpStatus.UNPROCESSABLE_ENTITY, "SAMPLE_SOURCE_INVALID",
          "The retained connection ID cannot be exchanged.");
    }
    Instant deadline = Instant.now().plusSeconds(5);
    try {
      var exchange = HttpRequest.newBuilder(identity.resolve("/internal/v1/delegations:exchange"))
          .timeout(remaining(deadline)).header("Authorization", "Bearer " + jwt.getTokenValue())
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(Map.of(
              "audience", "S03", "resource_type", "connection", "resource_id", source.connectionId(),
              "purpose", PURPOSE, "operation_deadline", deadline.toString())))).build();
      JsonNode delegation = send(exchange, false);
      String token = text(delegation, "access_token");
      if (token.length() > 32768 || !token.matches("[A-Za-z0-9._~+/-]+=*")
          || !"Bearer".equalsIgnoreCase(text(delegation, "token_type"))) throw unavailable();
      String path = "/v1/connections/" + UriUtils.encodePathSegment(source.connectionId(), StandardCharsets.UTF_8)
          + "/sample-objective-definition?externalObjectiveId=" + encode(source.externalObjectiveId())
          + "&targetWorkloadId=" + encode(source.targetWorkloadId());
      var get = HttpRequest.newBuilder(connections.resolve(path)).timeout(remaining(deadline))
          .header("Authorization", "Bearer " + token).GET().build();
      JsonNode response = send(get, true);
      if (!response.isObject() || response.size() != SOURCE_FIELDS.size()
          || SOURCE_FIELDS.stream().anyMatch(field -> !response.has(field))
          || !source.connectionId().equals(text(response, "connection_id"))
          || !source.externalObjectiveId().equals(text(response, "external_objective_id"))
          || !source.targetWorkloadId().equals(text(response, "target_workload_id"))
          || !"SYNTHETIC_UNVERIFIED".equals(text(response, "verification"))) throw unavailable();
      String payload = text(response, "payload");
      String digest = text(response, "payload_digest");
      String bindingId = text(response, "binding_id");
      String sourceSystem = text(response, "source_system");
      String sourceVersion = text(response, "source_version");
      if (bindingId.length() > 200 || sourceSystem.length() > 200 || sourceVersion.length() > 200
          || !sourceSystem.startsWith("sample:") || !sourceVersion.startsWith("sample-")
          || !source.externalObjectiveId().startsWith("sample:")
          || !sha256(payload).equals(digest)) throw unavailable();
      ObjectiveImportDefinition definition;
      ObjectiveImportFingerprint.Value fingerprint;
      try {
        definition = ObjectiveImportSampleParser.parse(payload);
        fingerprint = ObjectiveImportFingerprint.fingerprint(definition);
      } catch (IllegalArgumentException error) {
        throw new ObjectivesException(HttpStatus.UNPROCESSABLE_ENTITY, "SAMPLE_SOURCE_INVALID",
            "The synthetic source definition is invalid or unsupported.");
      }
      if (!Instant.now().isBefore(deadline)) throw unavailable();
      var inspection = new Inspection(source.connectionId(), revision(response, "connection_revision"),
          bindingId, revision(response, "binding_revision"), source.targetWorkloadId(),
          sourceSystem, source.externalObjectiveId(), sourceVersion, digest, "SYNTHETIC_UNVERIFIED",
          definition, fingerprint);
      return new Fetched(inspection, payload);
    } catch (IOException | JacksonException error) {
      throw unavailable();
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw unavailable();
    }
  }

  private JsonNode send(HttpRequest request, boolean source) throws IOException, InterruptedException {
    var response = http.send(request, HttpResponse.BodyHandlers.limiting(
        HttpResponse.BodyHandlers.ofByteArray(), 65_536));
    if (response.statusCode() == 401 || response.statusCode() == 403) {
      throw new ObjectivesException(HttpStatus.FORBIDDEN, "SAMPLE_SOURCE_DENIED",
          "Current objective and connection read authority is required.");
    }
    if (source && response.statusCode() == 404) {
      throw new ObjectivesException(HttpStatus.NOT_FOUND, "SAMPLE_SOURCE_NOT_FOUND",
          "The synthetic source or its binding is unavailable.");
    }
    if (response.statusCode() != 200) throw unavailable();
    JsonNode value = json.readTree(response.body());
    if (value == null || !value.isObject()) throw unavailable();
    return value;
  }

  private static Duration remaining(Instant deadline) {
    Duration duration = Duration.between(Instant.now(), deadline);
    if (duration.isZero() || duration.isNegative()) throw unavailable();
    return duration.compareTo(TIMEOUT) < 0 ? duration : TIMEOUT;
  }

  private static String text(JsonNode root, String field) {
    JsonNode value = root.get(field);
    if (value == null || !value.isString() || value.stringValue().isBlank()) throw unavailable();
    return value.stringValue();
  }

  private static long revision(JsonNode root, String field) {
    JsonNode value = root.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 1) {
      throw unavailable();
    }
    return value.longValue();
  }

  private static String sha256(String payload) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(payload.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable.", error);
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static ObjectivesException unavailable() {
    return new ObjectivesException(HttpStatus.SERVICE_UNAVAILABLE, "SAMPLE_SOURCE_UNAVAILABLE",
        "The synthetic source inspection is unavailable.");
  }

  record Inspection(String connectionId, long connectionRevision, String bindingId, long bindingRevision,
      String targetWorkloadId, String sourceSystem, String externalObjectiveId, String sourceVersion,
      String payloadDigest, String verification, ObjectiveImportDefinition definition,
      ObjectiveImportFingerprint.Value definitionFingerprint) {}

  record Fetched(Inspection inspection, String payload) {}
}
