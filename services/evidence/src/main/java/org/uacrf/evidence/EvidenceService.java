package org.uacrf.evidence;

import static org.uacrf.evidence.EvidenceRequests.*;
import static org.uacrf.evidence.EvidenceResponses.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uacrf.security.DelegationPrincipal;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
class EvidenceService {
  private static final String SCHEMA = "uacrf_evidence.";
  private static final String COLUMNS =
      "id, source_class, source_ref, sequence, observed_at, kind, payload_digest, submitted_by, "
          + "qualification_state, revision, reviewed_by, review_reason";

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  EvidenceService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Transactional
  SubmitResult submit(DelegationPrincipal actor, SubmitEvidence request) {
    requireCapability(actor, "evidence.event.write");
    requireGlobalScope(actor);
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
    if (!sha256(request.payload()).equals(request.payloadDigest())) {
      return quarantine(actor, request, "DIGEST_MISMATCH");
    }
    List<Map<String, Object>> existing =
        jdbc.queryForList(
            "SELECT payload_digest, source_ref, sequence, source_class, qualification_state FROM "
                + SCHEMA + "evidence_event WHERE tenant_id = ? AND environment_id = ? AND id = ?",
            actor.tenantId(),
            actor.environmentId(),
            request.id());
    if (!existing.isEmpty()) {
      Map<String, Object> row = existing.getFirst();
      boolean same =
          request.payloadDigest().equals(row.get("payload_digest"))
              && request.sourceRef().equals(row.get("source_ref"))
              && request.sourceClass().name().equals(row.get("source_class"))
              && request.sequence() == ((Number) row.get("sequence")).longValue();
      return same
          ? new SubmitResult(request.id(), "DUPLICATE", "Already accepted.", (String) row.get("qualification_state"))
          : quarantine(actor, request, "REPLAY_CONFLICT");
    }
    if (request.sequence() <= last) {
      return quarantine(actor, request, "STALE_SEQUENCE");
    }
    jdbc.update(
        "INSERT INTO " + SCHEMA + "evidence_event (tenant_id, environment_id, id, source_class, "
            + "source_ref, sequence, observed_at, kind, payload, payload_digest, submitted_by, "
            + "qualification_state, revision) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)",
        actor.tenantId(),
        actor.environmentId(),
        request.id(),
        request.sourceClass().name(),
        request.sourceRef(),
        request.sequence(),
        Timestamp.from(request.observedAt()),
        request.kind(),
        request.payload(),
        request.payloadDigest(),
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
            "qualification_state", state));
    return new SubmitResult(request.id(), "ACCEPTED", "Accepted.", state);
  }

  private SubmitResult quarantine(DelegationPrincipal actor, SubmitEvidence request, String reason) {
    jdbc.update(
        "INSERT INTO " + SCHEMA + "quarantined_evidence (quarantine_id, tenant_id, environment_id, "
            + "evidence_id, source_ref, sequence, reason, payload, claimed_digest) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        actor.tenantId(),
        actor.environmentId(),
        request.id(),
        request.sourceRef(),
        request.sequence(),
        reason,
        request.payload(),
        request.payloadDigest());
    return new SubmitResult(request.id(), "QUARANTINED", reason, null);
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
                + "AND environment_id = ? AND id > ? ORDER BY id LIMIT ?",
            (rs, n) -> evidenceRow(rs),
            actor.tenantId(),
            actor.environmentId(),
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
            "SELECT qualification_state, payload_digest, revision, reviewed_at FROM " + SCHEMA
                + "evidence_event WHERE tenant_id = ? AND environment_id = ? AND id = ?",
            actor.tenantId(),
            actor.environmentId(),
            id);
    if (rows.isEmpty()) {
      throw notFound();
    }
    Map<String, Object> row = rows.getFirst();
    String state = (String) row.get("qualification_state");
    boolean digestMatches = request.expectedDigest().equals(row.get("payload_digest").toString());
    Timestamp reviewedAt = (Timestamp) row.get("reviewed_at");
    boolean withinCutoff = reviewedAt != null && !reviewedAt.toInstant().isAfter(request.cutoff());
    boolean usable = "QUALIFIED".equals(state) && digestMatches && withinCutoff;
    String reason =
        usable
            ? "Qualified by independent review before the cutoff."
            : switch (state) {
              case "SAMPLE_ONLY" -> "Sample data is never accepted evidence.";
              case "UNVERIFIED" -> "Evidence has not been independently reviewed.";
              case "REJECTED" -> "Evidence was rejected in review.";
              default -> digestMatches ? "Qualification happened after the cutoff." : "Digest differs from the expected digest.";
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

  @Transactional
  InboxResult receiveEvent(DelegationPrincipal actor, InboxEvent event) {
    requireCapability(actor, "evidence.inbox.write");
    requireGlobalScope(actor);
    jdbc.queryForList(
        "SELECT pg_advisory_xact_lock(hashtext(?))",
        actor.tenantId() + "|" + actor.environmentId() + "|" + event.sourceService() + "|" + event.aggregateId());
    if (!sha256(event.payload()).equals(event.payloadDigest())) {
      return quarantineEvent(actor, event, "DIGEST_MISMATCH");
    }
    List<String> seen =
        jdbc.queryForList(
            "SELECT payload_digest FROM " + SCHEMA + "inbox_event WHERE tenant_id = ? "
                + "AND environment_id = ? AND event_id = ?",
            String.class,
            actor.tenantId(),
            actor.environmentId(),
            event.eventId());
    if (!seen.isEmpty()) {
      return event.payloadDigest().equals(seen.getFirst().trim())
          ? new InboxResult(event.eventId(), "DUPLICATE", "Already received.")
          : quarantineEvent(actor, event, "EVENT_CONFLICT");
    }
    Long last =
        jdbc.queryForObject(
            "SELECT max(aggregate_sequence) FROM " + SCHEMA + "inbox_event WHERE tenant_id = ? "
                + "AND environment_id = ? AND source_service = ? AND aggregate_id = ?",
            Long.class,
            actor.tenantId(),
            actor.environmentId(),
            event.sourceService(),
            event.aggregateId());
    if (last != null && event.aggregateSequence() <= last) {
      return quarantineEvent(actor, event, "SEQUENCE_REGRESSION");
    }
    jdbc.update(
        "INSERT INTO " + SCHEMA + "inbox_event (tenant_id, environment_id, event_id, source_service, "
            + "event_type, aggregate_id, aggregate_sequence, payload, payload_digest) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        actor.tenantId(),
        actor.environmentId(),
        event.eventId(),
        event.sourceService(),
        event.eventType(),
        event.aggregateId(),
        event.aggregateSequence(),
        event.payload(),
        event.payloadDigest());
    return new InboxResult(event.eventId(), "RECEIVED", "Received.");
  }

  private InboxResult quarantineEvent(DelegationPrincipal actor, InboxEvent event, String reason) {
    jdbc.update(
        "INSERT INTO " + SCHEMA + "quarantined_inbox_event (quarantine_id, tenant_id, environment_id, "
            + "event_id, source_service, aggregate_id, aggregate_sequence, reason, payload, claimed_digest) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        actor.tenantId(),
        actor.environmentId(),
        event.eventId(),
        event.sourceService(),
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
                + "AND environment_id = ? AND id = ?",
            (rs, n) -> evidenceRow(rs),
            actor.tenantId(),
            actor.environmentId(),
            id);
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
        rs.getString("payload_digest"),
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
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is required by the runtime.", error);
    }
  }
}
