package org.uacrf.evidence;

import static org.uacrf.evidence.EvidenceRequests.*;
import static org.uacrf.evidence.EvidenceResponses.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uacrf.security.DelegationPrincipal;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
class EvidenceService {
  private static final String SCHEMA = "uacrf_evidence.";
  private static final ObjectMapper STRICT_JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private static final String TOPOLOGY_SUMMARY_CAPABILITY = "evidence.topology.summary";
  private static final Set<String> TOPOLOGY_PURPOSES =
      Set.of(
          "topology.dependency-proposal",
          "topology.dependency-review",
          "topology.graph.current-use",
          "topology.graph.subgraph-selection",
          "topology.graph.difference-classification");
  private static final String COLUMNS =
      "id, source_class, source_ref, sequence, observed_at, kind, service_id, schema_version, "
          + "payload_digest, payload_byte_length, submitted_by, "
          + "qualification_state, revision, reviewed_by, review_reason";
  private static final String HAS_EVIDENCE_CONFLICT =
      "EXISTS (SELECT 1 FROM " + SCHEMA + "quarantined_evidence q "
          + "WHERE q.tenant_id = e.tenant_id AND q.environment_id = e.environment_id "
          + "AND ((q.reason = 'REPLAY_CONFLICT' AND q.evidence_id = e.id) OR "
          + "(q.reason IN ('REPLAY_CONFLICT', 'STALE_SEQUENCE') "
          + "AND q.source_ref = e.source_ref AND q.sequence = e.sequence)))";

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final EvidenceContractSchemas contractSchemas;

  EvidenceService(
      JdbcTemplate jdbc, ObjectMapper objectMapper, EvidenceContractSchemas contractSchemas) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
    this.contractSchemas = contractSchemas;
  }

  @Transactional
  SubmitResult submit(DelegationPrincipal actor, SubmitEvidence request) {
    requireCapability(actor, "evidence.event.write");
    requireGlobalScope(actor);
    jdbc.queryForList(
        "SELECT pg_advisory_xact_lock(hashtext('evidence.observation.id'), hashtext(?))",
        actor.tenantId() + "|" + actor.environmentId() + "|" + request.id());
    jdbc.update(
        "INSERT INTO " + SCHEMA + "source_cursor (tenant_id, environment_id, source_ref, last_sequence) "
            + "VALUES (?, ?, ?, 0) ON CONFLICT DO NOTHING",
        actor.tenantId(),
        actor.environmentId(),
        request.sourceRef());
    long last =
        jdbc.queryForObject(
            "SELECT last_sequence FROM " + SCHEMA + "source_cursor WHERE tenant_id = ? "
                + "AND environment_id = ? AND source_ref = ? FOR UPDATE",
            Long.class,
            actor.tenantId(),
            actor.environmentId(),
            request.sourceRef());
    String state = request.sourceClass() == SourceClass.SAMPLE_LOCAL ? "SAMPLE_ONLY" : "UNVERIFIED";
    byte[] payloadBytes = request.payload().getBytes(StandardCharsets.UTF_8);
    String actualPayloadDigest = sha256(payloadBytes);
    long payloadByteLength = payloadBytes.length;
    if (!actualPayloadDigest.equals(request.payloadDigest())) {
      return quarantine(
          actor, request, "DIGEST_MISMATCH", actualPayloadDigest, payloadByteLength);
    }
    if (request.sourceClass() == SourceClass.PROVIDER && !validProviderObservation(request)) {
      return quarantine(
          actor, request, "SOURCE_SCHEMA_INVALID", actualPayloadDigest, payloadByteLength);
    }
    List<Map<String, Object>> existing =
        jdbc.queryForList(
            "SELECT payload_digest, payload, source_ref, sequence, source_class, kind, service_id, "
                + "schema_version, payload_byte_length, qualification_state, "
                + "observed_at = ? AS observed_at_matches FROM "
                + SCHEMA + "evidence_event WHERE tenant_id = ? AND environment_id = ? AND id = ?",
            Timestamp.from(request.observedAt()),
            actor.tenantId(),
            actor.environmentId(),
            request.id());
    if (!existing.isEmpty()) {
      Map<String, Object> row = existing.getFirst();
      boolean same =
          request.payloadDigest().equals(row.get("payload_digest"))
              && request.payload().equals(row.get("payload"))
              && request.sourceRef().equals(row.get("source_ref"))
              && request.sourceClass().name().equals(row.get("source_class"))
              && request.kind().equals(row.get("kind"))
              && java.util.Objects.equals(request.serviceId(), row.get("service_id"))
              && java.util.Objects.equals(request.schemaVersion(), row.get("schema_version"))
              && payloadByteLength == ((Number) row.get("payload_byte_length")).longValue()
              && Boolean.TRUE.equals(row.get("observed_at_matches"))
              && request.sequence() == ((Number) row.get("sequence")).longValue();
      return same
          ? new SubmitResult(
              request.id(),
              "DUPLICATE",
              "Already accepted.",
              (String) row.get("qualification_state"),
              actualPayloadDigest,
              request.payloadDigest(),
              payloadByteLength)
          : quarantine(actor, request, "REPLAY_CONFLICT", actualPayloadDigest, payloadByteLength);
    }
    if (request.sequence() <= last) {
      return quarantine(actor, request, "STALE_SEQUENCE", actualPayloadDigest, payloadByteLength);
    }
    jdbc.update(
        "INSERT INTO " + SCHEMA + "evidence_event (tenant_id, environment_id, id, source_class, "
            + "source_ref, sequence, observed_at, kind, service_id, schema_version, payload, "
            + "payload_digest, payload_byte_length, submitted_by, qualification_state, revision) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)",
        actor.tenantId(),
        actor.environmentId(),
        request.id(),
        request.sourceClass().name(),
        request.sourceRef(),
        request.sequence(),
        Timestamp.from(request.observedAt()),
        request.kind(),
        request.serviceId(),
        request.schemaVersion(),
        request.payload(),
        request.payloadDigest(),
        payloadByteLength,
        actor.principalId(),
        state);
    jdbc.update(
        "UPDATE " + SCHEMA + "source_cursor SET last_sequence = ? WHERE tenant_id = ? "
            + "AND environment_id = ? AND source_ref = ?",
        request.sequence(),
        actor.tenantId(),
        actor.environmentId(),
        request.sourceRef());
    appendOutbox(
        actor,
        request.id(),
        1,
        "evidence.event.retained",
        Map.of(
            "source_class", request.sourceClass().name(),
            "source_ref", request.sourceRef(),
            "sequence", request.sequence(),
            "kind", request.kind(),
            "payload_digest", request.payloadDigest(),
            "payload_byte_length", payloadByteLength,
            "qualification_state", state));
    return new SubmitResult(
        request.id(),
        "ACCEPTED",
        "Accepted.",
        state,
        actualPayloadDigest,
        request.payloadDigest(),
        payloadByteLength);
  }

  private SubmitResult quarantine(
      DelegationPrincipal actor,
      SubmitEvidence request,
      String reason,
      String actualPayloadDigest,
      long payloadByteLength) {
    jdbc.update(
        "INSERT INTO " + SCHEMA + "quarantined_evidence (quarantine_id, tenant_id, environment_id, "
            + "evidence_id, source_ref, sequence, reason, payload, claimed_digest, "
            + "source_class, observed_at, kind, submitted_by, service_id, schema_version, "
            + "payload_byte_length, actual_payload_digest) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        actor.tenantId(),
        actor.environmentId(),
        request.id(),
        request.sourceRef(),
        request.sequence(),
        reason,
        request.payload(),
        request.payloadDigest(),
        request.sourceClass().name(),
        Timestamp.from(request.observedAt()),
        request.kind(),
        actor.principalId(),
        request.serviceId(),
        request.schemaVersion(),
        payloadByteLength,
        actualPayloadDigest);
    return new SubmitResult(
        request.id(),
        "QUARANTINED",
        reason,
        null,
        actualPayloadDigest,
        request.payloadDigest(),
        payloadByteLength);
  }

  private boolean validProviderObservation(SubmitEvidence request) {
    if (request.serviceId() == null || request.schemaVersion() == null) {
      return false;
    }
    String schemaKind =
        request.kind().startsWith("measurement.")
            ? "METRIC"
            : request.kind().startsWith("configuration.")
                ? "CONFIGURATION"
                : request.kind().startsWith("agent.") ? "AGENT_EXECUTION" : null;
    if (schemaKind == null) {
      return false;
    }
    try {
      JsonNode data = STRICT_JSON.readTree(request.payload());
      if (data == null || !data.isObject()) {
        return false;
      }
      ObjectNode observation = objectMapper.createObjectNode();
      observation.put("schema_version", request.schemaVersion());
      observation.put("service_id", request.serviceId());
      observation.put("source_id", request.sourceRef());
      observation.put("source_event_id", request.id());
      observation.put("occurred_at", request.observedAt().toString());
      observation.put("kind", schemaKind);
      observation.set("data", data);
      return contractSchemas.validObservation(observation);
    } catch (JacksonException | IllegalArgumentException error) {
      return false;
    }
  }

  @Transactional
  Evidence review(DelegationPrincipal actor, String id, ReviewEvidence request) {
    requireCapability(actor, "evidence.qualification.review");
    requireGlobalScope(actor);
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT source_class, submitted_by, revision, qualification_state, payload_digest FROM "
                + SCHEMA + "evidence_event WHERE tenant_id = ? AND environment_id = ? AND id = ? FOR UPDATE",
            actor.tenantId(),
            actor.environmentId(),
            id);
    if (rows.isEmpty()) {
      throw notFound();
    }
    Map<String, Object> row = rows.getFirst();
    if ("SAMPLE_LOCAL".equals(row.get("source_class"))) {
      throw conflict("SAMPLE_NOT_QUALIFIABLE", "Sample data can never become qualified evidence.");
    }
    if (actor.principalId().equals(row.get("submitted_by"))) {
      throw conflict("REVIEWER_NOT_INDEPENDENT", "The submitter cannot review their own evidence.");
    }
    long revision = ((Number) row.get("revision")).longValue();
    if (revision != request.expectedRevision()) {
      throw conflict("REVISION_MISMATCH", "The evidence changed since it was read.");
    }
    if (!"UNVERIFIED".equals(row.get("qualification_state"))) {
      throw conflict("ALREADY_REVIEWED", "Only unverified evidence can be reviewed.");
    }
    String state = request.decision() == Decision.QUALIFY ? "QUALIFIED" : "REJECTED";
    jdbc.update(
        "UPDATE " + SCHEMA + "evidence_event SET qualification_state = ?, reviewed_by = ?, "
            + "reviewed_at = now(), review_reason = ?, revision = revision + 1 WHERE tenant_id = ? "
            + "AND environment_id = ? AND id = ?",
        state,
        actor.principalId(),
        request.reason(),
        actor.tenantId(),
        actor.environmentId(),
        id);
    appendOutbox(
        actor,
        id,
        revision + 1,
        "evidence.qualification.decided",
        Map.of(
            "qualification_state", state,
            "payload_digest", (String) row.get("payload_digest"),
            "reviewed_by", actor.principalId()));
    return find(actor, id);
  }

  @Transactional(readOnly = true)
  Evidence get(DelegationPrincipal actor, String id) {
    requireCapability(actor, "evidence.event.read");
    return find(actor, id);
  }

  @Transactional(readOnly = true)
  Page<Evidence> list(DelegationPrincipal actor, int pageSize, String cursor) {
    requireCapability(actor, "evidence.event.read");
    List<Evidence> rows =
        jdbc.query(
            "SELECT " + COLUMNS + " FROM " + SCHEMA + "evidence_event WHERE tenant_id = ? "
                + "AND environment_id = ? AND (? = '*' OR service_id = ?) "
                + "AND id > ? ORDER BY id LIMIT ?",
            (rs, n) -> evidenceRow(rs),
            actor.tenantId(),
            actor.environmentId(),
            actor.serviceScope(),
            actor.serviceScope(),
            decodeCursor(cursor),
            pageSize + 1);
    if (rows.size() <= pageSize) {
      return new Page<>(rows, null);
    }
    List<Evidence> visible = new ArrayList<>(rows.subList(0, pageSize));
    return new Page<>(
        visible,
        Base64.getUrlEncoder().withoutPadding().encodeToString(visible.getLast().id().getBytes(StandardCharsets.UTF_8)));
  }

  @Transactional(readOnly = true)
  QualificationReceipt receipt(DelegationPrincipal actor, String id, ReceiptRequest request) {
    requireCapability(actor, "evidence.event.read");
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT qualification_state, payload_digest, revision, reviewed_at, observed_at, "
                + HAS_EVIDENCE_CONFLICT + " AS has_conflict FROM " + SCHEMA
                + "evidence_event e WHERE tenant_id = ? AND environment_id = ? AND id = ? "
                + "AND (? = '*' OR service_id = ?)",
            actor.tenantId(),
            actor.environmentId(),
            id,
            actor.serviceScope(),
            actor.serviceScope());
    if (rows.isEmpty()) {
      throw notFound();
    }
    Map<String, Object> row = rows.getFirst();
    String state = (String) row.get("qualification_state");
    boolean digestMatches = request.expectedDigest().equals(row.get("payload_digest").toString());
    Timestamp reviewedAt = (Timestamp) row.get("reviewed_at");
    boolean observationWithinCutoff = !((Timestamp) row.get("observed_at")).toInstant().isAfter(request.cutoff());
    boolean withinCutoff = reviewedAt != null && !reviewedAt.toInstant().isAfter(request.cutoff())
        && observationWithinCutoff;
    boolean conflict = Boolean.TRUE.equals(row.get("has_conflict"));
    boolean usable = "QUALIFIED".equals(state) && digestMatches && withinCutoff && !conflict;
    String reason =
        usable
            ? "Qualified by independent review before the cutoff."
            : conflict ? "Evidence has a retained source conflict."
            : switch (state) {
              case "SAMPLE_ONLY" -> "Sample data is never accepted evidence.";
              case "UNVERIFIED" -> "Evidence has not been independently reviewed.";
              case "REJECTED" -> "Evidence was rejected in review.";
              default -> !digestMatches ? "Digest differs from the expected digest."
                  : !observationWithinCutoff ? "Observation happened after the cutoff."
                  : "Qualification happened after the cutoff.";
            };
    return new QualificationReceipt(
        id,
        request.purpose(),
        state,
        usable,
        digestMatches,
        withinCutoff,
        row.get("payload_digest").toString(),
        ((Number) row.get("revision")).longValue(),
        reason);
  }

  @Transactional(readOnly = true)
  TopologySummaryResult topologySummary(Jwt jwt, String id, TopologySummaryRequest request) {
    DelegationPrincipal actor = DelegationPrincipal.from(jwt);
    if (!"S04".equals(jwt.getAudience().size() == 1 ? jwt.getAudience().getFirst() : null)
        || !id.equals(actor.serviceScope())
        || !"evidence_event".equals(jwt.getClaimAsString("resource_type"))
        || !id.equals(jwt.getClaimAsString("resource_id"))
        || !TOPOLOGY_SUMMARY_CAPABILITY.equals(actor.capabilities().size() == 1
            ? actor.capabilities().getFirst()
            : null)
        || !TOPOLOGY_PURPOSES.contains(request.purpose())
        || !request.purpose().equals(jwt.getClaimAsString("purpose"))) {
      throw new EvidenceException(
          HttpStatus.FORBIDDEN,
          "DELEGATION_SCOPE_MISMATCH",
          "A matching S01 resource- and purpose-scoped delegation is required.");
    }

    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT e.id, e.source_class, e.source_ref, e.sequence, e.observed_at, e.kind, "
                + "e.payload, e.payload_digest, e.qualification_state, e.revision, e.reviewed_at, "
                + HAS_EVIDENCE_CONFLICT + " AS has_conflict FROM " + SCHEMA
                + "evidence_event e WHERE e.tenant_id = ? AND e.environment_id = ? AND e.id = ?",
            actor.tenantId(),
            actor.environmentId(),
            id);
    if (rows.isEmpty()) {
      throw notFound();
    }
    Map<String, Object> row = rows.getFirst();
    String digest = row.get("payload_digest") == null ? "" : row.get("payload_digest").toString().trim();
    String payload = (String) row.get("payload");
    Instant observedAt = ((Timestamp) row.get("observed_at")).toInstant();
    Timestamp reviewedAt = (Timestamp) row.get("reviewed_at");
    long revision = ((Number) row.get("revision")).longValue();
    boolean eligible =
        "PROVIDER".equals(row.get("source_class"))
            && "QUALIFIED".equals(row.get("qualification_state"))
            && request.expectedDigest().equals(digest)
            && payload != null
            && sha256(payload).equals(digest)
            && request.expectedRevision() == revision
            && !observedAt.isAfter(request.cutoff())
            && reviewedAt != null
            && !reviewedAt.toInstant().isAfter(request.cutoff())
            && !Boolean.TRUE.equals(row.get("has_conflict"));
    if (!eligible) {
      throw conflict(
          "EVIDENCE_NOT_CURRENT",
          "Evidence is stale, mismatched, unqualified, or has a retained source conflict.");
    }

    return new TopologySummaryResult(
        true,
        new TopologySummary(
            (String) row.get("id"),
            (String) row.get("source_class"),
            (String) row.get("source_ref"),
            ((Number) row.get("sequence")).longValue(),
            observedAt.toString(),
            (String) row.get("kind"),
            (String) row.get("qualification_state"),
            revision,
            digest));
  }

  @Transactional(readOnly = true)
  MeasurementReceipt measurement(DelegationPrincipal actor, String id, MeasurementRequest request) {
    requireCapability(actor, "evidence.event.read");
    List<Map<String, Object>> rows = jdbc.queryForList(
        "SELECT payload, payload_digest, kind, observed_at, qualification_state, revision, reviewed_at, "
            + HAS_EVIDENCE_CONFLICT + " AS has_conflict FROM "
            + SCHEMA + "evidence_event e WHERE tenant_id = ? AND environment_id = ? AND id = ? "
            + "AND (? = '*' OR service_id = ?)",
        actor.tenantId(), actor.environmentId(), id, actor.serviceScope(), actor.serviceScope());
    if (rows.isEmpty()) throw notFound();
    Map<String, Object> row = rows.getFirst();
    String payload = (String) row.get("payload");
    String digest = row.get("payload_digest").toString().trim();
    String state = (String) row.get("qualification_state");
    String kind = (String) row.get("kind");
    Timestamp reviewedAt = (Timestamp) row.get("reviewed_at");
    ParsedMeasurement parsed = parseMeasurement(payload);
    String reason = Boolean.TRUE.equals(row.get("has_conflict")) ? "Evidence has a retained source conflict."
        : !"QUALIFIED".equals(state) ? "Evidence is not qualified."
        : reviewedAt == null || reviewedAt.toInstant().isAfter(request.cutoff()) ? "Qualification happened after the cutoff."
        : ((Timestamp) row.get("observed_at")).toInstant().isAfter(request.cutoff()) ? "Observation happened after the cutoff."
        : !sha256(payload).equals(digest) ? "Stored payload digest does not match."
        : !isMeasurementKind(kind) ? "Evidence is not a measurement observation."
        : parsed == null ? "Payload is not a valid measurement."
        : !parsed.metric().equals(request.metric()) ? "Payload metric does not match."
        : null;
    return new MeasurementReceipt(id, request.purpose(), state, reason == null, digest,
        ((Number) row.get("revision")).longValue(), ((Timestamp) row.get("observed_at")).toInstant(),
        kind, parsed == null ? null : parsed.metric(),
        parsed == null ? null : parsed.value(), parsed == null ? null : parsed.unit(),
        reason == null ? "Qualified measurement." : reason);
  }

  private static boolean isMeasurementKind(String kind) {
    String prefix = "measurement.";
    return kind != null && kind.startsWith(prefix) && kind.length() > prefix.length();
  }

  private ParsedMeasurement parseMeasurement(String payload) {
    try (JsonParser parser = objectMapper.tokenStreamFactory().createParser(payload)) {
      if (parser.nextToken() != JsonToken.START_OBJECT) return null;
      String metric = null;
      String unit = null;
      String measurementKind = null;
      String measurementRef = null;
      BigDecimal value = null;
      BigDecimal percentile = null;
      boolean percentileIsNull = false;
      var fields = new HashSet<String>();
      while (parser.nextToken() != JsonToken.END_OBJECT) {
        if (parser.currentToken() != JsonToken.PROPERTY_NAME) return null;
        String name = parser.currentName();
        if (!fields.add(name)) return null;
        JsonToken token = parser.nextToken();
        if ("metric".equals(name) && token == JsonToken.VALUE_STRING) metric = parser.getText();
        else if ("unit".equals(name) && token == JsonToken.VALUE_STRING) unit = parser.getText();
        else if ("value".equals(name) && (token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT))
          value = parser.getDecimalValue();
        else if (("window_start".equals(name) || "window_end".equals(name))
            && token == JsonToken.VALUE_STRING) {
          parser.getText();
        }
        else if ("measurement_kind".equals(name) && token == JsonToken.VALUE_STRING)
          measurementKind = parser.getText();
        else if ("measurement_ref".equals(name) && token == JsonToken.VALUE_STRING)
          measurementRef = parser.getText();
        else if ("percentile".equals(name) && token == JsonToken.VALUE_NULL)
          percentileIsNull = true;
        else if ("percentile".equals(name)
            && (token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT))
          percentile = parser.getDecimalValue();
        else return null;
      }
      if (parser.nextToken() != null
          || !fields.containsAll(Set.of("metric", "value", "unit"))
          || !(fields.size() == 3 || fields.size() == 5 || fields.size() == 8)
          || fields.contains("window_start") != fields.contains("window_end")
          || metric == null
          || metric.isBlank()
          || unit == null || unit.isBlank() || value == null) return null;
      boolean hasExtendedMeasurement =
          fields.contains("measurement_kind")
              || fields.contains("measurement_ref")
              || fields.contains("percentile");
      if (hasExtendedMeasurement
          && (!fields.containsAll(Set.of("measurement_kind", "measurement_ref", "percentile"))
              || measurementKind == null
              || !Set.of("THRESHOLD", "PERCENTILE", "RATIO", "RECOVERY_TIME", "OUTCOME")
                  .contains(measurementKind)
              || measurementRef == null
              || measurementRef.isBlank()
              || ("PERCENTILE".equals(measurementKind)
                  ? percentile == null
                      || percentile.signum() <= 0
                      || percentile.compareTo(BigDecimal.valueOf(100)) > 0
                  : !percentileIsNull))) {
        return null;
      }
      return new ParsedMeasurement(metric, value, unit);
    } catch (JacksonException | ArithmeticException | IllegalArgumentException error) {
      return null;
    }
  }

  private record ParsedMeasurement(String metric, BigDecimal value, String unit) {}

  @Transactional
  InboxResult receiveEvent(DelegationPrincipal actor, InboxEvent event) {
    requireCapability(actor, "evidence.inbox.write");
    requireGlobalScope(actor);
    // Event IDs are scoped independently of aggregates, so serialize both identities.
    jdbc.queryForList(
        "SELECT pg_advisory_xact_lock(hashtext('evidence.inbox.event'), hashtext(?))",
        actor.tenantId() + "|" + actor.environmentId() + "|" + event.eventId());
    jdbc.queryForList(
        "SELECT pg_advisory_xact_lock(hashtext(?))",
        actor.tenantId()
            + "|"
            + actor.environmentId()
            + "|"
            + event.sourceService()
            + "|"
            + event.aggregateType()
            + "|"
            + event.aggregateId());
    if (!sha256(event.payload()).equals(event.payloadDigest())) {
      return quarantineEvent(actor, event, "DIGEST_MISMATCH");
    }
    List<Boolean> seen =
        jdbc.query(
            "SELECT source_service, event_type, aggregate_type, aggregate_id, aggregate_sequence, "
                + "payload, payload_digest FROM "
                + SCHEMA + "inbox_event WHERE tenant_id = ? "
                + "AND environment_id = ? AND event_id = ?",
            (row, index) ->
                event.sourceService().equals(row.getString("source_service"))
                    && event.eventType().equals(row.getString("event_type"))
                    && event.aggregateType().equals(row.getString("aggregate_type"))
                    && event.aggregateId().equals(row.getString("aggregate_id"))
                    && event.aggregateSequence() == row.getLong("aggregate_sequence")
                    && event.payload().equals(row.getString("payload"))
                    && event.payloadDigest().equals(row.getString("payload_digest").trim()),
            actor.tenantId(),
            actor.environmentId(),
            event.eventId());
    if (!seen.isEmpty()) {
      return seen.getFirst()
          ? new InboxResult(event.eventId(), "DUPLICATE", "Already received.")
          : quarantineEvent(actor, event, "EVENT_CONFLICT");
    }
    Long last =
        jdbc.queryForObject(
            "SELECT max(aggregate_sequence) FROM " + SCHEMA + "inbox_event WHERE tenant_id = ? "
                + "AND environment_id = ? AND source_service = ? AND aggregate_type = ? "
                + "AND aggregate_id = ?",
            Long.class,
            actor.tenantId(),
            actor.environmentId(),
            event.sourceService(),
            event.aggregateType(),
            event.aggregateId());
    if (last != null && event.aggregateSequence() <= last) {
      return quarantineEvent(actor, event, "SEQUENCE_REGRESSION");
    }
    jdbc.update(
        "INSERT INTO " + SCHEMA + "inbox_event (tenant_id, environment_id, event_id, source_service, "
            + "event_type, aggregate_type, aggregate_id, aggregate_sequence, payload, payload_digest) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        actor.tenantId(),
        actor.environmentId(),
        event.eventId(),
        event.sourceService(),
        event.eventType(),
        event.aggregateType(),
        event.aggregateId(),
        event.aggregateSequence(),
        event.payload(),
        event.payloadDigest());
    return new InboxResult(event.eventId(), "RECEIVED", "Received.");
  }

  private InboxResult quarantineEvent(DelegationPrincipal actor, InboxEvent event, String reason) {
    jdbc.update(
        "INSERT INTO " + SCHEMA + "quarantined_inbox_event (quarantine_id, tenant_id, environment_id, "
            + "event_id, source_service, event_type, aggregate_type, aggregate_id, aggregate_sequence, "
            + "reason, payload, claimed_digest) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        actor.tenantId(),
        actor.environmentId(),
        event.eventId(),
        event.sourceService(),
        event.eventType(),
        event.aggregateType(),
        event.aggregateId(),
        event.aggregateSequence(),
        reason,
        event.payload(),
        event.payloadDigest());
    return new InboxResult(event.eventId(), "QUARANTINED", reason);
  }

  private Evidence find(DelegationPrincipal actor, String id) {
    List<Evidence> rows =
        jdbc.query(
            "SELECT " + COLUMNS + " FROM " + SCHEMA + "evidence_event WHERE tenant_id = ? "
                + "AND environment_id = ? AND id = ? AND (? = '*' OR service_id = ?)",
            (rs, n) -> evidenceRow(rs),
            actor.tenantId(),
            actor.environmentId(),
            id,
            actor.serviceScope(),
            actor.serviceScope());
    if (rows.isEmpty()) {
      throw notFound();
    }
    return rows.getFirst();
  }

  private static Evidence evidenceRow(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new Evidence(
        rs.getString("id"),
        rs.getString("source_class"),
        rs.getString("source_ref"),
        rs.getLong("sequence"),
        rs.getTimestamp("observed_at").toInstant().toString(),
        rs.getString("kind"),
        rs.getString("service_id"),
        rs.getString("schema_version"),
        rs.getString("payload_digest"),
        rs.getLong("payload_byte_length"),
        rs.getString("submitted_by"),
        rs.getString("qualification_state"),
        rs.getLong("revision"),
        rs.getString("reviewed_by"),
        rs.getString("review_reason"));
  }

  private void appendOutbox(
      DelegationPrincipal actor, String aggregateId, long sequence, String eventType, Map<String, Object> body) {
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("actor_id", actor.principalId());
    envelope.put("event_type", eventType);
    envelope.put("aggregate_id", aggregateId);
    envelope.put("revision", sequence);
    envelope.putAll(body);
    String payload;
    try {
      payload = objectMapper.writeValueAsString(envelope);
    } catch (JacksonException error) {
      throw new IllegalStateException("Could not serialize the event.", error);
    }
    jdbc.update(
        "INSERT INTO " + SCHEMA + "outbox_event (event_id, tenant_id, environment_id, aggregate_type, "
            + "aggregate_id, aggregate_sequence, event_type, payload, payload_digest) "
            + "VALUES (?, ?, ?, 'EVIDENCE', ?, ?, ?, ?::jsonb, ?)",
        UUID.randomUUID(),
        actor.tenantId(),
        actor.environmentId(),
        aggregateId,
        sequence,
        eventType,
        payload,
        sha256(payload));
  }

  static int validatePageSize(int pageSize) {
    if (pageSize < 1 || pageSize > 100) {
      throw new EvidenceException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "page_size must be between 1 and 100.");
    }
    return pageSize;
  }

  private static String decodeCursor(String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return "";
    }
    try {
      String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      if (value.isBlank() || value.length() > 200) {
        throw new IllegalArgumentException();
      }
      return value;
    } catch (IllegalArgumentException error) {
      throw new EvidenceException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "cursor is malformed.");
    }
  }

  private static void requireCapability(DelegationPrincipal actor, String capability) {
    if (!actor.capabilities().contains(capability)) {
      throw new EvidenceException(
          HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "The current principal cannot perform this evidence operation.");
    }
  }

  private static void requireGlobalScope(DelegationPrincipal actor) {
    if (!"*".equals(actor.serviceScope())) {
      throw new EvidenceException(
          HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "This evidence operation requires a global service scope.");
    }
  }

  private static EvidenceException notFound() {
    return new EvidenceException(HttpStatus.NOT_FOUND, "NOT_FOUND", "The evidence record does not exist.");
  }

  private static EvidenceException conflict(String code, String message) {
    return new EvidenceException(HttpStatus.CONFLICT, code, message);
  }

  private static String sha256(String value) {
    return sha256(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is required by the runtime.", error);
    }
  }
}
