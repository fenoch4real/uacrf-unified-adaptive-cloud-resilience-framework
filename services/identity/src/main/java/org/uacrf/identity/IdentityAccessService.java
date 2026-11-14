package org.uacrf.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.oauth2.jwt.Jwt;
import org.uacrf.security.DelegationPrincipal;
import org.uacrf.security.DelegationTokenIssuer;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
class IdentityAccessService {
  private static final Set<String> DELEGABLE_CAPABILITIES =
      Set.of(
          "catalog.journey.read",
          "catalog.journey.write",
          "catalog.service.read",
          "catalog.service.write",
          "catalog.agent.read",
          "catalog.agent.write",
          "connections.connection.read",
          "connections.connection.write",
          "connections.observation.write",
          "evidence.event.read",
          "evidence.event.write",
          "evidence.inbox.write",
          "evidence.qualification.review",
          "objectives.objective.read",
          "objectives.objective.write",
          "objectives.objective.review",
          "objectives.objective.activate",
          "objectives.constraint.write",
          "owner.eligible",
          "runtime.principal.use");
  private static final Duration GRANT_DECISION_TTL = Duration.ofMinutes(15);
  private static final Duration MAX_GRANT_LIFETIME = Duration.ofDays(365);

  private final JdbcTemplate jdbc;
  private final DelegationTokenIssuer tokenIssuer;
  private final ObjectMapper objectMapper;

  private final ObjectProvider<TrustedDirectory> trustedDirectory;

  IdentityAccessService(
      JdbcTemplate jdbc,
      DelegationTokenIssuer tokenIssuer,
      ObjectMapper objectMapper,
      ObjectProvider<TrustedDirectory> trustedDirectory) {
    this.trustedDirectory = trustedDirectory;
    this.jdbc = jdbc;
    this.tokenIssuer = tokenIssuer;
    this.objectMapper = objectMapper;
  }

  @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  IdentityResponses.Delegation createDelegation(
      Jwt jwt, IdentityRequests.Delegation request) {
    if (!Set.of("S02", "S03", "S04", "S05").contains(request.audience())) {
      throw invalid("Only the S02, S03, S04 and S05 audiences are currently available.");
    }
    if (request.serviceId() != null && !isValidId(request.serviceId())) {
      throw invalid("service_id is malformed.");
    }
    LinkedHashSet<String> requestedCapabilities =
        new LinkedHashSet<>(request.capabilityIds());
    if (requestedCapabilities.size() != request.capabilityIds().size()
        || requestedCapabilities.stream().anyMatch(capability -> capability.contains("*"))) {
      throw invalid("Delegated capabilities must be unique and cannot include wildcards.");
    }

    Actor actor = resolveActor(jwt, request.tenantId(), request.environmentId());
    if (requestedCapabilities.contains("evidence.inbox.write") && !"SYSTEM".equals(actor.kind())) {
      throw denied("Service-to-service capabilities are only delegable to system principals.");
    }
    for (String capability : requestedCapabilities) {
      if (!hasCapability(actor, capability, request.serviceId())) {
        throw denied("The requested delegation exceeds current membership authority.");
      }
    }

    List<String> eligibleOwners =
        jdbc.query(
            "SELECT DISTINCT p.id FROM uacrf_identity.principal p "
                + "JOIN uacrf_identity.identity_provider ip ON ip.issuer_uri = p.issuer_uri "
                + "JOIN uacrf_identity.membership m ON m.principal_id = p.id "
                + "WHERE p.kind = 'HUMAN' AND p.enabled = true AND ip.status = 'TRUSTED' "
                + "AND m.tenant_id = ? AND m.environment_id = ? "
                + "AND m.capability = 'owner.eligible' AND m.service_scope = '*' "
                + "AND m.revoked_at IS NULL AND m.expires_at > now() "
                + "ORDER BY p.id",
            (rs, row) -> rs.getString(1),
            actor.tenantId(),
            actor.environmentId());
    String runtimeScopeClause =
        request.serviceId() == null
            ? "AND m.service_scope = '*' "
            : "AND (m.service_scope = '*' OR m.service_scope = ?) ";
    Object[] runtimeArguments =
        request.serviceId() == null
            ? new Object[] {actor.tenantId(), actor.environmentId()}
            : new Object[] {actor.tenantId(), actor.environmentId(), request.serviceId()};
    List<String> runtimePrincipals =
        jdbc.query(
            "SELECT DISTINCT p.id FROM uacrf_identity.principal p "
                + "JOIN uacrf_identity.identity_provider ip ON ip.issuer_uri = p.issuer_uri "
                + "JOIN uacrf_identity.membership m ON m.principal_id = p.id "
                + "WHERE p.kind IN ('SYSTEM', 'AGENT') AND p.enabled = true "
                + "AND ip.status = 'TRUSTED' AND m.tenant_id = ? AND m.environment_id = ? "
                + "AND m.capability = 'runtime.principal.use' AND m.revoked_at IS NULL "
                + "AND m.expires_at > now() "
                + runtimeScopeClause
                + "ORDER BY p.id",
            (rs, row) -> rs.getString(1),
            runtimeArguments);

    String accessToken =
        tokenIssuer.issue(
            new DelegationPrincipal(
                actor.principalId(),
                actor.tenantId(),
                actor.environmentId(),
                request.serviceId() == null ? "*" : request.serviceId(),
                jwt.getIssuer().toString(),
                jwt.getSubject(),
                List.copyOf(requestedCapabilities),
                eligibleOwners,
                runtimePrincipals),
            request.audience());
    return new IdentityResponses.Delegation(accessToken, "Bearer", 30);
  }

  @Transactional(readOnly = true)
  IdentityResponses.CurrentContextList getCurrentContexts(Jwt jwt, int limit, String cursor) {
    if (limit < 1 || limit > 100) {
      throw invalid("limit must be between 1 and 100.");
    }
    ContextCursor after = decodeContextCursor(cursor);
    String issuer = jwt.getIssuer() == null ? null : jwt.getIssuer().toString();
    String subject = jwt.getSubject();
    if (blank(issuer) || blank(subject) || jwt.getIssuedAt() == null) {
      throw denied("The authenticated session is missing its trusted identity.");
    }

    List<ContextPrincipal> principals =
        jdbc.query(
            "SELECT p.id, p.kind, p.token_valid_after FROM uacrf_identity.principal p "
                + "JOIN uacrf_identity.identity_provider ip ON ip.issuer_uri = p.issuer_uri "
                + "WHERE p.issuer_uri = ? AND p.subject = ? AND p.enabled = true "
                + "AND ip.status = 'TRUSTED'",
            (rs, row) ->
                new ContextPrincipal(
                    rs.getString("id"),
                    rs.getString("kind"),
                    rs.getTimestamp("token_valid_after").toInstant()),
            issuer,
            subject);
    if (principals.isEmpty() || !"HUMAN".equals(principals.getFirst().kind())) {
      return new IdentityResponses.CurrentContextList(List.of(), null);
    }
    ContextPrincipal principal = principals.getFirst();
    if (jwt.getIssuedAt().isBefore(principal.tokenValidAfter())) {
      throw denied("The authenticated session predates the principal's current validity.");
    }

    List<IdentityResponses.CurrentContext> rows =
        jdbc.query(
            "SELECT DISTINCT t.id AS tenant_id, t.display_name AS tenant_name, "
                + "e.id AS environment_id, e.display_name AS environment_name "
                + "FROM uacrf_identity.membership m "
                + "JOIN uacrf_identity.tenant t ON t.id = m.tenant_id "
                + "JOIN uacrf_identity.environment e "
                + "ON e.tenant_id = m.tenant_id AND e.id = m.environment_id "
                + "WHERE m.principal_id = ? AND m.revoked_at IS NULL AND m.expires_at > now() "
                + "AND t.status = 'ACTIVE' AND e.status = 'ACTIVE' "
                + "AND (t.id, e.id) > (?, ?) "
                + "ORDER BY t.id, e.id LIMIT ?",
            (rs, row) ->
                new IdentityResponses.CurrentContext(
                    rs.getString("tenant_id"),
                    rs.getString("tenant_name"),
                    rs.getString("environment_id"),
                    rs.getString("environment_name")),
            principal.id(),
            after.tenantId(),
            after.environmentId(),
            limit + 1);
    if (rows.size() <= limit) {
      return new IdentityResponses.CurrentContextList(rows, null);
    }
    List<IdentityResponses.CurrentContext> visible =
        new ArrayList<>(rows.subList(0, limit));
    IdentityResponses.CurrentContext last = visible.getLast();
    String nextCursor = encodeContextCursor(last.tenantId(), last.environmentId());
    return new IdentityResponses.CurrentContextList(visible, nextCursor);
  }

  @Transactional(readOnly = true)
  IdentityResponses.MeAccess getCurrentAccess(
      Jwt jwt, String serviceId, String tenantId, String environmentId) {
    if (serviceId != null && !isValidId(serviceId)) {
      throw invalid("service_id is malformed.");
    }
    Actor actor = resolveActor(jwt, tenantId, environmentId);
    String serviceScopeClause =
        serviceId == null ? "AND service_scope = '*' " : "AND (service_scope = '*' OR service_scope = ?) ";
    Object[] membershipArguments =
        serviceId == null
            ? new Object[] {actor.tenantId(), actor.environmentId(), actor.principalId()}
            : new Object[] {actor.tenantId(), actor.environmentId(), actor.principalId(), serviceId};
    List<String> capabilities =
        jdbc.query(
            "SELECT DISTINCT capability FROM uacrf_identity.membership "
                + "WHERE tenant_id = ? AND environment_id = ? AND principal_id = ? "
                + "AND revoked_at IS NULL AND expires_at > now() "
                + serviceScopeClause
                + "ORDER BY capability",
            (rs, row) -> rs.getString("capability"),
            membershipArguments);
    String scope =
        serviceId == null
            ? "GLOBAL_GRANTS_ONLY"
            : "GLOBAL_AND_SELECTED_SERVICE_GRANTS";
    return new IdentityResponses.MeAccess(
        actor.principalId(),
        actor.displayName(),
        actor.kind(),
        serviceId,
        scope,
        capabilities);
  }

  @Transactional(readOnly = true)
  IdentityResponses.IdentityProviderList listIdentityProviders(
      Jwt jwt, String tenantId, String environmentId) {
    Actor actor = requireDirectoryRead(jwt, tenantId, environmentId);
    List<IdentityResponses.IdentityProvider> providers =
        jdbc.query(
            "SELECT DISTINCT ip.id, ip.display_name, ip.status "
                + "FROM uacrf_identity.identity_provider ip "
                + "JOIN uacrf_identity.principal p ON p.issuer_uri = ip.issuer_uri "
                + "JOIN uacrf_identity.membership m ON m.principal_id = p.id "
                + "WHERE m.tenant_id = ? AND m.environment_id = ? "
                + "ORDER BY ip.id LIMIT 100",
            (rs, row) ->
                new IdentityResponses.IdentityProvider(
                    rs.getString("id"), rs.getString("display_name"), rs.getString("status")),
            actor.tenantId(),
            actor.environmentId());
    return new IdentityResponses.IdentityProviderList(providers);
  }

  @Transactional(readOnly = true)
  IdentityResponses.PrincipalPage listPrincipals(
      Jwt jwt, int limit, String cursor, String tenantId, String environmentId) {
    if (limit < 1 || limit > 100) {
      throw invalid("limit must be between 1 and 100.");
    }
    Actor actor = requireDirectoryRead(jwt, tenantId, environmentId);
    String after = decodeCursor(cursor);
    List<IdentityResponses.Principal> rows =
        jdbc.query(
            "SELECT p.id, p.display_name, p.kind, p.enabled, p.revision, "
                + "(p.kind = 'HUMAN' AND p.enabled = true AND ip.status = 'TRUSTED' "
                + "AND p.token_valid_after <= now() AND EXISTS ("
                + "SELECT 1 FROM uacrf_identity.membership owner_membership "
                + "WHERE owner_membership.principal_id = p.id "
                + "AND owner_membership.tenant_id = ? "
                + "AND owner_membership.environment_id = ? "
                + "AND owner_membership.capability = 'owner.eligible' "
                + "AND owner_membership.service_scope = '*' "
                + "AND owner_membership.revoked_at IS NULL "
                + "AND owner_membership.expires_at > now())) AS owner_eligible "
                + "FROM uacrf_identity.principal p "
                + "JOIN uacrf_identity.identity_provider ip ON ip.issuer_uri = p.issuer_uri "
                + "WHERE p.id > ? AND EXISTS ("
                + "SELECT 1 FROM uacrf_identity.membership scoped_membership "
                + "WHERE scoped_membership.principal_id = p.id "
                + "AND scoped_membership.tenant_id = ? "
                + "AND scoped_membership.environment_id = ?) "
                + "ORDER BY p.id LIMIT ?",
            (rs, row) ->
                new IdentityResponses.Principal(
                    rs.getString("id"),
                    rs.getString("display_name"),
                    rs.getString("kind"),
                    rs.getBoolean("enabled"),
                    rs.getBoolean("owner_eligible"),
                    rs.getLong("revision")),
            actor.tenantId(),
            actor.environmentId(),
            after,
            actor.tenantId(),
            actor.environmentId(),
            limit + 1);
    if (rows.size() <= limit) {
      return new IdentityResponses.PrincipalPage(rows, null);
    }
    List<IdentityResponses.Principal> visible = new ArrayList<>(rows.subList(0, limit));
    String nextCursor = encodeCursor(visible.getLast().id());
    return new IdentityResponses.PrincipalPage(visible, nextCursor);
  }

  @Transactional(readOnly = true)
  IdentityResponses.MembershipPage listMemberships(
      Jwt jwt, int limit, String cursor, String tenantId, String environmentId) {
    if (limit < 1 || limit > 100) {
      throw invalid("limit must be between 1 and 100.");
    }
    Actor actor = resolveActor(jwt, tenantId, environmentId);
    if (!hasCapability(actor, "identity.membership.read", null)) {
      throw denied("The current principal cannot read identity memberships.");
    }
    String after = decodeCursor(cursor);
    List<IdentityResponses.Membership> rows =
        jdbc.query(
            "SELECT id::text AS id, principal_id, capability, service_scope, expires_at, revision "
                + "FROM uacrf_identity.membership WHERE tenant_id = ? AND environment_id = ? "
                + "AND revoked_at IS NULL AND expires_at > now() AND id::text > ? "
                + "ORDER BY id LIMIT ?",
            (rs, row) ->
                new IdentityResponses.Membership(
                    rs.getString("id"),
                    rs.getString("principal_id"),
                    rs.getString("capability"),
                    rs.getString("service_scope"),
                    rs.getTimestamp("expires_at").toInstant(),
                    rs.getLong("revision")),
            actor.tenantId(),
            actor.environmentId(),
            after,
            limit + 1);
    if (rows.size() <= limit) {
      return new IdentityResponses.MembershipPage(rows, null);
    }
    List<IdentityResponses.Membership> visible = new ArrayList<>(rows.subList(0, limit));
    String nextCursor = encodeCursor(visible.getLast().id());
    return new IdentityResponses.MembershipPage(visible, nextCursor);
  }

  @Transactional
  IdentityResponses.MutationReceipt revokeMembership(
      Jwt jwt,
      String membershipId,
      IdentityRequests.RevokeMembership request,
      String idempotencyKey,
      String tenantId,
      String environmentId) {
    validateIdempotencyKey(idempotencyKey);
    UUID membershipUuid;
    try {
      membershipUuid = UUID.fromString(membershipId);
    } catch (IllegalArgumentException error) {
      throw invalid("membership id is malformed.");
    }

    Actor actor = requireMembershipManage(jwt, tenantId, environmentId);
    jdbc.update(
        "INSERT INTO uacrf_identity.membership_guard (tenant_id, environment_id) "
            + "VALUES (?, ?) ON CONFLICT DO NOTHING",
        actor.tenantId(),
        actor.environmentId());
    jdbc.query(
        "SELECT tenant_id FROM uacrf_identity.membership_guard "
            + "WHERE tenant_id = ? AND environment_id = ? FOR UPDATE",
        (rs, row) -> rs.getString(1),
        actor.tenantId(),
        actor.environmentId());
    actor = requireMembershipManage(jwt, actor.tenantId(), actor.environmentId());

    String operation = "POST /v1/memberships/{id}:revoke";
    String payloadDigest =
        sha256(serialize(List.of(membershipUuid.toString(), request.expectedRevision(), request.reason())));
    int inserted =
        jdbc.update(
            "INSERT INTO uacrf_identity.idempotency_record "
                + "(tenant_id, environment_id, actor_id, operation, idempotency_key, payload_digest) "
                + "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
            actor.tenantId(),
            actor.environmentId(),
            actor.principalId(),
            operation,
            idempotencyKey,
            payloadDigest);
    if (inserted == 0) {
      List<Map<String, Object>> records =
          jdbc.queryForList(
              "SELECT payload_digest, response::text FROM uacrf_identity.idempotency_record "
                  + "WHERE tenant_id = ? AND environment_id = ? AND actor_id = ? "
                  + "AND operation = ? AND idempotency_key = ? FOR UPDATE",
              actor.tenantId(),
              actor.environmentId(),
              actor.principalId(),
              operation,
              idempotencyKey);
      if (records.isEmpty() || !payloadDigest.equals(records.getFirst().get("payload_digest"))) {
        throw conflict(
            "IDEMPOTENCY_CONFLICT", "The idempotency key was already used for different content.");
      }
      Object response = records.getFirst().get("response");
      if (response == null) {
        throw conflict("COMMAND_IN_PROGRESS", "The original request has not completed.");
      }
      IdentityResponses.MutationReceipt receipt = deserializeMutation(response.toString());
      return new IdentityResponses.MutationReceipt(
          receipt.id(), receipt.revision(), "REPLAYED", receipt.runtimeEffect());
    }

    List<String> principals =
        jdbc.query(
            "SELECT principal_id FROM uacrf_identity.membership "
                + "WHERE id = ? AND tenant_id = ? AND environment_id = ?",
            (rs, row) -> rs.getString(1),
            membershipUuid,
            actor.tenantId(),
            actor.environmentId());
    if (principals.isEmpty()) {
      throw notFound("The membership is absent or not visible.");
    }
    String targetPrincipalId = principals.getFirst();
    jdbc.query(
        "SELECT id FROM uacrf_identity.principal WHERE id = ? FOR UPDATE",
        (rs, row) -> rs.getString(1),
        targetPrincipalId);
    List<MembershipState> memberships =
        jdbc.query(
            "SELECT capability, service_scope, revision, revoked_at IS NOT NULL AS revoked "
                + "FROM uacrf_identity.membership WHERE id = ? AND tenant_id = ? "
                + "AND environment_id = ? FOR UPDATE",
            (rs, row) ->
                new MembershipState(
                    rs.getString("capability"),
                    rs.getString("service_scope"),
                    rs.getLong("revision"),
                    rs.getBoolean("revoked")),
            membershipUuid,
            actor.tenantId(),
            actor.environmentId());
    if (memberships.isEmpty()) {
      throw notFound("The membership is absent or not visible.");
    }
    MembershipState membership = memberships.getFirst();
    if (membership.revision() != request.expectedRevision()) {
      throw conflict("REVISION_CONFLICT", "The membership revision has changed.");
    }
    if (membership.revoked()) {
      throw conflict("MEMBERSHIP_ALREADY_REVOKED", "The membership is already revoked.");
    }
    if ("identity.membership.manage".equals(membership.capability())
        && activeMembershipAdministrators(actor) <= 1) {
      throw conflict("LAST_IDENTITY_ADMIN", "The final active membership administrator cannot be removed.");
    }

    long newRevision = membership.revision() + 1;
    int updated =
        jdbc.update(
            "UPDATE uacrf_identity.membership SET revoked_at = now(), revocation_reason = ?, "
                + "revision = ? WHERE id = ? AND tenant_id = ? AND environment_id = ? "
                + "AND revision = ? AND revoked_at IS NULL",
            request.reason().trim(),
            newRevision,
            membershipUuid,
            actor.tenantId(),
            actor.environmentId(),
            membership.revision());
    if (updated != 1) {
      throw conflict("REVISION_CONFLICT", "The membership changed while it was being revoked.");
    }

    UUID eventId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO uacrf_identity.audit_event "
            + "(event_id, tenant_id, environment_id, actor_id, action, membership_id, "
            + "principal_id, capability, service_scope, previous_revision, new_revision, reason) "
            + "VALUES (?, ?, ?, ?, 'MEMBERSHIP_REVOKED', ?, ?, ?, ?, ?, ?, ?)",
        eventId,
        actor.tenantId(),
        actor.environmentId(),
        actor.principalId(),
        membershipUuid,
        targetPrincipalId,
        membership.capability(),
        membership.serviceScope(),
        membership.revision(),
        newRevision,
        request.reason().trim());
    jdbc.update(
        "INSERT INTO uacrf_identity.outbox_event "
            + "(event_id, tenant_id, environment_id, aggregate_type, aggregate_id, "
            + "aggregate_sequence, event_type, payload) "
            + "VALUES (?, ?, ?, 'IDENTITY_MEMBERSHIP', ?, ?, 'identity.membership.revoked', ?::jsonb)",
        eventId,
        actor.tenantId(),
        actor.environmentId(),
        membershipUuid.toString(),
        newRevision,
        serialize(
            Map.of(
                "actor_id", actor.principalId(),
                "membership_id", membershipUuid.toString(),
                "principal_id", targetPrincipalId,
                "capability", membership.capability(),
                "service_scope", membership.serviceScope(),
                "revision", newRevision,
                "reason", request.reason().trim())));
    IdentityResponses.MutationReceipt receipt =
        new IdentityResponses.MutationReceipt(
            membershipUuid.toString(), newRevision, "REVOKED", "NONE");
    jdbc.update(
        "UPDATE uacrf_identity.idempotency_record SET response = ?::jsonb "
            + "WHERE tenant_id = ? AND environment_id = ? AND actor_id = ? "
            + "AND operation = ? AND idempotency_key = ?",
        serialize(receipt),
        actor.tenantId(),
        actor.environmentId(),
        actor.principalId(),
        operation,
        idempotencyKey);
    return receipt;
  }

  @Transactional
  IdentityResponses.MutationReceipt setPrincipalStatus(
      Jwt jwt,
      String principalId,
      IdentityRequests.SetPrincipalStatus request,
      String idempotencyKey) {
    validateIdempotencyKey(idempotencyKey);
    if (!isValidId(principalId)) {
      throw invalid("principal id is malformed.");
    }
    if (request.enabled() == null) {
      throw invalid("enabled is required.");
    }

    Actor actor = requirePrincipalManage(jwt, null, null);
    if (request.enabled()) {
      if (blank(request.grantReviewRef())) {
        throw invalid("grant_review_ref is required when enabling a principal.");
      }
      throw unavailable(
          "TRUSTED_SOURCE_UNAVAILABLE",
          "Trusted-source re-verification and grant review are not configured.");
    }

    jdbc.update(
        "INSERT INTO uacrf_identity.membership_guard (tenant_id, environment_id) "
            + "VALUES (?, ?) ON CONFLICT DO NOTHING",
        actor.tenantId(),
        actor.environmentId());
    jdbc.query(
        "SELECT tenant_id FROM uacrf_identity.membership_guard "
            + "WHERE tenant_id = ? AND environment_id = ? FOR UPDATE",
        (rs, row) -> rs.getString(1),
        actor.tenantId(),
        actor.environmentId());
    actor = requirePrincipalManage(jwt, actor.tenantId(), actor.environmentId());

    String operation = "POST /v1/principals/{id}:set-status";
    String payloadDigest =
        sha256(
            serialize(
                List.of(
                    principalId,
                    request.enabled(),
                    request.expectedRevision(),
                    request.reason(),
                    Objects.toString(request.grantReviewRef(), ""))));
    int inserted =
        jdbc.update(
            "INSERT INTO uacrf_identity.idempotency_record "
                + "(tenant_id, environment_id, actor_id, operation, idempotency_key, payload_digest) "
                + "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
            actor.tenantId(),
            actor.environmentId(),
            actor.principalId(),
            operation,
            idempotencyKey,
            payloadDigest);
    if (inserted == 0) {
      List<Map<String, Object>> records =
          jdbc.queryForList(
              "SELECT payload_digest, response::text FROM uacrf_identity.idempotency_record "
                  + "WHERE tenant_id = ? AND environment_id = ? AND actor_id = ? "
                  + "AND operation = ? AND idempotency_key = ? FOR UPDATE",
              actor.tenantId(),
              actor.environmentId(),
              actor.principalId(),
              operation,
              idempotencyKey);
      if (records.isEmpty() || !payloadDigest.equals(records.getFirst().get("payload_digest"))) {
        throw conflict(
            "IDEMPOTENCY_CONFLICT", "The idempotency key was already used for different content.");
      }
      Object response = records.getFirst().get("response");
      if (response == null) {
        throw conflict("COMMAND_IN_PROGRESS", "The original request has not completed.");
      }
      IdentityResponses.MutationReceipt receipt = deserializeMutation(response.toString());
      return new IdentityResponses.MutationReceipt(
          receipt.id(), receipt.revision(), "REPLAYED", receipt.runtimeEffect());
    }

    List<PrincipalState> principals =
        jdbc.query(
            "SELECT p.enabled, p.revision FROM uacrf_identity.principal p "
                + "WHERE p.id = ? AND EXISTS ("
                + "SELECT 1 FROM uacrf_identity.membership m "
                + "WHERE m.principal_id = p.id AND m.tenant_id = ? AND m.environment_id = ?) "
                + "FOR UPDATE OF p",
            (rs, row) -> new PrincipalState(rs.getBoolean("enabled"), rs.getLong("revision")),
            principalId,
            actor.tenantId(),
            actor.environmentId());
    if (principals.isEmpty()) {
      throw notFound("The principal is absent or not visible.");
    }
    PrincipalState principal = principals.getFirst();
    if (principal.revision() != request.expectedRevision()) {
      throw conflict("REVISION_CONFLICT", "The principal revision has changed.");
    }
    if (!principal.enabled()) {
      throw conflict("PRINCIPAL_ALREADY_DISABLED", "The principal is already disabled.");
    }

    Boolean isMembershipAdministrator =
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM uacrf_identity.membership "
                + "WHERE tenant_id = ? AND environment_id = ? AND principal_id = ? "
                + "AND capability = 'identity.membership.manage' AND service_scope = '*' "
                + "AND revoked_at IS NULL AND expires_at > now())",
            Boolean.class,
            actor.tenantId(),
            actor.environmentId(),
            principalId);
    if (Boolean.TRUE.equals(isMembershipAdministrator)
        && activeMembershipAdministrators(actor) <= 1) {
      throw conflict("LAST_IDENTITY_ADMIN", "The final active membership administrator cannot be disabled.");
    }

    long newRevision = principal.revision() + 1;
    int updated =
        jdbc.update(
            "UPDATE uacrf_identity.principal SET enabled = false, "
                + "token_valid_after = GREATEST(token_valid_after, "
                + "date_trunc('second', now()) + interval '1 second'), revision = ? "
                + "WHERE id = ? AND enabled = true AND revision = ?",
            newRevision,
            principalId,
            principal.revision());
    if (updated != 1) {
      throw conflict("REVISION_CONFLICT", "The principal changed while its status was updated.");
    }

    UUID eventId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO uacrf_identity.principal_status_audit "
            + "(event_id, tenant_id, environment_id, actor_id, principal_id, previous_enabled, "
            + "new_enabled, previous_revision, new_revision, reason) "
            + "VALUES (?, ?, ?, ?, ?, true, false, ?, ?, ?)",
        eventId,
        actor.tenantId(),
        actor.environmentId(),
        actor.principalId(),
        principalId,
        principal.revision(),
        newRevision,
        request.reason().trim());
    jdbc.update(
        "INSERT INTO uacrf_identity.outbox_event "
            + "(event_id, tenant_id, environment_id, aggregate_type, aggregate_id, "
            + "aggregate_sequence, event_type, payload) "
            + "VALUES (?, ?, ?, 'IDENTITY_PRINCIPAL', ?, ?, 'identity.principal.disabled', ?::jsonb)",
        eventId,
        actor.tenantId(),
        actor.environmentId(),
        principalId,
        newRevision,
        serialize(
            Map.of(
                "actor_id", actor.principalId(),
                "principal_id", principalId,
                "enabled", false,
                "revision", newRevision,
                "reason", request.reason().trim())));
    IdentityResponses.MutationReceipt receipt =
        new IdentityResponses.MutationReceipt(principalId, newRevision, "UPDATED", "NONE");
    jdbc.update(
        "UPDATE uacrf_identity.idempotency_record SET response = ?::jsonb "
            + "WHERE tenant_id = ? AND environment_id = ? AND actor_id = ? "
            + "AND operation = ? AND idempotency_key = ?",
        serialize(receipt),
        actor.tenantId(),
        actor.environmentId(),
        actor.principalId(),
        operation,
        idempotencyKey);
    return receipt;
  }

  @Transactional
  IdentityResponses.MutationReceipt createGrantDecision(
      Jwt jwt,
      IdentityRequests.CreateGrantDecision request,
      String tenantId,
      String environmentId) {
    Actor approver = resolveActor(jwt, tenantId, environmentId);
    if (!hasCapability(approver, "identity.grant.approve", null)) {
      throw denied("The current principal cannot approve capability grants.");
    }
    Instant membershipExpiresAt = request.expiresAt().truncatedTo(ChronoUnit.MICROS);
    validateGrantShape(
        approver,
        request.principalId(),
        request.capability(),
        request.serviceScope(),
        membershipExpiresAt);

    int inserted =
        jdbc.update(
            "INSERT INTO uacrf_identity.grant_decision "
                + "(id, tenant_id, environment_id, approver_id, principal_id, capability, "
                + "service_scope, membership_expires_at, reason, decision_expires_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING",
            request.id(),
            approver.tenantId(),
            approver.environmentId(),
            approver.principalId(),
            request.principalId(),
            request.capability(),
            request.serviceScope(),
            Timestamp.from(membershipExpiresAt),
            request.reason().trim(),
            Timestamp.from(Instant.now().plus(GRANT_DECISION_TTL)));
    if (inserted == 1) {
      return new IdentityResponses.MutationReceipt(request.id(), 1, "CREATED", "NONE");
    }
    Boolean identicalDecision =
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM uacrf_identity.grant_decision WHERE id = ? "
                + "AND tenant_id = ? AND environment_id = ? AND approver_id = ? "
                + "AND principal_id = ? AND capability = ? AND service_scope = ? "
                + "AND membership_expires_at = ? AND reason = ?)",
            Boolean.class,
            request.id(),
            approver.tenantId(),
            approver.environmentId(),
            approver.principalId(),
            request.principalId(),
            request.capability(),
            request.serviceScope(),
            Timestamp.from(membershipExpiresAt),
            request.reason().trim());
    if (!Boolean.TRUE.equals(identicalDecision)) {
      throw conflict("GRANT_DECISION_CONFLICT", "The grant decision id is already used.");
    }
    return new IdentityResponses.MutationReceipt(request.id(), 1, "REPLAYED", "NONE");
  }

  @Transactional
  IdentityResponses.MutationReceipt createMembership(
      Jwt jwt,
      IdentityRequests.CreateMembership request,
      String idempotencyKey,
      String tenantId,
      String environmentId) {
    validateIdempotencyKey(idempotencyKey);
    UUID membershipId = UUID.fromString(request.id());
    Instant expiresAt = request.expiresAt().truncatedTo(ChronoUnit.MICROS);

    Actor actor = requireMembershipManage(jwt, tenantId, environmentId);
    lockMembershipGuard(actor);
    actor = requireMembershipManage(jwt, actor.tenantId(), actor.environmentId());

    String operation = "POST /v1/memberships";
    String payloadDigest =
        sha256(
            serialize(
                List.of(
                    membershipId.toString(),
                    request.principalId(),
                    request.capability(),
                    request.serviceScope(),
                    expiresAt.toString(),
                    request.reason(),
                    request.authorizationRef())));
    IdentityResponses.MutationReceipt replay =
        replayRecordedCommand(actor, operation, idempotencyKey, payloadDigest);
    if (replay != null) {
      return replay;
    }

    validateGrantShape(
        actor, request.principalId(), request.capability(), request.serviceScope(), expiresAt);
    List<GrantDecision> decisions =
        jdbc.query(
            "SELECT approver_id, principal_id, capability, service_scope, "
                + "membership_expires_at, decision_expires_at, consumed_at IS NOT NULL AS consumed "
                + "FROM uacrf_identity.grant_decision WHERE id = ? AND tenant_id = ? "
                + "AND environment_id = ? FOR UPDATE",
            (rs, row) ->
                new GrantDecision(
                    rs.getString("approver_id"),
                    rs.getString("principal_id"),
                    rs.getString("capability"),
                    rs.getString("service_scope"),
                    rs.getTimestamp("membership_expires_at").toInstant(),
                    rs.getTimestamp("decision_expires_at").toInstant(),
                    rs.getBoolean("consumed")),
            request.authorizationRef(),
            actor.tenantId(),
            actor.environmentId());
    if (decisions.isEmpty()) {
      throw invalidGrantAuthorization();
    }
    GrantDecision decision = decisions.getFirst();
    if (decision.consumed()
        || !decision.decisionExpiresAt().isAfter(Instant.now())
        || !decision.principalId().equals(request.principalId())
        || !decision.capability().equals(request.capability())
        || !decision.serviceScope().equals(request.serviceScope())
        || !decision.membershipExpiresAt().equals(expiresAt)
        || decision.approverId().equals(actor.principalId())
        || decision.approverId().equals(request.principalId())
        || !approverCurrentlyAuthorized(decision.approverId(), actor)) {
      throw invalidGrantAuthorization();
    }

    jdbc.query(
        "SELECT id FROM uacrf_identity.principal WHERE id = ? FOR UPDATE",
        (rs, row) -> rs.getString(1),
        request.principalId());
    Boolean duplicate =
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM uacrf_identity.membership WHERE tenant_id = ? "
                + "AND environment_id = ? AND principal_id = ? AND capability = ? "
                + "AND service_scope = ? AND revoked_at IS NULL AND expires_at > now())",
            Boolean.class,
            actor.tenantId(),
            actor.environmentId(),
            request.principalId(),
            request.capability(),
            request.serviceScope());
    if (Boolean.TRUE.equals(duplicate)) {
      throw conflict("DUPLICATE_MEMBERSHIP", "The principal already holds this active grant.");
    }

    int inserted =
        jdbc.update(
            "INSERT INTO uacrf_identity.membership "
                + "(id, tenant_id, environment_id, principal_id, capability, service_scope, "
                + "expires_at, revision, grant_reason, grant_decision_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?) ON CONFLICT (id) DO NOTHING",
            membershipId,
            actor.tenantId(),
            actor.environmentId(),
            request.principalId(),
            request.capability(),
            request.serviceScope(),
            Timestamp.from(expiresAt),
            request.reason().trim(),
            request.authorizationRef());
    if (inserted != 1) {
      throw conflict("MEMBERSHIP_ID_CONFLICT", "The membership id is already used.");
    }
    jdbc.update(
        "UPDATE uacrf_identity.grant_decision SET consumed_at = now(), "
            + "consumed_membership_id = ? WHERE id = ? AND consumed_at IS NULL",
        membershipId,
        request.authorizationRef());

    UUID eventId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO uacrf_identity.audit_event "
            + "(event_id, tenant_id, environment_id, actor_id, action, membership_id, "
            + "principal_id, capability, service_scope, previous_revision, new_revision, reason) "
            + "VALUES (?, ?, ?, ?, 'MEMBERSHIP_CREATED', ?, ?, ?, ?, 0, 1, ?)",
        eventId,
        actor.tenantId(),
        actor.environmentId(),
        actor.principalId(),
        membershipId,
        request.principalId(),
        request.capability(),
        request.serviceScope(),
        request.reason().trim());
    jdbc.update(
        "INSERT INTO uacrf_identity.outbox_event "
            + "(event_id, tenant_id, environment_id, aggregate_type, aggregate_id, "
            + "aggregate_sequence, event_type, payload) "
            + "VALUES (?, ?, ?, 'IDENTITY_MEMBERSHIP', ?, 1, 'identity.membership.created', ?::jsonb)",
        eventId,
        actor.tenantId(),
        actor.environmentId(),
        membershipId.toString(),
        serialize(
            Map.of(
                "actor_id", actor.principalId(),
                "approver_id", decision.approverId(),
                "membership_id", membershipId.toString(),
                "principal_id", request.principalId(),
                "capability", request.capability(),
                "service_scope", request.serviceScope(),
                "expires_at", expiresAt.toString(),
                "authorization_ref", request.authorizationRef(),
                "revision", 1)));
    IdentityResponses.MutationReceipt receipt =
        new IdentityResponses.MutationReceipt(membershipId.toString(), 1, "CREATED", "NONE");
    jdbc.update(
        "UPDATE uacrf_identity.idempotency_record SET response = ?::jsonb "
            + "WHERE tenant_id = ? AND environment_id = ? AND actor_id = ? "
            + "AND operation = ? AND idempotency_key = ?",
        serialize(receipt),
        actor.tenantId(),
        actor.environmentId(),
        actor.principalId(),
        operation,
        idempotencyKey);
    return receipt;
  }

  @Transactional
  IdentityResponses.MutationReceipt admitPrincipal(
      Jwt jwt,
      IdentityRequests.AdmitPrincipal request,
      String idempotencyKey,
      String tenantId,
      String environmentId) {
    validateIdempotencyKey(idempotencyKey);
    Actor actor = requirePrincipalManage(jwt, tenantId, environmentId);
    lockMembershipGuard(actor);
    actor = requirePrincipalManage(jwt, actor.tenantId(), actor.environmentId());

    String operation = "POST /v1/principals";
    String payloadDigest =
        sha256(
            serialize(
                List.of(
                    request.id(),
                    request.providerId(),
                    request.directorySubjectRef(),
                    request.reason())));
    IdentityResponses.MutationReceipt replay =
        replayRecordedCommand(actor, operation, idempotencyKey, payloadDigest);
    if (replay != null) {
      return replay;
    }

    List<String> issuers =
        jdbc.query(
            "SELECT issuer_uri FROM uacrf_identity.identity_provider "
                + "WHERE id = ? AND status = 'TRUSTED'",
            (rs, row) -> rs.getString(1),
            request.providerId());
    if (issuers.isEmpty()) {
      throw notFound("The identity provider is absent or not trusted.");
    }
    TrustedDirectory directory = trustedDirectory.getIfAvailable();
    if (directory == null) {
      throw unavailable(
          "TRUSTED_SOURCE_UNAVAILABLE", "Trusted-source verification is not configured.");
    }
    TrustedDirectory.DirectoryPrincipal source;
    try {
      source =
          directory
              .find(issuers.getFirst(), request.directorySubjectRef())
              .orElseThrow(() -> notFound("The directory subject is absent or not visible."));
    } catch (TrustedDirectory.UnavailableException error) {
      throw unavailable("TRUSTED_SOURCE_UNAVAILABLE", "The trusted source could not be verified.");
    }
    if (!source.enabled()) {
      throw unprocessable("DIRECTORY_SUBJECT_DISABLED", "The directory subject is disabled.");
    }
    if (blank(source.subject())
        || blank(source.displayName())
        || !("HUMAN".equals(source.kind()) || "SYSTEM".equals(source.kind()))) {
      throw unprocessable("DIRECTORY_SUBJECT_INVALID", "The directory entry is not admissible.");
    }

    int inserted =
        jdbc.update(
            "INSERT INTO uacrf_identity.principal "
                + "(id, issuer_uri, subject, display_name, kind, enabled, token_valid_after, revision) "
                + "VALUES (?, ?, ?, ?, ?, true, now(), 1) ON CONFLICT DO NOTHING",
            request.id(),
            issuers.getFirst(),
            source.subject(),
            source.displayName(),
            source.kind());
    if (inserted != 1) {
      throw conflict("PRINCIPAL_CONFLICT", "The principal id or directory subject is already admitted.");
    }

    UUID eventId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO uacrf_identity.principal_admission_audit "
            + "(event_id, tenant_id, environment_id, actor_id, principal_id, provider_id, "
            + "directory_subject_ref, reason) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        eventId,
        actor.tenantId(),
        actor.environmentId(),
        actor.principalId(),
        request.id(),
        request.providerId(),
        request.directorySubjectRef(),
        request.reason().trim());
    jdbc.update(
        "INSERT INTO uacrf_identity.outbox_event "
            + "(event_id, tenant_id, environment_id, aggregate_type, aggregate_id, "
            + "aggregate_sequence, event_type, payload) "
            + "VALUES (?, ?, ?, 'IDENTITY_PRINCIPAL', ?, 1, 'identity.principal.admitted', ?::jsonb)",
        eventId,
        actor.tenantId(),
        actor.environmentId(),
        request.id(),
        serialize(
            Map.of(
                "actor_id", actor.principalId(),
                "principal_id", request.id(),
                "provider_id", request.providerId(),
                "kind", source.kind(),
                "revision", 1)));
    IdentityResponses.MutationReceipt receipt =
        new IdentityResponses.MutationReceipt(request.id(), 1, "CREATED", "NONE");
    jdbc.update(
        "UPDATE uacrf_identity.idempotency_record SET response = ?::jsonb "
            + "WHERE tenant_id = ? AND environment_id = ? AND actor_id = ? "
            + "AND operation = ? AND idempotency_key = ?",
        serialize(receipt),
        actor.tenantId(),
        actor.environmentId(),
        actor.principalId(),
        operation,
        idempotencyKey);
    return receipt;
  }

  private void lockMembershipGuard(Actor actor) {
    jdbc.update(
        "INSERT INTO uacrf_identity.membership_guard (tenant_id, environment_id) "
            + "VALUES (?, ?) ON CONFLICT DO NOTHING",
        actor.tenantId(),
        actor.environmentId());
    jdbc.query(
        "SELECT tenant_id FROM uacrf_identity.membership_guard "
            + "WHERE tenant_id = ? AND environment_id = ? FOR UPDATE",
        (rs, row) -> rs.getString(1),
        actor.tenantId(),
        actor.environmentId());
  }

  // Returns the retained receipt for an exact replay, or null after registering a new command.
  private IdentityResponses.MutationReceipt replayRecordedCommand(
      Actor actor, String operation, String idempotencyKey, String payloadDigest) {
    int inserted =
        jdbc.update(
            "INSERT INTO uacrf_identity.idempotency_record "
                + "(tenant_id, environment_id, actor_id, operation, idempotency_key, payload_digest) "
                + "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
            actor.tenantId(),
            actor.environmentId(),
            actor.principalId(),
            operation,
            idempotencyKey,
            payloadDigest);
    if (inserted == 1) {
      return null;
    }
    List<Map<String, Object>> records =
        jdbc.queryForList(
            "SELECT payload_digest, response::text FROM uacrf_identity.idempotency_record "
                + "WHERE tenant_id = ? AND environment_id = ? AND actor_id = ? "
                + "AND operation = ? AND idempotency_key = ? FOR UPDATE",
            actor.tenantId(),
            actor.environmentId(),
            actor.principalId(),
            operation,
            idempotencyKey);
    if (records.isEmpty() || !payloadDigest.equals(records.getFirst().get("payload_digest"))) {
      throw conflict(
          "IDEMPOTENCY_CONFLICT", "The idempotency key was already used for different content.");
    }
    Object response = records.getFirst().get("response");
    if (response == null) {
      throw conflict("COMMAND_IN_PROGRESS", "The original request has not completed.");
    }
    IdentityResponses.MutationReceipt receipt = deserializeMutation(response.toString());
    return new IdentityResponses.MutationReceipt(
        receipt.id(), receipt.revision(), "REPLAYED", receipt.runtimeEffect());
  }

  private void validateGrantShape(
      Actor actor, String principalId, String capability, String serviceScope, Instant expiresAt) {
    if (!DELEGABLE_CAPABILITIES.contains(capability)) {
      throw unprocessable("UNKNOWN_CAPABILITY", "The capability is unknown or not delegable.");
    }
    if (!"*".equals(serviceScope) && !isValidId(serviceScope)) {
      throw invalid("service_scope is malformed.");
    }
    if ("owner.eligible".equals(capability) && !"*".equals(serviceScope)) {
      throw unprocessable("INVALID_SCOPE", "owner.eligible is environment-local and uses scope *.");
    }
    Instant now = Instant.now();
    if (!expiresAt.isAfter(now) || expiresAt.isAfter(now.plus(MAX_GRANT_LIFETIME))) {
      throw unprocessable("EXPIRY_OUT_OF_BOUNDS", "expires_at must be in the future and bounded.");
    }
    if (principalId.equals(actor.principalId())) {
      throw denied("Principals cannot grant capabilities to themselves.");
    }
    List<String> kinds =
        jdbc.query(
            "SELECT p.kind FROM uacrf_identity.principal p "
                + "JOIN uacrf_identity.identity_provider ip ON ip.issuer_uri = p.issuer_uri "
                + "WHERE p.id = ? AND p.enabled = true AND ip.status = 'TRUSTED'",
            (rs, row) -> rs.getString(1),
            principalId);
    if (kinds.isEmpty()) {
      throw notFound("The principal is absent or not visible.");
    }
    String kind = kinds.getFirst();
    boolean allowed =
        switch (capability) {
          case "owner.eligible" -> "HUMAN".equals(kind);
          case "runtime.principal.use" -> "SYSTEM".equals(kind) || "AGENT".equals(kind);
          default -> "HUMAN".equals(kind) || "SYSTEM".equals(kind);
        };
    if (!allowed) {
      throw unprocessable("INVALID_PRINCIPAL_KIND", "The principal kind cannot hold this capability.");
    }
  }

  private boolean approverCurrentlyAuthorized(String approverId, Actor actor) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM uacrf_identity.principal p "
                + "JOIN uacrf_identity.identity_provider ip ON ip.issuer_uri = p.issuer_uri "
                + "JOIN uacrf_identity.membership m ON m.principal_id = p.id "
                + "WHERE p.id = ? AND p.kind = 'HUMAN' AND p.enabled = true "
                + "AND p.token_valid_after <= now() AND ip.status = 'TRUSTED' "
                + "AND m.tenant_id = ? AND m.environment_id = ? "
                + "AND m.capability = 'identity.grant.approve' AND m.service_scope = '*' "
                + "AND m.revoked_at IS NULL AND m.expires_at > now())",
            Boolean.class,
            approverId,
            actor.tenantId(),
            actor.environmentId()));
  }

  private static IdentityException invalidGrantAuthorization() {
    return denied("authorization_ref is not a current independent grant decision for this grant.");
  }

  private static IdentityException unprocessable(String code, String message) {
    return new IdentityException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
  }

  @Transactional(readOnly = true)
  IdentityResponses.OwnerPage listOwnerCandidates(
      Jwt jwt,
      String objectKind,
      String objectId,
      int limit,
      String cursor,
      String tenantId,
      String environmentId) {
    if (limit < 1 || limit > 100) {
      throw invalid("limit must be between 1 and 100.");
    }
    if (objectId != null && (objectId.isBlank() || objectId.length() > 200)) {
      throw invalid("object_id is malformed.");
    }
    String requiredWriteCapability = writeCapability(objectKind);
    Actor actor = resolveActor(jwt, tenantId, environmentId);
    if (!hasCapability(actor, "identity.owner.select", "*")
        || !hasCapability(actor, requiredWriteCapability, objectId)) {
      throw denied("The current principal cannot select an owner for this target.");
    }

    String after = decodeCursor(cursor);
    List<IdentityResponses.OwnerCandidate> rows =
        jdbc.query(
            "SELECT DISTINCT p.id, p.display_name, p.kind, p.enabled, p.revision "
                + "FROM uacrf_identity.principal p "
                + "JOIN uacrf_identity.identity_provider ip ON ip.issuer_uri = p.issuer_uri "
                + "JOIN uacrf_identity.membership m ON m.principal_id = p.id "
                + "WHERE p.kind = 'HUMAN' AND p.enabled = true AND ip.status = 'TRUSTED' "
                + "AND p.token_valid_after <= now() AND m.tenant_id = ? "
                + "AND m.environment_id = ? AND m.capability = 'owner.eligible' "
                + "AND m.service_scope = '*' AND m.revoked_at IS NULL "
                + "AND m.expires_at > now() AND p.id > ? "
                + "ORDER BY p.id LIMIT ?",
            (rs, row) ->
                new IdentityResponses.OwnerCandidate(
                    rs.getString("id"),
                    rs.getString("display_name"),
                    rs.getString("kind"),
                    rs.getBoolean("enabled"),
                    true,
                    rs.getLong("revision")),
            actor.tenantId(),
            actor.environmentId(),
            after,
            limit + 1);
    if (rows.size() <= limit) {
      return new IdentityResponses.OwnerPage(rows, null);
    }
    List<IdentityResponses.OwnerCandidate> visible =
        new ArrayList<>(rows.subList(0, limit));
    String nextCursor = encodeCursor(visible.getLast().id());
    return new IdentityResponses.OwnerPage(visible, nextCursor);
  }

  private Actor requireDirectoryRead(Jwt jwt, String tenantId, String environmentId) {
    Actor actor = resolveActor(jwt, tenantId, environmentId);
    if (!hasCapability(actor, "identity.directory.read", null)) {
      throw denied("The current principal cannot read the identity directory.");
    }
    return actor;
  }

  private Actor requireMembershipManage(Jwt jwt, String tenantId, String environmentId) {
    Actor actor = resolveActor(jwt, tenantId, environmentId);
    if (!hasCapability(actor, "identity.membership.manage", null)) {
      throw denied("The current principal cannot manage identity memberships.");
    }
    return actor;
  }

  private Actor requirePrincipalManage(Jwt jwt, String tenantId, String environmentId) {
    Actor actor = resolveActor(jwt, tenantId, environmentId);
    if (!hasCapability(actor, "identity.principal.manage", null)) {
      throw denied("The current principal cannot manage identity principals.");
    }
    return actor;
  }

  private long activeMembershipAdministrators(Actor actor) {
    Long count =
        jdbc.queryForObject(
            "SELECT count(DISTINCT p.id) FROM uacrf_identity.principal p "
                + "JOIN uacrf_identity.identity_provider ip ON ip.issuer_uri = p.issuer_uri "
                + "JOIN uacrf_identity.membership m ON m.principal_id = p.id "
                + "WHERE m.tenant_id = ? AND m.environment_id = ? "
                + "AND m.capability = 'identity.membership.manage' "
                + "AND m.service_scope = '*' AND m.revoked_at IS NULL AND m.expires_at > now() "
                + "AND p.enabled = true AND p.token_valid_after <= now() AND ip.status = 'TRUSTED'",
            Long.class,
            actor.tenantId(),
            actor.environmentId());
    return count == null ? 0 : count;
  }

  private static void validateSelectedContext(String tenantId, String environmentId) {
    if ((tenantId == null) != (environmentId == null)) {
      throw invalid("tenant_id and environment_id must be provided together.");
    }
    if (tenantId != null && (!isValidId(tenantId) || !isValidId(environmentId))) {
      throw invalid("The selected tenant or environment id is malformed.");
    }
  }

  private Actor resolveActor(Jwt jwt, String selectedTenantId, String selectedEnvironmentId) {
    validateSelectedContext(selectedTenantId, selectedEnvironmentId);
    String issuer = jwt.getIssuer() == null ? null : jwt.getIssuer().toString();
    String subject = jwt.getSubject();
    String tenantId =
        selectedTenantId == null ? jwt.getClaimAsString("tenant_id") : selectedTenantId;
    String environmentId =
        selectedEnvironmentId == null
            ? jwt.getClaimAsString("environment_id")
            : selectedEnvironmentId;
    if (blank(issuer) || blank(subject) || blank(tenantId) || blank(environmentId)) {
      throw denied("The authenticated session has no active UACRF tenant and environment.");
    }
    if (jwt.getIssuedAt() == null) {
      throw denied("The authenticated session is missing its issue time.");
    }

    List<Actor> principals =
        jdbc.query(
            "SELECT p.id, p.display_name, p.kind, p.token_valid_after, p.revision "
                + "FROM uacrf_identity.principal p "
                + "JOIN uacrf_identity.identity_provider ip ON ip.issuer_uri = p.issuer_uri "
                + "JOIN uacrf_identity.environment e ON e.tenant_id = ? AND e.id = ? "
                + "JOIN uacrf_identity.tenant t ON t.id = e.tenant_id "
                + "WHERE p.issuer_uri = ? AND p.subject = ? AND p.enabled = true "
                + "AND ip.status = 'TRUSTED' AND e.status = 'ACTIVE' AND t.status = 'ACTIVE' "
                + "AND p.kind IN ('HUMAN', 'SYSTEM') AND EXISTS ("
                + "SELECT 1 FROM uacrf_identity.membership m "
                + "WHERE m.tenant_id = e.tenant_id AND m.environment_id = e.id "
                + "AND m.principal_id = p.id AND m.revoked_at IS NULL AND m.expires_at > now())",
            (rs, row) ->
                new Actor(
                    rs.getString("id"),
                    rs.getString("display_name"),
                    rs.getString("kind"),
                    tenantId,
                    environmentId,
                    rs.getTimestamp("token_valid_after").toInstant()),
            tenantId,
            environmentId,
            issuer,
            subject);
    if (principals.isEmpty()) {
      throw denied("The authenticated identity is not currently admitted for this environment.");
    }
    Actor actor = principals.getFirst();
    if (jwt.getIssuedAt().isBefore(actor.tokenValidAfter())) {
      throw denied("The authenticated session predates the principal's current validity.");
    }
    return actor;
  }

  private boolean hasCapability(Actor actor, String capability, String serviceScope) {
    String scopeClause =
        serviceScope == null
            ? "AND service_scope = '*'"
            : "AND (service_scope = '*' OR service_scope = ?)";
    Object[] arguments =
        serviceScope == null
            ? new Object[] {
              actor.tenantId(), actor.environmentId(), actor.principalId(), capability
            }
            : new Object[] {
              actor.tenantId(),
              actor.environmentId(),
              actor.principalId(),
              capability,
              serviceScope
            };
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM uacrf_identity.membership "
                + "WHERE tenant_id = ? AND environment_id = ? AND principal_id = ? "
                + "AND capability = ? AND revoked_at IS NULL AND expires_at > now() "
                + scopeClause
                + ")",
            Boolean.class,
            arguments));
  }

  private static String writeCapability(String objectKind) {
    return switch (objectKind) {
      case "JOURNEY" -> "catalog.journey.write";
      case "SERVICE" -> "catalog.service.write";
      case "AGENT" -> "catalog.agent.write";
      case "RESOURCE" -> "catalog.resource.write";
      case "ONBOARDING" -> "onboarding.write";
      case "KNOWLEDGE" -> "knowledge.write";
      default -> throw invalid("object_kind is not supported.");
    };
  }

  private static String decodeCursor(String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return "";
    }
    if (cursor.length() > 500) {
      throw invalid("cursor is malformed.");
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

  private static ContextCursor decodeContextCursor(String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return new ContextCursor("", "");
    }
    if (cursor.length() > 600) {
      throw invalid("cursor is malformed.");
    }
    try {
      String[] parts =
          new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
              .split("/", -1);
      if (parts.length != 2 || !isValidId(parts[0]) || !isValidId(parts[1])) {
        throw new IllegalArgumentException();
      }
      return new ContextCursor(parts[0], parts[1]);
    } catch (IllegalArgumentException error) {
      throw invalid("cursor is malformed.");
    }
  }

  private static String encodeContextCursor(String tenantId, String environmentId) {
    return encodeCursor(tenantId + "/" + environmentId);
  }

  private String serialize(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException error) {
      throw new IllegalStateException("Could not serialize the identity mutation.", error);
    }
  }

  private IdentityResponses.MutationReceipt deserializeMutation(String value) {
    try {
      return objectMapper.readValue(value, IdentityResponses.MutationReceipt.class);
    } catch (JacksonException error) {
      throw new IllegalStateException("Could not read the retained identity mutation result.", error);
    }
  }

  private static String sha256(String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is required by the runtime.", error);
    }
  }

  private static void validateIdempotencyKey(String key) {
    if (key == null
        || key.isBlank()
        || key.length() > 200
        || key.chars().anyMatch(Character::isWhitespace)) {
      throw invalid("Idempotency-Key must be a non-empty token of at most 200 characters.");
    }
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static boolean isValidId(String value) {
    return value.length() <= 200 && value.matches("^[A-Za-z0-9][A-Za-z0-9_.:-]*$");
  }

  private static IdentityException denied(String message) {
    return new IdentityException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", message);
  }

  private static IdentityException invalid(String message) {
    return new IdentityException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
  }

  private static IdentityException notFound(String message) {
    return new IdentityException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
  }

  private static IdentityException conflict(String code, String message) {
    return new IdentityException(HttpStatus.CONFLICT, code, message);
  }

  private static IdentityException unavailable(String code, String message) {
    return new IdentityException(HttpStatus.SERVICE_UNAVAILABLE, code, message);
  }

  private record PrincipalState(boolean enabled, long revision) {}

  private record GrantDecision(
      String approverId,
      String principalId,
      String capability,
      String serviceScope,
      Instant membershipExpiresAt,
      Instant decisionExpiresAt,
      boolean consumed) {}

  private record MembershipState(
      String capability, String serviceScope, long revision, boolean revoked) {}

  private record ContextCursor(String tenantId, String environmentId) {}

  private record ContextPrincipal(String id, String kind, java.time.Instant tokenValidAfter) {}

  private record Actor(
      String principalId,
      String displayName,
      String kind,
      String tenantId,
      String environmentId,
      java.time.Instant tokenValidAfter) {}
}
