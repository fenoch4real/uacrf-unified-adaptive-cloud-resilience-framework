package org.uacrf.catalog;

import static org.uacrf.catalog.CatalogRequests.*;
import static org.uacrf.catalog.CatalogResponses.*;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
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
  private static final Set<String> SERVICE_REVISION_RECEIPT_PURPOSES =
      Set.of(
          "topology.graph.subgraph-selection",
          "topology.graph.difference-classification");
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
  OwnershipTransferReceipt transferOwnership(
      DelegationPrincipal actor, TransferOwnership request, String key) {
    if (!Set.of("JOURNEY", "SERVICE").contains(request.objectKind())) {
      throw new CatalogException(
          HttpStatus.UNPROCESSABLE_ENTITY, "UNSUPPORTED_OBJECT_KIND",
          "Ownership transfer supports JOURNEY and SERVICE only.");
    }
    if (!request.expectedAnchorRevision().isNull()) {
      throw invalid("expected_anchor_revision must be null for JOURNEY and SERVICE.");
    }
    requireCapability(actor, "ownership.transfer");
    boolean service = "SERVICE".equals(request.objectKind());
    requireCapability(actor, service ? "catalog.service.write" : "catalog.journey.write");
    if (service) {
      requireServiceScope(actor, request.objectId());
    } else {
      requireGlobalScope(actor);
    }
    requireOwner(actor, request.ownerId());
    validateIdempotencyKey(key);

    String operation = "POST /v1/ownership:transfer";
    String digest = sha256(serialize(request));
    int inserted = jdbc.update(
        "INSERT INTO " + SCHEMA
            + "idempotency_record (tenant_id, environment_id, actor_id, operation, "
            + "idempotency_key, payload_digest) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
        actor.tenantId(), actor.environmentId(), actor.principalId(), operation, key, digest);
    if (inserted == 0) {
      List<Map<String, Object>> records = jdbc.queryForList(
          "SELECT payload_digest, response::text FROM " + SCHEMA
              + "idempotency_record WHERE tenant_id = ? AND environment_id = ? AND actor_id = ? "
              + "AND operation = ? AND idempotency_key = ? FOR UPDATE",
          actor.tenantId(), actor.environmentId(), actor.principalId(), operation, key);
      if (records.isEmpty() || !digest.equals(records.getFirst().get("payload_digest"))) {
        throw conflict("IDEMPOTENCY_CONFLICT", "The idempotency key was already used for different content.");
      }
      Object response = records.getFirst().get("response");
      if (response == null) {
        throw conflict("COMMAND_IN_PROGRESS", "The original request has not completed.");
      }
      OwnershipTransferReceipt retained;
      try {
        retained = objectMapper.readValue(response.toString(), OwnershipTransferReceipt.class);
      } catch (JacksonException error) {
        throw new IllegalStateException("Could not read the retained idempotency result.", error);
      }
      return new OwnershipTransferReceipt(retained.id(), retained.revision(), "REPLAYED", "NONE");
    }

    String table = service ? "service" : "customer_journey";
    List<Map<String, Object>> rows = jdbc.queryForList(
        "SELECT * FROM " + SCHEMA + table
            + " WHERE tenant_id = ? AND environment_id = ? AND id = ? FOR UPDATE",
        actor.tenantId(), actor.environmentId(), request.objectId());
    if (rows.isEmpty()) {
      throw notFound("The catalog object is absent or not visible.");
    }
    Map<String, Object> row = rows.getFirst();
    long oldRevision = ((Number) row.get("revision")).longValue();
    if (oldRevision != request.expectedRevision()) {
      throw conflict("REVISION_MISMATCH", "The catalog object revision has changed.");
    }
    if (request.ownerId().equals(row.get("owner_id"))) {
      throw conflict("OWNER_ALREADY_ASSIGNED", "The requested owner is already assigned.");
    }
    if (oldRevision >= MAX_SAFE_INTEGER) {
      throw conflict("REVISION_LIMIT", "The catalog object revision limit was reached.");
    }
    List<String> workloadIds = service
        ? jdbc.queryForList(
            "SELECT id FROM " + SCHEMA
                + "agent_workload WHERE tenant_id = ? AND environment_id = ? AND service_id = ? ORDER BY id",
            String.class, actor.tenantId(), actor.environmentId(), request.objectId())
        : List.of();
    if (!workloadIds.isEmpty()) {
      throw conflict("SERVICE_IS_AGENT_ANCHOR", "The service is an internal anchor for an agent workload.");
    }
    long revision = oldRevision + 1;
    jdbc.update(
        "UPDATE " + SCHEMA + table
            + " SET owner_id = ?, revision = ?, updated_at = now() "
            + "WHERE tenant_id = ? AND environment_id = ? AND id = ?",
        request.ownerId(), revision, actor.tenantId(), actor.environmentId(), request.objectId());
    if (service) {
      appendServiceRevision(actor, new ServiceRevisionSnapshot(
          request.objectId(), row.get("name").toString(), request.ownerId(),
          ((Number) row.get("criticality")).intValue(), row.get("lifecycle").toString(),
          revision, workloadIds));
    }
    jdbc.update(
        "INSERT INTO " + SCHEMA
            + "owner_transfer_audit (audit_id, tenant_id, environment_id, object_kind, object_id, "
            + "previous_owner_id, owner_id, previous_revision, revision, actor_id, reason) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        UUID.randomUUID(), actor.tenantId(), actor.environmentId(), request.objectKind(),
        request.objectId(), row.get("owner_id"), request.ownerId(), oldRevision, revision,
        actor.principalId(), request.reason().trim());
    OwnershipTransferReceipt receipt =
        new OwnershipTransferReceipt(request.objectId(), revision, "UPDATED", "NONE");
    jdbc.update(
        "UPDATE " + SCHEMA
            + "idempotency_record SET response = ?::jsonb WHERE tenant_id = ? AND environment_id = ? "
            + "AND actor_id = ? AND operation = ? AND idempotency_key = ?",
        serialize(receipt), actor.tenantId(), actor.environmentId(), actor.principalId(), operation, key);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("actor_id", actor.principalId());
    payload.put("event_type", "catalog.ownership.transferred");
    payload.put("aggregate_id", request.objectId());
    payload.put("revision", revision);
    payload.put("previous_owner_id", row.get("owner_id"));
    payload.put("record", request);
    jdbc.update(
        "INSERT INTO " + SCHEMA
            + "outbox_event (event_id, tenant_id, environment_id, aggregate_type, aggregate_id, "
            + "aggregate_sequence, event_type, payload) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
        UUID.randomUUID(), actor.tenantId(), actor.environmentId(),
        service ? "SERVICE" : "CUSTOMER_JOURNEY", request.objectId(), revision,
        "catalog.ownership.transferred", serialize(payload));
    return receipt;
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
        () -> {
          String name = request.name().trim();
          jdbc.update(
              "INSERT INTO "
                  + SCHEMA
                  + "service (tenant_id, environment_id, id, name, owner_id, reason, "
                  + "criticality, lifecycle, revision) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1)",
              actor.tenantId(),
              actor.environmentId(),
              request.id(),
              name,
              request.ownerId(),
              request.reason().trim(),
              request.criticality(),
              request.lifecycle());
          ServiceRevisionSnapshot snapshot =
              new ServiceRevisionSnapshot(
                  request.id(),
                  name,
                  request.ownerId(),
                  request.criticality(),
                  request.lifecycle(),
                  1,
                  List.of());
          appendServiceRevision(actor, snapshot);
          return 1;
        });
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
        () -> {
          List<Map<String, Object>> services = List.of();
          if (request.serviceId() != null) {
            services =
                jdbc.queryForList(
                    "SELECT name, owner_id, criticality, lifecycle, revision FROM "
                        + SCHEMA
                        + "service WHERE tenant_id = ? AND environment_id = ? AND id = ? FOR UPDATE",
                    actor.tenantId(), actor.environmentId(), request.serviceId());
            if (services.isEmpty()) {
              throw notFound("The service is absent or not visible.");
            }
          }
          long oldRevision = 0;
          if (!services.isEmpty()) {
            Map<String, Object> service = services.getFirst();
            oldRevision = ((Number) service.get("revision")).longValue();
            if (oldRevision >= MAX_SAFE_INTEGER) {
              throw conflict("SERVICE_REVISION_LIMIT", "The service revision limit was reached.");
            }
          }
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
                request.purpose().trim());
          if (!services.isEmpty()) {
            Map<String, Object> service = services.getFirst();
            long revision = oldRevision + 1;
            if (jdbc.update(
                "UPDATE " + SCHEMA
                    + "service SET revision = ?, updated_at = now() "
                    + "WHERE tenant_id = ? AND environment_id = ? AND id = ? AND revision = ?",
                revision, actor.tenantId(), actor.environmentId(), request.serviceId(), oldRevision) != 1) {
              throw conflict("SERVICE_REVISION_CHANGED", "The service revision changed during registration.");
            }
            List<String> workloadIds =
                new ArrayList<>(
                    jdbc.queryForList(
                        "SELECT id FROM " + SCHEMA
                            + "agent_workload WHERE tenant_id = ? AND environment_id = ? AND service_id = ?",
                        String.class, actor.tenantId(), actor.environmentId(), request.serviceId()));
            workloadIds.sort(String::compareTo);
            appendServiceRevision(
                actor,
                new ServiceRevisionSnapshot(
                    request.serviceId(),
                    service.get("name").toString(),
                    service.get("owner_id").toString(),
                    ((Number) service.get("criticality")).intValue(),
                    service.get("lifecycle").toString(),
                    revision,
                    workloadIds));
          }
          return 1;
        });
  }

  private void appendServiceRevision(DelegationPrincipal actor, ServiceRevisionSnapshot snapshot) {
    String snapshotJson = serialize(snapshot);
    jdbc.update(
        "INSERT INTO " + SCHEMA
            + "service_revision (tenant_id, environment_id, service_id, "
            + "resource_version, content_digest, snapshot, created_by) "
            + "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)",
        actor.tenantId(), actor.environmentId(), snapshot.serviceId(), snapshot.revision(),
        sha256(OwnerReceiptSignature.canonicalize(snapshotJson)), snapshotJson, actor.principalId());
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

  @Transactional(readOnly = true)
  OwnerReceipt issueServiceOwnerReceipt(
      Jwt jwt, String serviceId, OwnerReceiptRequest request) {
    DelegationPrincipal actor = DelegationPrincipal.from(jwt);
    if (serviceId == null || !serviceId.matches(RECEIPT_IDENTIFIER_PATTERN)) {
      throw invalid("The service id is malformed.");
    }
    long resourceVersion = parseServiceRevisionVersion(request.requestedVersion());
    requireServiceRevisionReceiptDelegation(jwt, actor, serviceId, request.purpose());

    Instant issuedAt = clock.instant();
    Instant deadline = request.operationDeadline();
    if (!deadline.isAfter(issuedAt)
        || deadline.isAfter(issuedAt.plusSeconds(5))
        || jwt.getExpiresAt() == null
        || !jwt.getExpiresAt().isAfter(issuedAt)) {
      throw conflict("OPERATION_DEADLINE_EXPIRED", "The operation deadline is not current.");
    }

    ServiceRevisionRead retained = loadServiceRevision(actor, serviceId, resourceVersion);
    JsonNode snapshot = retained.snapshot();
    String storedDigest = retained.contentDigest();
    if (request.knownRevision() != null
        && request.knownRevision().longValue() != snapshot.get("revision").longValue()) {
      throw conflict(
          "STALE_SERVICE_REVISION",
          "The known service revision does not match the requested version.");
    }
    if (!"ACTIVE".equals(snapshot.get("lifecycle").asText())) {
      throw conflict("OWNER_INACTIVE", "The service revision is not active.");
    }
    requireReceiptIdentifier(actor.tenantId());
    requireReceiptIdentifier(actor.environmentId());
    requireReceiptIdentifier(actor.actorSubject());
    requireReceiptIdentifier(serviceId);

    Instant validUntil =
        min(deadline, min(issuedAt.plusSeconds(5), jwt.getExpiresAt()));
    if (!validUntil.isAfter(issuedAt)) {
      throw conflict("OPERATION_DEADLINE_EXPIRED", "The operation deadline is not current.");
    }
    String resourceVersionText = Long.toString(resourceVersion);
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
            "service",
            serviceId,
            resourceVersionText,
            resourceVersion,
            storedDigest,
            resourceVersion,
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

  @Transactional(readOnly = true)
  ServiceRevisionRead getServiceRevision(Jwt jwt, String serviceId, String version) {
    DelegationPrincipal actor = DelegationPrincipal.from(jwt);
    if (serviceId == null || !serviceId.matches(RECEIPT_IDENTIFIER_PATTERN)) {
      throw invalid("The service id is malformed.");
    }
    long resourceVersion = parseServiceRevisionVersion(version);
    requireServiceRevisionReceiptDelegation(jwt, actor, serviceId, jwt.getClaimAsString("purpose"));
    if (jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(clock.instant())) {
      throw denied("The catalog-read delegation has expired.");
    }
    return loadServiceRevision(actor, serviceId, resourceVersion);
  }

  private ServiceRevisionRead loadServiceRevision(
      DelegationPrincipal actor, String serviceId, long resourceVersion) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT content_digest, snapshot::text AS snapshot FROM "
                + SCHEMA
                + "service_revision WHERE tenant_id = ? AND environment_id = ? "
                + "AND service_id = ? AND resource_version = ?",
            actor.tenantId(),
            actor.environmentId(),
            serviceId,
            resourceVersion);
    if (rows.isEmpty()) {
      throw notFound("The immutable service catalog revision is absent or not visible.");
    }
    Map<String, Object> row = rows.getFirst();
    String snapshotJson = row.get("snapshot").toString();
    JsonNode snapshot;
    try {
      snapshot = objectMapper.readTree(snapshotJson);
    } catch (JacksonException error) {
      throw new IllegalStateException("The retained service catalog revision is invalid.", error);
    }
    String canonicalSnapshot = OwnerReceiptSignature.canonicalize(snapshotJson);
    String storedDigest = row.get("content_digest").toString().trim();
    String computedDigest = sha256(canonicalSnapshot);
    if (!computedDigest.equals(storedDigest)
        || !validServiceSnapshot(snapshot, serviceId, resourceVersion)) {
      throw conflict("SERVICE_REVISION_INVALID", "The retained service revision is invalid.");
    }
    return new ServiceRevisionRead(resourceVersion, storedDigest, snapshot);
  }

  private static boolean validServiceSnapshot(
      JsonNode snapshot, String serviceId, long resourceVersion) {
    if (snapshot == null || !snapshot.isObject()) {
      return false;
    }
    if (snapshot == null || !snapshot.isObject()
        || !snapshot.hasNonNull("service_id") || !snapshot.get("service_id").isTextual()
        || !serviceId.equals(snapshot.get("service_id").asText())
        || !snapshot.hasNonNull("revision") || !snapshot.get("revision").isIntegralNumber()
        || snapshot.get("revision").longValue() != resourceVersion
        || !snapshot.hasNonNull("name") || !snapshot.get("name").isTextual()
        || !snapshot.hasNonNull("owner_id") || !snapshot.get("owner_id").isTextual()
        || !snapshot.hasNonNull("criticality") || !snapshot.get("criticality").isIntegralNumber()
        || snapshot.get("criticality").intValue() < 1 || snapshot.get("criticality").intValue() > 5
        || !snapshot.hasNonNull("lifecycle") || !snapshot.get("lifecycle").isTextual()) {
      return false;
    }
    if (!Set.of("DRAFT", "ACTIVE", "SUSPENDED", "RETIRED")
        .contains(snapshot.get("lifecycle").asText())) {
      return false;
    }
    JsonNode memberships = snapshot.get("owned_agent_workload_ids");
    if (memberships == null) {
      return true;
    }
    if (!memberships.isArray()) {
      return false;
    }
    String previous = null;
    for (JsonNode member : memberships) {
      if (!member.isTextual() || !member.asText().matches(RECEIPT_IDENTIFIER_PATTERN)
          || previous != null && previous.compareTo(member.asText()) >= 0) {
        return false;
      }
      previous = member.asText();
    }
    return true;
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

  private void requireServiceRevisionReceiptDelegation(
      Jwt jwt, DelegationPrincipal actor, String serviceId, String purpose) {
    boolean exactAudience =
        jwt.getAudience() != null
            && jwt.getAudience().size() == 1
            && jwt.getAudience().contains("S02");
    boolean exactCapability =
        actor.capabilities().size() == 1
            && actor.capabilities().contains("catalog.service.read");
    if (!exactAudience
        || !exactCapability
        || !serviceId.equals(actor.serviceScope())
        || !"service".equals(jwt.getClaims().get("resource_type"))
        || !serviceId.equals(jwt.getClaims().get("resource_id"))
        || purpose == null
        || !purpose.equals(jwt.getClaims().get("purpose"))
        || !SERVICE_REVISION_RECEIPT_PURPOSES.contains(purpose)
        || actor.tenantId() == null
        || actor.environmentId() == null
        || actor.actorSubject() == null
        || actor.actorSubject().isBlank()) {
      throw denied("A resource- and purpose-scoped S01 catalog-read delegation is required.");
    }
  }

  private static long parseServiceRevisionVersion(String version) {
    if (version == null || !version.matches("^[1-9][0-9]{0,15}$")) {
      throw invalid("requested_version must be a positive immutable service revision.");
    }
    try {
      long parsed = Long.parseLong(version);
      if (parsed > MAX_SAFE_INTEGER) {
        throw invalid("requested_version is outside the supported range.");
      }
      return parsed;
    } catch (NumberFormatException error) {
      throw invalid("requested_version is outside the supported range.");
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
