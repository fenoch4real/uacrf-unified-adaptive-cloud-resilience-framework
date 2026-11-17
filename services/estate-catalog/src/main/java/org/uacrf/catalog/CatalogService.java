package org.uacrf.catalog;

import static org.uacrf.catalog.CatalogRequests.*;
import static org.uacrf.catalog.CatalogResponses.*;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.uacrf.security.DelegationPrincipal;
import org.uacrf.security.OwnerReceiptSignature;

@org.springframework.stereotype.Service
class CatalogService {
  private static final String SCHEMA = "uacrf_catalog.";
  private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
  private static final Set<String> OWNER_RECEIPT_PURPOSES =
      Set.of(
          "topology.dependency-proposal",
          "topology.dependency-review",
          "topology.graph.current-use");
  private static final String RECEIPT_IDENTIFIER_PATTERN =
      "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$";
  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final OwnerReceiptSigningKey ownerReceiptSigningKey;
  private final Clock clock;

  CatalogService(
      JdbcTemplate jdbc,
      ObjectMapper objectMapper,
      OwnerReceiptSigningKey ownerReceiptSigningKey,
      Clock clock) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
    this.ownerReceiptSigningKey = ownerReceiptSigningKey;
    this.clock = clock;
  }

  @Transactional
  Receipt registerService(
      DelegationPrincipal actor, RegisterService request, String idempotencyKey) {
    requireCapability(actor, "catalog.service.write");
    requireServiceScope(actor, request.id());
    requireOwner(actor, request.ownerId());
    if (request.expectedRevision() != 0) {
      throw conflict("CREATE_REVISION_MISMATCH", "A new service must use expected_revision 0.");
    }
    if (!List.of("DRAFT", "ACTIVE", "SUSPENDED", "RETIRED").contains(request.lifecycle())) {
      throw invalid("Invalid service lifecycle.");
    }
    return executeOnce(
        actor,
        "POST /v1/services",
        idempotencyKey,
        request,
        request.id(),
        "SERVICE",
        "catalog.service.registered",
        () ->
            jdbc.update(
                "INSERT INTO "
                    + SCHEMA
                    + "service (tenant_id, environment_id, id, name, owner_id, reason, "
                    + "criticality, lifecycle, revision) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1)",
                actor.tenantId(),
                actor.environmentId(),
                request.id(),
                request.name().trim(),
                request.ownerId(),
                request.reason().trim(),
                request.criticality(),
                request.lifecycle()));
  }

  @Transactional
  Receipt registerAgent(
      DelegationPrincipal actor, RegisterAgent request, String idempotencyKey) {
    requireCapability(actor, "catalog.agent.write");
    if (!"*".equals(actor.serviceScope())
        && !actor.serviceScope().equals(request.serviceId())) {
      throw denied("The delegated service scope does not match the agent registration target.");
    }
    requireOwner(actor, request.ownerId());
    if (!actor.runtimePrincipalIds().contains(request.runtimePrincipalId())) {
      throw denied("The runtime principal is not currently authorized for this registration.");
    }
    if (request.serviceId() != null
        && !exists(
            "SELECT EXISTS (SELECT 1 FROM "
                + SCHEMA
                + "service WHERE tenant_id = ? AND environment_id = ? AND id = ?)",
            actor.tenantId(),
            actor.environmentId(),
            request.serviceId())) {
      throw notFound("The service is absent or not visible.");
    }
    return executeOnce(
        actor,
        "POST /v1/agent-workloads",
        idempotencyKey,
        request,
        request.id(),
        "AGENT_WORKLOAD",
        "catalog.agent-workload.registered",
        () ->
            jdbc.update(
                "INSERT INTO "
                    + SCHEMA
                    + "agent_workload (tenant_id, environment_id, id, service_id, name, kind, "
                    + "owner_id, runtime_principal_id, purpose, revision) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1)",
                actor.tenantId(),
                actor.environmentId(),
                request.id(),
                request.serviceId(),
                request.name().trim(),
                request.kind().name(),
                request.ownerId(),
                request.runtimePrincipalId(),
                request.purpose().trim()));
  }

  @Transactional
  Receipt registerAgentVersion(
      DelegationPrincipal actor,
      String workloadId,
      RegisterAgentVersion request,
      String idempotencyKey) {
    requireCapability(actor, "catalog.agent.write");
    List<Map<String, Object>> profiles =
        jdbc.queryForList(
            "SELECT service_id, owner_id FROM "
                + SCHEMA
                + "agent_workload WHERE tenant_id = ? AND environment_id = ? AND id = ?",
            actor.tenantId(),
            actor.environmentId(),
            workloadId);
    if (profiles.isEmpty()) {
      throw notFound("The agent workload is absent or not visible.");
    }
    Map<String, Object> profile = profiles.getFirst();
    requireServiceScope(actor, (String) profile.get("service_id"));
    requireOwner(actor, (String) profile.get("owner_id"));
    if (request.manifest().tools() != null
        && request
            .manifest()
            .tools()
            .stream()
            .anyMatch(
                tool ->
                    tool.effects() != null
                        && new HashSet<>(tool.effects()).size() != tool.effects().size())) {
      throw invalid("Workload tool effects must be unique.");
    }
    if (!sha256(serialize(request.manifest())).equals(request.digest())) {
      throw invalid("The workload version digest does not match its manifest.");
    }
    return executeOnce(
        actor,
        "POST /v1/agent-workloads/" + workloadId + "/versions",
        idempotencyKey,
        request,
        request.id(),
        "AGENT_WORKLOAD_VERSION",
        null,
        () ->
            jdbc.update(
                "INSERT INTO "
                    + SCHEMA
                    + "agent_workload_version (tenant_id, environment_id, workload_id, id, "
                    + "version_number, digest, object_ref, manifest, created_by, state, revision) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, 'REGISTERED', 1)",
                actor.tenantId(),
                actor.environmentId(),
                workloadId,
                request.id(),
                request.versionNumber(),
                request.digest(),
                request.objectRef(),
                serialize(request.manifest()),
                actor.principalId()),
        1,
        "REGISTERED",
        false,
        Map.of());
  }

  @Transactional
  Receipt activateAgentVersion(
      DelegationPrincipal actor,
      String workloadId,
      ActivateAgentVersion request,
      String idempotencyKey) {
    requireCapability(actor, "catalog.agent.write");
    if (request.expectedRevision() == Long.MAX_VALUE) {
      throw invalid("The expected workload revision cannot be incremented.");
    }
    List<String> digests =
        jdbc.query(
            "SELECT digest FROM "
                + SCHEMA
                + "agent_workload_version WHERE tenant_id = ? AND environment_id = ? "
                + "AND workload_id = ? AND id = ?",
            (result, row) -> result.getString("digest"),
            actor.tenantId(),
            actor.environmentId(),
            workloadId,
            request.versionId());
    if (digests.isEmpty()) {
      throw notFound("The agent workload version is absent or not visible.");
    }
    long nextRevision = request.expectedRevision() + 1;
    Map<String, Object> eventMetadata =
        Map.of(
            "owned_resource_id", workloadId,
            "owned_revision", nextRevision,
            "owned_digest", digests.getFirst(),
            "minimal_outcome_metadata",
                Map.of("version_id", request.versionId(), "state", "ACTIVE"));
    return executeOnce(
        actor,
        "POST /v1/agent-workloads/" + workloadId + ":activate",
        idempotencyKey,
        request,
        workloadId,
        "AGENT_WORKLOAD",
        "uacrf.s02.workload.version.activated.v1",
        () -> {
          activateAgentVersionInTransaction(actor, workloadId, request);
          return 1;
        },
        nextRevision,
        "ACTIVATED",
        true,
        eventMetadata);
  }

  @Transactional(readOnly = true)
  WorkloadVersionReceipt getActiveWorkloadVersion(DelegationPrincipal actor, String versionId) {
    requireCapability(actor, "catalog.agent.read");
    List<Map<String, Object>> versions =
        jdbc.queryForList(
            "SELECT version.workload_id, version.id, version.version_number, version.digest, "
                + "version.object_ref, version.manifest::text AS manifest, profile.service_id "
                + "FROM "
                + SCHEMA
                + "agent_workload_version version JOIN "
                + SCHEMA
                + "agent_workload profile ON profile.tenant_id = version.tenant_id "
                + "AND profile.environment_id = version.environment_id "
                + "AND profile.id = version.workload_id AND profile.active_version_id = version.id "
                + "WHERE version.tenant_id = ? AND version.environment_id = ? AND version.id = ? "
                + "AND version.state = 'ACTIVE'",
            actor.tenantId(),
            actor.environmentId(),
            versionId);
    if (versions.isEmpty()) {
      throw notFound("The active agent workload version is absent or not visible.");
    }
    Map<String, Object> version = versions.getFirst();
    requireServiceScope(actor, (String) version.get("service_id"));
    WorkloadVersionManifest manifest = deserializeWorkloadManifest(version.get("manifest").toString());
    if (!sha256(serialize(manifest)).equals(version.get("digest").toString().trim())) {
      throw new IllegalStateException("The retained workload version manifest digest is invalid.");
    }
    return new WorkloadVersionReceipt(
        version.get("workload_id").toString(),
        version.get("id").toString(),
        ((Number) version.get("version_number")).longValue(),
        version.get("digest").toString().trim(),
        version.get("object_ref").toString(),
        manifest);
  }

  @Transactional(readOnly = true)
  OwnerReceipt issueOwnerReceipt(Jwt jwt, String workloadId, OwnerReceiptRequest request) {
    DelegationPrincipal actor = DelegationPrincipal.from(jwt);
    requireOwnerReceiptDelegation(jwt, actor, workloadId, request.purpose());

    Instant issuedAt = clock.instant();
    Instant deadline = request.operationDeadline();
    if (!deadline.isAfter(issuedAt)
        || deadline.isAfter(issuedAt.plusSeconds(5))
        || jwt.getExpiresAt() == null
        || !jwt.getExpiresAt().isAfter(issuedAt)) {
      throw conflict("OPERATION_DEADLINE_EXPIRED", "The operation deadline is not current.");
    }

    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT profile.revision, profile.active_version_id, parent_service.lifecycle "
                + "AS service_lifecycle, version.state AS version_state, version.digest, "
                + "version.manifest::text AS manifest FROM "
                + SCHEMA
                + "agent_workload profile "
                + "LEFT JOIN "
                + SCHEMA
                + "service parent_service ON parent_service.tenant_id = profile.tenant_id "
                + "AND parent_service.environment_id = profile.environment_id "
                + "AND parent_service.id = profile.service_id "
                + "LEFT JOIN "
                + SCHEMA
                + "agent_workload_version version ON version.tenant_id = profile.tenant_id "
                + "AND version.environment_id = profile.environment_id "
                + "AND version.workload_id = profile.id "
                + "AND version.id = profile.active_version_id "
                + "WHERE profile.tenant_id = ? AND profile.environment_id = ? "
                + "AND profile.id = ?",
            actor.tenantId(),
            actor.environmentId(),
            workloadId);
    if (rows.isEmpty()) {
      throw notFound("The agent workload is absent or not visible.");
    }
    Map<String, Object> row = rows.getFirst();
    long profileRevision = ((Number) row.get("revision")).longValue();
    String activeVersionId = (String) row.get("active_version_id");
    String serviceLifecycle = (String) row.get("service_lifecycle");
    String versionState = (String) row.get("version_state");
    String digest = row.get("digest") == null ? null : row.get("digest").toString().trim();

    if (!"ACTIVE".equals(serviceLifecycle)) {
      throw conflict("OWNER_INACTIVE", "The parent service is not active.");
    }
    if (activeVersionId == null || !"ACTIVE".equals(versionState) || digest == null) {
      throw conflict("WORKLOAD_VERSION_INACTIVE", "The workload has no current active version.");
    }
    if (row.get("manifest") == null
        || !sha256(serialize(deserializeWorkloadManifest(row.get("manifest").toString())))
            .equals(digest)) {
      throw conflict("WORKLOAD_MANIFEST_INVALID", "The active workload manifest digest is invalid.");
    }
    if (!activeVersionId.equals(request.requestedVersion())) {
      throw conflict("STALE_WORKLOAD_VERSION", "The requested workload version is not active.");
    }
    if (request.knownRevision() != null && request.knownRevision() != profileRevision) {
      throw conflict("STALE_WORKLOAD_REVISION", "The workload profile revision has changed.");
    }
    if (profileRevision < 0 || profileRevision > MAX_SAFE_INTEGER) {
      throw conflict("OWNER_REVISION_UNSUPPORTED", "The workload revision cannot be represented safely.");
    }
    requireReceiptIdentifier(actor.tenantId());
    requireReceiptIdentifier(actor.environmentId());
    requireReceiptIdentifier(actor.actorSubject());
    requireReceiptIdentifier(workloadId);
    requireReceiptIdentifier(activeVersionId);

    Instant validUntil =
        min(deadline, min(issuedAt.plusSeconds(5), jwt.getExpiresAt()));
    if (!validUntil.isAfter(issuedAt)) {
      throw conflict("OPERATION_DEADLINE_EXPIRED", "The operation deadline is not current.");
    }
    OwnerReceiptClaims claims =
        new OwnerReceiptClaims(
            "UACRF_OWNER_RECEIPT_V1",
            ownerReceiptSigningKey.keyId(),
            UUID.randomUUID().toString(),
            "S02",
            "S06",
            actor.tenantId(),
            actor.environmentId(),
            actor.actorSubject(),
            "agent_workload",
            workloadId,
            activeVersionId,
            profileRevision,
            digest,
            profileRevision,
            true,
            request.purpose(),
            issuedAt.toString(),
            validUntil.toString());
    String signature =
        OwnerReceiptSignature.sign(
            serialize(ownerReceiptSigningPayload(claims)), ownerReceiptSigningKey.privateKey());
    return new OwnerReceipt(
        claims.protocol(),
        claims.keyId(),
        claims.receiptId(),
        claims.issuerService(),
        claims.audienceService(),
        claims.tenantId(),
        claims.environmentId(),
        claims.actorId(),
        claims.resourceType(),
        claims.resourceId(),
        claims.resourceVersion(),
        claims.resourceRevision(),
        claims.contentDigest(),
        claims.authorityEpoch(),
        claims.eligible(),
        claims.purpose(),
        claims.issuedAt(),
        claims.validUntil(),
        signature);
  }

  static Map<String, Object> ownerReceiptSigningPayload(OwnerReceiptClaims claims) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("protocol", claims.protocol());
    payload.put("key_id", claims.keyId());
    payload.put("receipt_id", claims.receiptId());
    payload.put("issuer_service", claims.issuerService());
    payload.put("audience_service", claims.audienceService());
    payload.put("tenant_id", claims.tenantId());
    payload.put("environment_id", claims.environmentId());
    payload.put("actor_id", claims.actorId());
    payload.put("resource_type", claims.resourceType());
    payload.put("resource_id", claims.resourceId());
    payload.put("resource_version", claims.resourceVersion());
    payload.put("resource_revision", claims.resourceRevision());
    payload.put("content_digest", claims.contentDigest());
    payload.put("authority_epoch", claims.authorityEpoch());
    payload.put("eligible", claims.eligible());
    payload.put("purpose", claims.purpose());
    payload.put("issued_at", claims.issuedAt());
    payload.put("valid_until", claims.validUntil());
    return payload;
  }

  @Transactional
  Receipt registerJourney(
      DelegationPrincipal actor, RegisterJourney request, String idempotencyKey) {
    requireCapability(actor, "catalog.journey.write");
    requireGlobalScope(actor);
    requireOwner(actor, request.ownerId());
    return executeOnce(
        actor,
        "POST /v1/customer-journeys",
        idempotencyKey,
        request,
        request.id(),
        "CUSTOMER_JOURNEY",
        "catalog.customer-journey.registered",
        () ->
            jdbc.update(
                "INSERT INTO "
                    + SCHEMA
                    + "customer_journey (tenant_id, environment_id, id, name, owner_id, purpose, "
                    + "lifecycle, revision) VALUES (?, ?, ?, ?, ?, ?, ?, 1)",
                actor.tenantId(),
                actor.environmentId(),
                request.id(),
                request.name().trim(),
                request.ownerId(),
                request.purpose().trim(),
                request.lifecycle().name()));
  }

  @Transactional
  Receipt registerStep(
      DelegationPrincipal actor,
      String journeyId,
      RegisterStep request,
      String idempotencyKey) {
    requireCapability(actor, "catalog.journey.write");
    requireGlobalScope(actor);
    requireJourney(actor, journeyId);
    return executeOnce(
        actor,
        "POST /v1/customer-journeys/" + journeyId + "/steps",
        idempotencyKey,
        request,
        request.id(),
        "JOURNEY_STEP",
        "catalog.journey-step.registered",
        () ->
            jdbc.update(
                "INSERT INTO "
                    + SCHEMA
                    + "journey_step (tenant_id, environment_id, journey_id, id, position, name, "
                    + "intended_outcome, revision) VALUES (?, ?, ?, ?, ?, ?, ?, 1)",
                actor.tenantId(),
                actor.environmentId(),
                journeyId,
                request.id(),
                request.position(),
                request.name().trim(),
                request.intendedOutcome().trim()));
  }

  @Transactional
  Receipt linkCapability(
      DelegationPrincipal actor,
      String journeyId,
      LinkCapability request,
      String idempotencyKey) {
    requireCapability(actor, "catalog.journey.write");
    requireGlobalScope(actor);
    requireJourney(actor, journeyId);
    String table =
        request.capabilityKind() == CapabilityKind.SERVICE ? "service" : "agent_workload";
    if (!exists(
        "SELECT EXISTS (SELECT 1 FROM "
            + SCHEMA
            + table
            + " WHERE tenant_id = ? AND environment_id = ? AND id = ?)",
        actor.tenantId(),
        actor.environmentId(),
        request.capabilityId())) {
      throw notFound("The linked capability is absent or not visible.");
    }
    if (request.stepId() != null
        && !exists(
            "SELECT EXISTS (SELECT 1 FROM "
                + SCHEMA
                + "journey_step WHERE tenant_id = ? AND environment_id = ? "
                + "AND journey_id = ? AND id = ?)",
            actor.tenantId(),
            actor.environmentId(),
            journeyId,
            request.stepId())) {
      throw notFound("The journey step is absent or not visible.");
    }
    return executeOnce(
        actor,
        "POST /v1/customer-journeys/" + journeyId + "/capabilities",
        idempotencyKey,
        request,
        request.id(),
        "JOURNEY_CAPABILITY",
        "catalog.journey-capability.linked",
        () ->
            jdbc.update(
                "INSERT INTO "
                    + SCHEMA
                    + "journey_capability (tenant_id, environment_id, journey_id, id, "
                    + "capability_kind, capability_id, step_id, criticality, revision) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1)",
                actor.tenantId(),
                actor.environmentId(),
                journeyId,
                request.id(),
                request.capabilityKind().name(),
                request.capabilityId(),
                request.stepId(),
                request.criticality().name()));
  }

  @Transactional(readOnly = true)
  Page<ServiceRecord> listServices(
      DelegationPrincipal actor, int pageSize, String cursor, boolean includeRetired) {
    requireCapability(actor, "catalog.service.read");
    String after = decodeCursor(cursor);
    String lifecycleClause = includeRetired ? "" : "AND lifecycle <> 'RETIRED' ";
    List<ServiceRecord> rows =
        jdbc.query(
            "SELECT id, name, owner_id, lifecycle, criticality, revision FROM "
                + SCHEMA
                + "service WHERE tenant_id = ? AND environment_id = ? "
                + "AND (? = '*' OR id = ?) "
                + lifecycleClause
                + "AND id > ? ORDER BY id LIMIT ?",
            serviceRowMapper(),
            actor.tenantId(),
            actor.environmentId(),
            actor.serviceScope(),
            actor.serviceScope(),
            after,
            pageSize + 1);
    return page(rows, pageSize, ServiceRecord::id);
  }

  @Transactional(readOnly = true)
  Page<Agent> listAgents(DelegationPrincipal actor, int pageSize, String cursor) {
    requireCapability(actor, "catalog.agent.read");
    List<Agent> rows =
        jdbc.query(
            "SELECT id, service_id, name, kind, owner_id, runtime_principal_id, purpose, revision "
                + "FROM "
                + SCHEMA
                + "agent_workload WHERE tenant_id = ? AND environment_id = ? "
                + "AND (? = '*' OR service_id = ?) AND id > ? ORDER BY id LIMIT ?",
            agentRowMapper(),
            actor.tenantId(),
            actor.environmentId(),
            actor.serviceScope(),
            actor.serviceScope(),
            decodeCursor(cursor),
            pageSize + 1);
    return page(rows, pageSize, Agent::id);
  }

  @Transactional(readOnly = true)
  Page<Journey> listJourneys(
      DelegationPrincipal actor, int pageSize, String cursor, boolean includeRetired) {
    requireCapability(actor, "catalog.journey.read");
    requireGlobalScope(actor);
    String lifecycleClause = includeRetired ? "" : "AND lifecycle <> 'RETIRED' ";
    List<Journey> rows =
        jdbc.query(
            "SELECT id, name, owner_id, purpose, lifecycle, revision FROM "
                + SCHEMA
                + "customer_journey WHERE tenant_id = ? AND environment_id = ? "
                + lifecycleClause
                + "AND id > ? ORDER BY id LIMIT ?",
            journeyRowMapper(List.of(), List.of()),
            actor.tenantId(),
            actor.environmentId(),
            decodeCursor(cursor),
            pageSize + 1);
    return page(rows, pageSize, Journey::id);
  }

  @Transactional(readOnly = true)
  Journey getJourney(DelegationPrincipal actor, String journeyId) {
    requireCapability(actor, "catalog.journey.read");
    requireGlobalScope(actor);
    List<Journey> rows =
        jdbc.query(
            "SELECT id, name, owner_id, purpose, lifecycle, revision FROM "
                + SCHEMA
                + "customer_journey WHERE tenant_id = ? AND environment_id = ? AND id = ?",
            journeyRowMapper(List.of(), List.of()),
            actor.tenantId(),
            actor.environmentId(),
            journeyId);
    if (rows.isEmpty()) {
      throw notFound("The journey is absent or not visible.");
    }
    List<Step> steps =
        jdbc.query(
            "SELECT id, position, name, intended_outcome, revision FROM "
                + SCHEMA
                + "journey_step WHERE tenant_id = ? AND environment_id = ? AND journey_id = ? "
                + "ORDER BY position, id",
            stepRowMapper(),
            actor.tenantId(),
            actor.environmentId(),
            journeyId);
    List<Capability> capabilities =
        jdbc.query(
            "SELECT id, capability_kind, capability_id, step_id, criticality, revision FROM "
                + SCHEMA
                + "journey_capability WHERE tenant_id = ? AND environment_id = ? AND journey_id = ? "
                + "ORDER BY id",
            capabilityRowMapper(),
            actor.tenantId(),
            actor.environmentId(),
            journeyId);
    Journey journey = rows.getFirst();
    return new Journey(
        journey.id(),
        journey.name(),
        journey.ownerId(),
        journey.purpose(),
        journey.lifecycle(),
        journey.revision(),
        steps,
        capabilities);
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
    return executeOnce(
        actor, operation, key, request, aggregateId, aggregateType, eventType, write,
        1, "CREATED", true, Map.of());
  }

  private Receipt executeOnce(
      DelegationPrincipal actor,
      String operation,
      String key,
      Object request,
      String aggregateId,
      String aggregateType,
      String eventType,
      SqlWrite write,
      long revision,
      String result,
      boolean publishEvent,
      Map<String, Object> eventMetadata) {
    validateIdempotencyKey(key);
    String requestJson = serialize(request);
    String digest = sha256(requestJson);
    int inserted =
        jdbc.update(
            "INSERT INTO "
                + SCHEMA
                + "idempotency_record (tenant_id, environment_id, actor_id, operation, "
                + "idempotency_key, payload_digest) VALUES (?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT DO NOTHING",
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
      return deserialize(response.toString());
    }
    try {
      write.apply();
    } catch (DuplicateKeyException error) {
      throw conflict("CATALOG_ID_CONFLICT", "A catalog record with this ID already exists.");
    }
    Receipt receipt = new Receipt(aggregateId, revision, aggregateType, result, "NONE");
    String responseJson = serialize(receipt);
    jdbc.update(
        "UPDATE "
            + SCHEMA
            + "idempotency_record SET response = ?::jsonb WHERE tenant_id = ? "
            + "AND environment_id = ? AND actor_id = ? AND operation = ? AND idempotency_key = ?",
        responseJson,
        actor.tenantId(),
        actor.environmentId(),
        actor.principalId(),
        operation,
        key);
    if (publishEvent) {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("actor_id", actor.principalId());
      payload.put("event_type", eventType);
      payload.put("aggregate_id", aggregateId);
      payload.put("revision", revision);
      payload.put("record", request);
      payload.putAll(eventMetadata);
      jdbc.update(
          "INSERT INTO "
              + SCHEMA
              + "outbox_event (event_id, tenant_id, environment_id, aggregate_type, aggregate_id, "
              + "aggregate_sequence, event_type, payload) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
          UUID.randomUUID(),
          actor.tenantId(),
          actor.environmentId(),
          aggregateType,
          aggregateId,
          revision,
          eventType,
          serialize(payload));
    }
    return receipt;
  }

  private void activateAgentVersionInTransaction(
      DelegationPrincipal actor, String workloadId, ActivateAgentVersion request) {
    List<Map<String, Object>> profiles =
        jdbc.queryForList(
            "SELECT service_id, revision, active_version_id FROM "
                + SCHEMA
                + "agent_workload WHERE tenant_id = ? AND environment_id = ? AND id = ? FOR UPDATE",
            actor.tenantId(),
            actor.environmentId(),
            workloadId);
    if (profiles.isEmpty()) {
      throw notFound("The agent workload is absent or not visible.");
    }
    Map<String, Object> profile = profiles.getFirst();
    requireServiceScope(actor, (String) profile.get("service_id"));
    if (((Number) profile.get("revision")).longValue() != request.expectedRevision()) {
      throw conflict("REVISION_MISMATCH", "The agent workload revision has changed.");
    }

    List<Map<String, Object>> versions =
        jdbc.queryForList(
            "SELECT state, created_by FROM "
                + SCHEMA
                + "agent_workload_version WHERE tenant_id = ? AND environment_id = ? "
                + "AND workload_id = ? AND id = ? FOR UPDATE",
            actor.tenantId(),
            actor.environmentId(),
            workloadId,
            request.versionId());
    if (versions.isEmpty()) {
      throw notFound("The agent workload version is absent or not visible.");
    }
    Map<String, Object> version = versions.getFirst();
    if ("REGISTERED".equals(version.get("state"))
        && actor.principalId().equals(version.get("created_by"))) {
      throw conflict(
          "VERSION_AUTHOR_NOT_INDEPENDENT",
          "A workload version must be activated by an actor other than its author.");
    }
    if (!"REGISTERED".equals(version.get("state"))) {
      throw conflict("VERSION_NOT_REGISTERED", "Only a registered workload version can be activated.");
    }

    String activeVersionId = (String) profile.get("active_version_id");
    if (activeVersionId != null
        && jdbc.update(
                "UPDATE "
                    + SCHEMA
                    + "agent_workload_version SET state = 'SUPERSEDED', revision = revision + 1 "
                    + "WHERE tenant_id = ? AND environment_id = ? AND workload_id = ? "
                    + "AND id = ? AND state = 'ACTIVE'",
                actor.tenantId(),
                actor.environmentId(),
                workloadId,
                activeVersionId)
            != 1) {
      throw conflict("ACTIVE_VERSION_CHANGED", "The active workload version changed.");
    }
    if (jdbc.update(
            "UPDATE "
                + SCHEMA
                + "agent_workload_version SET state = 'ACTIVE', revision = revision + 1, "
                + "activated_by = ?, activated_at = now() WHERE tenant_id = ? "
                + "AND environment_id = ? AND workload_id = ? AND id = ? AND state = 'REGISTERED'",
            actor.principalId(),
            actor.tenantId(),
            actor.environmentId(),
            workloadId,
            request.versionId())
        != 1) {
      throw conflict("VERSION_NOT_REGISTERED", "Only a registered workload version can be activated.");
    }
    if (jdbc.update(
            "UPDATE "
                + SCHEMA
                + "agent_workload SET active_version_id = ?, revision = revision + 1 "
                + "WHERE tenant_id = ? AND environment_id = ? AND id = ? AND revision = ?",
            request.versionId(),
            actor.tenantId(),
            actor.environmentId(),
            workloadId,
            request.expectedRevision())
        != 1) {
      throw conflict("REVISION_MISMATCH", "The agent workload revision has changed.");
    }
  }

  private void requireCapability(DelegationPrincipal actor, String capability) {
    if (!actor.capabilities().contains(capability)) {
      throw denied("The current principal cannot perform this catalog operation.");
    }
  }

  private void requireOwnerReceiptDelegation(
      Jwt jwt, DelegationPrincipal actor, String workloadId, String purpose) {
    boolean exactAudience =
        jwt.getAudience() != null
            && jwt.getAudience().size() == 1
            && jwt.getAudience().contains("S02");
    boolean exactCapability =
        actor.capabilities().size() == 1
            && actor.capabilities().contains("catalog.agent.owner-receipt");
    if (!exactAudience
        || !exactCapability
        || !workloadId.equals(actor.serviceScope())
        || !"agent_workload".equals(jwt.getClaims().get("resource_type"))
        || !workloadId.equals(jwt.getClaims().get("resource_id"))
        || !purpose.equals(jwt.getClaims().get("purpose"))
        || !OWNER_RECEIPT_PURPOSES.contains(purpose)
        || actor.tenantId() == null
        || actor.environmentId() == null
        || actor.actorSubject() == null
        || actor.actorSubject().isBlank()) {
      throw denied("A resource- and purpose-scoped S01 owner-receipt delegation is required.");
    }
  }

  private static void requireReceiptIdentifier(String value) {
    if (value == null || !value.matches(RECEIPT_IDENTIFIER_PATTERN)) {
      throw conflict("OWNER_RECEIPT_SCOPE_INVALID", "The owner receipt scope is not schema-valid.");
    }
  }

  private static Instant min(Instant left, Instant right) {
    return left.isBefore(right) ? left : right;
  }

  private void requireGlobalScope(DelegationPrincipal actor) {
    if (!"*".equals(actor.serviceScope())) {
      throw denied("This catalog operation requires a global service scope.");
    }
  }

  private void requireServiceScope(DelegationPrincipal actor, String serviceId) {
    if (!"*".equals(actor.serviceScope()) && !actor.serviceScope().equals(serviceId)) {
      throw denied("The delegated service scope does not match the catalog target.");
    }
  }

  private void requireOwner(DelegationPrincipal actor, String ownerId) {
    if (!actor.eligibleOwnerIds().contains(ownerId)) {
      throw denied("The selected owner is not currently eligible in this environment.");
    }
  }

  private void requireJourney(DelegationPrincipal actor, String journeyId) {
    if (!exists(
        "SELECT EXISTS (SELECT 1 FROM "
            + SCHEMA
            + "customer_journey WHERE tenant_id = ? AND environment_id = ? AND id = ?)",
        actor.tenantId(),
        actor.environmentId(),
        journeyId)) {
      throw notFound("The journey is absent or not visible.");
    }
  }

  private boolean exists(String sql, Object... args) {
    Boolean found = jdbc.queryForObject(sql, Boolean.class, args);
    return Boolean.TRUE.equals(found);
  }

  private <T> Page<T> page(List<T> rows, int pageSize, IdExtractor<T> id) {
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
      String value =
          new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
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
      throw new IllegalStateException("Could not serialize the catalog record.", error);
    }
  }

  private WorkloadVersionManifest deserializeWorkloadManifest(String value) {
    try {
      return objectMapper.readValue(value, WorkloadVersionManifest.class);
    } catch (JacksonException error) {
      throw new IllegalStateException("The retained workload version manifest is invalid.", error);
    }
  }

  private Receipt deserialize(String value) {
    try {
      return objectMapper.readValue(value, Receipt.class);
    } catch (JacksonException error) {
      throw new IllegalStateException("Could not read the retained idempotency result.", error);
    }
  }

  private static String sha256(String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is required by the runtime.", error);
    }
  }

  private static RowMapper<ServiceRecord> serviceRowMapper() {
    return (rs, row) ->
        new ServiceRecord(
            rs.getString("id"),
            rs.getString("name"),
            rs.getString("owner_id"),
            rs.getString("lifecycle"),
            rs.getInt("criticality"),
            rs.getLong("revision"));
  }

  private static RowMapper<Agent> agentRowMapper() {
    return (rs, row) ->
        new Agent(
            rs.getString("id"),
            rs.getString("service_id"),
            rs.getString("name"),
            rs.getString("kind"),
            rs.getString("owner_id"),
            rs.getString("runtime_principal_id"),
            rs.getString("purpose"),
            rs.getLong("revision"));
  }

  private static RowMapper<Journey> journeyRowMapper(
      List<Step> steps, List<Capability> capabilities) {
    return (rs, row) ->
        new Journey(
            rs.getString("id"),
            rs.getString("name"),
            rs.getString("owner_id"),
            rs.getString("purpose"),
            rs.getString("lifecycle"),
            rs.getLong("revision"),
            steps,
            capabilities);
  }

  private static RowMapper<Step> stepRowMapper() {
    return (rs, row) ->
        new Step(
            rs.getString("id"),
            rs.getInt("position"),
            rs.getString("name"),
            rs.getString("intended_outcome"),
            rs.getLong("revision"));
  }

  private static RowMapper<Capability> capabilityRowMapper() {
    return (rs, row) ->
        new Capability(
            rs.getString("id"),
            rs.getString("capability_kind"),
            rs.getString("capability_id"),
            rs.getString("step_id"),
            rs.getString("criticality"),
            rs.getLong("revision"));
  }

  private static CatalogException denied(String message) {
    return new CatalogException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", message);
  }

  private static CatalogException notFound(String message) {
    return new CatalogException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
  }

  private static CatalogException invalid(String message) {
    return new CatalogException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
  }

  private static CatalogException conflict(String code, String message) {
    return new CatalogException(HttpStatus.CONFLICT, code, message);
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
