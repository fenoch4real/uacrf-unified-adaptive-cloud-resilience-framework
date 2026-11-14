package org.uacrf.connections;

import static org.uacrf.connections.ConnectionsRequests.*;
import static org.uacrf.connections.ConnectionsResponses.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uacrf.security.DelegationPrincipal;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
class ConnectionsService {
  private static final String SCHEMA = "uacrf_connections.";
  private static final String UNACCEPTED_REASON =
      "No conformance-tested read-only collector exists for this provider yet.";
  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  ConnectionsService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Transactional
  Receipt register(DelegationPrincipal actor, RegisterConnection request, String key) {
    requireCapability(actor, "connections.connection.write");
    requireGlobalScope(actor);
    if (!actor.eligibleOwnerIds().contains(request.ownerId())) {
      throw denied("The selected owner is not currently eligible in this environment.");
    }
    if (request.expectedRevision() != 0) {
      throw conflict("CREATE_REVISION_MISMATCH", "A new connection must use expected_revision 0.");
    }
    boolean accepted = request.provider() == Provider.SAMPLE_LOCAL;
    return executeOnce(
        actor,
        "POST /v1/connections",
        key,
        request,
        request.id(),
        "CONNECTION",
        "connections.connection.registered",
        () ->
            jdbc.update(
                "INSERT INTO "
                    + SCHEMA
                    + "connection (tenant_id, environment_id, id, name, provider, "
                    + "read_only_identity_ref, owner_id, status, status_reason, revision) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1)",
                actor.tenantId(),
                actor.environmentId(),
                request.id(),
                request.name().trim(),
                request.provider().name(),
                request.readOnlyIdentityRef(),
                request.ownerId(),
                accepted ? "ACTIVE" : "UNACCEPTED",
                accepted ? "Local sample collector is available." : UNACCEPTED_REASON));
  }

  @Transactional(readOnly = true)
  Page<Connection> list(DelegationPrincipal actor, int pageSize, String cursor) {
    requireCapability(actor, "connections.connection.read");
    List<Connection> rows =
        jdbc.query(
            "SELECT id, name, provider, read_only_identity_ref, owner_id, status, status_reason, "
                + "revision FROM "
                + SCHEMA
                + "connection WHERE tenant_id = ? AND environment_id = ? AND id > ? "
                + "ORDER BY id LIMIT ?",
            (rs, row) -> connectionRow(rs),
            actor.tenantId(),
            actor.environmentId(),
            decodeCursor(cursor),
            pageSize + 1);
    return page(rows, pageSize, Connection::id);
  }

  @Transactional(readOnly = true)
  Connection get(DelegationPrincipal actor, String id) {
    requireCapability(actor, "connections.connection.read");
    return find(actor, id);
  }

  @Transactional
  Receipt bind(DelegationPrincipal actor, String connectionId, BindSource request, String key) {
    requireCapability(actor, "connections.connection.write");
    requireGlobalScope(actor);
    if (request.expectedRevision() != 0) {
      throw conflict("CREATE_REVISION_MISMATCH", "A new binding must use expected_revision 0.");
    }
    Connection connection = find(actor, connectionId);
    if (!"ACTIVE".equals(connection.status())) {
      throw conflict("CONNECTION_NOT_ACCEPTED", connection.statusReason());
    }
    return executeOnce(
        actor,
        "POST /v1/connections/" + connectionId + "/source-bindings",
        key,
        request,
        request.id(),
        "SOURCE_BINDING",
        "connections.source-binding.created",
        () ->
            jdbc.update(
                "INSERT INTO "
                    + SCHEMA
                    + "source_binding (tenant_id, environment_id, id, connection_id, source_ref, "
                    + "service_ref, revision) VALUES (?, ?, ?, ?, ?, ?, 1)",
                actor.tenantId(),
                actor.environmentId(),
                request.id(),
                connectionId,
                request.sourceRef(),
                request.serviceRef()));
  }

  @Transactional(readOnly = true)
  Page<Binding> listBindings(DelegationPrincipal actor, String connectionId, int pageSize, String cursor) {
    requireCapability(actor, "connections.connection.read");
    find(actor, connectionId);
    List<Binding> rows =
        jdbc.query(
            "SELECT id, connection_id, source_ref, service_ref, revision FROM "
                + SCHEMA
                + "source_binding WHERE tenant_id = ? AND environment_id = ? "
                + "AND connection_id = ? AND id > ? ORDER BY id LIMIT ?",
            (rs, row) ->
                new Binding(
                    rs.getString("id"),
                    rs.getString("connection_id"),
                    rs.getString("source_ref"),
                    rs.getString("service_ref"),
                    rs.getLong("revision")),
            actor.tenantId(),
            actor.environmentId(),
            connectionId,
            decodeCursor(cursor),
            pageSize + 1);
    return page(rows, pageSize, Binding::id);
  }

  private Connection find(DelegationPrincipal actor, String id) {
    List<Connection> rows =
        jdbc.query(
            "SELECT id, name, provider, read_only_identity_ref, owner_id, status, status_reason, "
                + "revision FROM "
                + SCHEMA
                + "connection WHERE tenant_id = ? AND environment_id = ? AND id = ?",
            (rs, row) -> connectionRow(rs),
            actor.tenantId(),
            actor.environmentId(),
            id);
    if (rows.isEmpty()) {
      throw new ConnectionsException(
          HttpStatus.NOT_FOUND, "NOT_FOUND", "The connection is absent or not visible.");
    }
    return rows.getFirst();
  }

  private static Connection connectionRow(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new Connection(
        rs.getString("id"),
        rs.getString("name"),
        rs.getString("provider"),
        rs.getString("read_only_identity_ref"),
        rs.getString("owner_id"),
        rs.getString("status"),
        rs.getString("status_reason"),
        rs.getLong("revision"));
  }

  private Receipt executeOnce(
      DelegationPrincipal actor,
      String operation,
      String key,
      Object request,
      String aggregateId,
      String aggregateType,
      String eventType,
      SqlWrite write) {
    validateIdempotencyKey(key);
    String digest = sha256(serialize(request));
    int inserted =
        jdbc.update(
            "INSERT INTO "
                + SCHEMA
                + "idempotency_record (tenant_id, environment_id, actor_id, operation, "
                + "idempotency_key, payload_digest) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
            actor.tenantId(),
            actor.environmentId(),
            actor.principalId(),
            operation,
            key,
            digest);
    if (inserted == 0) {
      List<Map<String, Object>> records =
          jdbc.queryForList(
              "SELECT payload_digest, response::text FROM "
                  + SCHEMA
                  + "idempotency_record WHERE tenant_id = ? AND environment_id = ? "
                  + "AND actor_id = ? AND operation = ? AND idempotency_key = ? FOR UPDATE",
              actor.tenantId(),
              actor.environmentId(),
              actor.principalId(),
              operation,
              key);
      if (records.isEmpty() || !digest.equals(records.getFirst().get("payload_digest"))) {
        throw conflict(
            "IDEMPOTENCY_CONFLICT", "The idempotency key was already used for different content.");
      }
      Object response = records.getFirst().get("response");
      if (response == null) {
        throw conflict("COMMAND_IN_PROGRESS", "The original request has not completed.");
      }
      try {
        return objectMapper.readValue(response.toString(), Receipt.class);
      } catch (JacksonException error) {
        throw new IllegalStateException("Could not read the retained idempotency result.", error);
      }
    }
    try {
      write.apply();
    } catch (DuplicateKeyException error) {
      throw conflict("CONNECTIONS_ID_CONFLICT", "A record with this ID or source already exists.");
    }
    Receipt receipt = new Receipt(aggregateId, 1, aggregateType, "CREATED", "NONE");
    jdbc.update(
        "UPDATE "
            + SCHEMA
            + "idempotency_record SET response = ?::jsonb WHERE tenant_id = ? "
            + "AND environment_id = ? AND actor_id = ? AND operation = ? AND idempotency_key = ?",
        serialize(receipt),
        actor.tenantId(),
        actor.environmentId(),
        actor.principalId(),
        operation,
        key);
    appendOutbox(
        actor,
        aggregateType,
        aggregateId,
        eventType,
        Map.of("record", request));
    return receipt;
  }

  private void appendOutbox(
      DelegationPrincipal actor,
      String aggregateType,
      String aggregateId,
      String eventType,
      Map<String, Object> body) {
    java.util.LinkedHashMap<String, Object> envelope = new java.util.LinkedHashMap<>();
    envelope.put("actor_id", actor.principalId());
    envelope.put("event_type", eventType);
    envelope.put("aggregate_id", aggregateId);
    envelope.put("revision", 1);
    envelope.putAll(body);
    String payload = serialize(envelope);
    jdbc.update(
        "INSERT INTO "
            + SCHEMA
            + "outbox_event (event_id, tenant_id, environment_id, aggregate_type, aggregate_id, "
            + "aggregate_sequence, event_type, payload, payload_digest) "
            + "VALUES (?, ?, ?, ?, ?, 1, ?, ?::jsonb, ?)",
        UUID.randomUUID(),
        actor.tenantId(),
        actor.environmentId(),
        aggregateType,
        aggregateId,
        eventType,
        payload,
        sha256(payload));
  }

  @Transactional
  IngestResult ingest(DelegationPrincipal actor, String connectionId, SubmitObservation request) {
    requireCapability(actor, "connections.observation.write");
    requireGlobalScope(actor);
    Connection connection = find(actor, connectionId);
    if (!"ACTIVE".equals(connection.status())) {
      throw conflict("CONNECTION_NOT_ACCEPTED", connection.statusReason());
    }
    List<Long> bound =
        jdbc.queryForList(
            "SELECT last_sequence FROM "
                + SCHEMA
                + "source_binding WHERE tenant_id = ? AND environment_id = ? "
                + "AND connection_id = ? AND source_ref = ? FOR UPDATE",
            Long.class,
            actor.tenantId(),
            actor.environmentId(),
            connectionId,
            request.sourceRef());
    if (bound.isEmpty()) {
      throw conflict("SOURCE_NOT_BOUND", "The source is not bound to this connection.");
    }
    if (!sha256(request.payload()).equals(request.payloadDigest())) {
      return quarantine(actor, connectionId, request, "DIGEST_MISMATCH");
    }
    List<Map<String, Object>> existing =
        jdbc.queryForList(
            "SELECT payload_digest, source_ref, sequence FROM "
                + SCHEMA
                + "observation WHERE tenant_id = ? AND environment_id = ? "
                + "AND connection_id = ? AND id = ?",
            actor.tenantId(),
            actor.environmentId(),
            connectionId,
            request.id());
    if (!existing.isEmpty()) {
      Map<String, Object> row = existing.getFirst();
      boolean same =
          request.payloadDigest().equals(row.get("payload_digest"))
              && request.sourceRef().equals(row.get("source_ref"))
              && request.sequence() == ((Number) row.get("sequence")).longValue();
      return same
          ? new IngestResult(request.id(), "DUPLICATE", "Already accepted.", request.sequence())
          : quarantine(actor, connectionId, request, "REPLAY_CONFLICT");
    }
    if (request.sequence() <= bound.getFirst()) {
      return quarantine(actor, connectionId, request, "STALE_SEQUENCE");
    }
    jdbc.update(
        "INSERT INTO "
            + SCHEMA
            + "observation (tenant_id, environment_id, connection_id, id, source_ref, sequence, "
            + "observed_at, kind, payload, payload_digest) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        actor.tenantId(),
        actor.environmentId(),
        connectionId,
        request.id(),
        request.sourceRef(),
        request.sequence(),
        java.sql.Timestamp.from(request.observedAt()),
        request.kind(),
        request.payload(),
        request.payloadDigest());
    jdbc.update(
        "UPDATE "
            + SCHEMA
            + "source_binding SET last_sequence = ? WHERE tenant_id = ? AND environment_id = ? "
            + "AND connection_id = ? AND source_ref = ?",
        request.sequence(),
        actor.tenantId(),
        actor.environmentId(),
        connectionId,
        request.sourceRef());
    appendOutbox(
        actor,
        "OBSERVATION",
        request.id(),
        "connections.observation.accepted",
        Map.of(
            "connection_id", connectionId,
            "source_ref", request.sourceRef(),
            "sequence", request.sequence(),
            "kind", request.kind(),
            "payload_digest", request.payloadDigest()));
    return new IngestResult(request.id(), "ACCEPTED", "Accepted.", request.sequence());
  }

  private IngestResult quarantine(
      DelegationPrincipal actor, String connectionId, SubmitObservation request, String reason) {
    jdbc.update(
        "INSERT INTO "
            + SCHEMA
            + "quarantined_observation (quarantine_id, tenant_id, environment_id, connection_id, "
            + "observation_id, source_ref, sequence, reason, payload, claimed_digest) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        actor.tenantId(),
        actor.environmentId(),
        connectionId,
        request.id(),
        request.sourceRef(),
        request.sequence(),
        reason,
        request.payload(),
        request.payloadDigest());
    return new IngestResult(request.id(), "QUARANTINED", reason, request.sequence());
  }

  @Transactional(readOnly = true)
  Page<Observation> listObservations(
      DelegationPrincipal actor, String connectionId, int pageSize, String cursor) {
    requireCapability(actor, "connections.connection.read");
    find(actor, connectionId);
    List<Observation> rows =
        jdbc.query(
            "SELECT id, source_ref, sequence, observed_at, kind, payload, payload_digest FROM "
                + SCHEMA
                + "observation WHERE tenant_id = ? AND environment_id = ? AND connection_id = ? "
                + "AND id > ? ORDER BY id LIMIT ?",
            (rs, row) ->
                new Observation(
                    rs.getString("id"),
                    rs.getString("source_ref"),
                    rs.getLong("sequence"),
                    rs.getTimestamp("observed_at").toInstant().toString(),
                    rs.getString("kind"),
                    rs.getString("payload"),
                    rs.getString("payload_digest")),
            actor.tenantId(),
            actor.environmentId(),
            connectionId,
            decodeCursor(cursor),
            pageSize + 1);
    return page(rows, pageSize, Observation::id);
  }

  @Transactional(readOnly = true)
  Page<Quarantined> listQuarantine(
      DelegationPrincipal actor, String connectionId, int pageSize, String cursor) {
    requireCapability(actor, "connections.connection.read");
    find(actor, connectionId);
    List<Quarantined> rows =
        jdbc.query(
            "SELECT quarantine_id::text AS id, observation_id, source_ref, sequence, reason, "
                + "received_at FROM "
                + SCHEMA
                + "quarantined_observation WHERE tenant_id = ? AND environment_id = ? "
                + "AND connection_id = ? AND quarantine_id::text > ? ORDER BY quarantine_id LIMIT ?",
            (rs, row) ->
                new Quarantined(
                    rs.getString("id"),
                    rs.getString("observation_id"),
                    rs.getString("source_ref"),
                    rs.getLong("sequence"),
                    rs.getString("reason"),
                    rs.getTimestamp("received_at").toInstant().toString()),
            actor.tenantId(),
            actor.environmentId(),
            connectionId,
            decodeCursor(cursor),
            pageSize + 1);
    return page(rows, pageSize, Quarantined::id);
  }

  private static void requireCapability(DelegationPrincipal actor, String capability) {
    if (!actor.capabilities().contains(capability)) {
      throw denied("The current principal cannot perform this connections operation.");
    }
  }

  private static void requireGlobalScope(DelegationPrincipal actor) {
    if (!"*".equals(actor.serviceScope())) {
      throw denied("This connections operation requires a global service scope.");
    }
  }

  private static <T> Page<T> page(List<T> rows, int pageSize, IdExtractor<T> id) {
    if (rows.size() <= pageSize) {
      return new Page<>(rows, null);
    }
    List<T> visible = new ArrayList<>(rows.subList(0, pageSize));
    return new Page<>(visible, encodeCursor(id.id(visible.getLast())));
  }

  static int validatePageSize(int pageSize) {
    if (pageSize < 1 || pageSize > 100) {
      throw invalid("page_size must be between 1 and 100.");
    }
    return pageSize;
  }

  private static void validateIdempotencyKey(String key) {
    if (key == null || key.isBlank() || key.length() > 200 || key.chars().anyMatch(Character::isWhitespace)) {
      throw invalid("Idempotency-Key must be a non-empty token of at most 200 characters.");
    }
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
      throw invalid("cursor is malformed.");
    }
  }

  private static String encodeCursor(String id) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(id.getBytes(StandardCharsets.UTF_8));
  }

  private String serialize(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException error) {
      throw new IllegalStateException("Could not serialize the record.", error);
    }
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is required by the runtime.", error);
    }
  }

  private static ConnectionsException denied(String message) {
    return new ConnectionsException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", message);
  }

  private static ConnectionsException invalid(String message) {
    return new ConnectionsException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
  }

  private static ConnectionsException conflict(String code, String message) {
    return new ConnectionsException(HttpStatus.CONFLICT, code, message);
  }

  @FunctionalInterface
  private interface SqlWrite {
    int apply();
  }

  @FunctionalInterface
  private interface IdExtractor<T> {
    String id(T value);
  }
}
