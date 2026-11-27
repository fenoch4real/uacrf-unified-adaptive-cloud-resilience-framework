package io.uacrf.identity.service;

import io.uacrf.crypto.CanonicalJson;
import io.uacrf.identity.api.IdentityDomainException;
import io.uacrf.identity.api.IdentityDtos.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class IdentityRepository {

    private final JdbcClient jdbc;

    public IdentityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<EnvironmentView> listEnvironments(UUID tenantId) {
        return jdbc.sql("""
                SELECT environment_id, tenant_id, slug, name, env_class, production, authority_tier, status
                FROM foundation.environment
                WHERE tenant_id = :tenantId
                ORDER BY slug
                """)
                .param("tenantId", tenantId)
                .query((rs, rowNum) -> new EnvironmentView(
                        rs.getObject("environment_id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("slug"),
                        rs.getString("name"),
                        rs.getString("env_class"),
                        rs.getBoolean("production"),
                        rs.getString("authority_tier"),
                        rs.getString("status")
                ))
                .list();
    }

    public List<PrincipalView> listPrincipals(UUID tenantId, UUID environmentId) {
        return jdbc.sql("""
                SELECT principal_id, tenant_id, environment_id, principal_type, issuer, external_subject,
                       display_name, email, status, version
                FROM foundation.principal
                WHERE tenant_id = :tenantId AND environment_id = :environmentId
                ORDER BY display_name, principal_id
                """)
                .param("tenantId", tenantId)
                .param("environmentId", environmentId)
                .query((rs, rowNum) -> mapPrincipal(rs, "NONE"))
                .list();
    }

    public List<PrincipalView> listEligibleOwners(UUID tenantId, UUID environmentId, Instant now) {
        return jdbc.sql("""
                SELECT DISTINCT p.principal_id, p.tenant_id, p.environment_id, p.principal_type, p.issuer,
                       p.external_subject, p.display_name, p.email, p.status, p.version
                FROM foundation.principal p
                JOIN foundation.membership m ON m.principal_id = p.principal_id
                WHERE p.tenant_id = :tenantId
                  AND p.environment_id = :environmentId
                  AND p.principal_type = 'HUMAN'
                  AND p.status = 'ENABLED'
                  AND m.tenant_id = :tenantId
                  AND m.environment_id = :environmentId
                  AND m.capability = 'owner.eligible'
                  AND m.service_scope = 'GLOBAL'
                  AND m.revoked_at IS NULL
                  AND m.expires_at > :now
                ORDER BY p.display_name
                """)
                .param("tenantId", tenantId)
                .param("environmentId", environmentId)
                .param("now", Timestamp.from(now))
                .query((rs, rowNum) -> mapPrincipal(rs, "NONE"))
                .list();
    }

    public Optional<PrincipalView> findPrincipal(UUID tenantId, UUID environmentId, UUID principalId) {
        return jdbc.sql("""
                SELECT principal_id, tenant_id, environment_id, principal_type, issuer, external_subject,
                       display_name, email, status, version
                FROM foundation.principal
                WHERE tenant_id = :tenantId AND environment_id = :environmentId AND principal_id = :principalId
                """)
                .param("tenantId", tenantId)
                .param("environmentId", environmentId)
                .param("principalId", principalId)
                .query((rs, rowNum) -> mapPrincipal(rs, "NONE"))
                .optional();
    }

    public Optional<PrincipalView> findPrincipalForUpdate(UUID tenantId, UUID environmentId, UUID principalId) {
        return jdbc.sql("""
                SELECT principal_id, tenant_id, environment_id, principal_type, issuer, external_subject,
                       display_name, email, status, version
                FROM foundation.principal
                WHERE tenant_id = :tenantId AND environment_id = :environmentId AND principal_id = :principalId
                FOR UPDATE
                """)
                .param("tenantId", tenantId)
                .param("environmentId", environmentId)
                .param("principalId", principalId)
                .query((rs, rowNum) -> mapPrincipal(rs, "NONE"))
                .optional();
    }

    public PrincipalView insertPrincipal(AdmitPrincipalRequest req, UUID actorId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO foundation.principal
                  (principal_id, tenant_id, environment_id, principal_type, issuer, external_subject, display_name, email, status)
                VALUES
                  (:id, :tenantId, :envId, :type, :issuer, :subject, :displayName, :email, 'ENABLED')
                """)
                .param("id", id)
                .param("tenantId", req.tenantId())
                .param("envId", req.environmentId())
                .param("type", req.principalType())
                .param("issuer", req.issuer())
                .param("subject", req.externalSubject())
                .param("displayName", req.displayName())
                .param("email", req.email())
                .update();

        PrincipalView view = new PrincipalView(id, req.tenantId(), req.environmentId(), req.principalType(),
                req.issuer(), req.externalSubject(), req.displayName(), req.email(), "ENABLED", 1L, "NONE");
        recordAuditAndOutbox(req.tenantId(), req.environmentId(), actorId, "PRINCIPAL_ADMITTED",
                "PRINCIPAL", id, "Directory principal admitted without membership grants", null, view, 1L);
        return view;
    }

    public PrincipalView disablePrincipal(PrincipalView current, UUID actorId, String reason) {
        long nextVersion = current.version() + 1;
        int updated = jdbc.sql("""
                UPDATE foundation.principal
                SET status = 'DISABLED', version = :nextVersion, updated_at = now()
                WHERE principal_id = :id AND tenant_id = :tenantId AND environment_id = :envId AND version = :expectedVersion
                """)
                .param("nextVersion", nextVersion)
                .param("id", current.principalId())
                .param("tenantId", current.tenantId())
                .param("envId", current.environmentId())
                .param("expectedVersion", current.version())
                .update();
        if (updated != 1) {
            throw new IdentityDomainException(HttpStatus.CONFLICT, "STALE_REVISION", "Principal revision changed concurrently");
        }
        PrincipalView after = new PrincipalView(current.principalId(), current.tenantId(), current.environmentId(),
                current.principalType(), current.issuer(), current.externalSubject(), current.displayName(),
                current.email(), "DISABLED", nextVersion, "NONE");
        recordAuditAndOutbox(current.tenantId(), current.environmentId(), actorId, "PRINCIPAL_DISABLED",
                "PRINCIPAL", current.principalId(), reason, current, after, nextVersion);
        return after;
    }

    public long countActiveCapabilityHolders(UUID tenantId, UUID environmentId, String capability, UUID excludingPrincipalId, Instant now) {
        return jdbc.sql("""
                SELECT COUNT(DISTINCT m.principal_id)
                FROM foundation.membership m
                JOIN foundation.principal p ON p.principal_id = m.principal_id
                WHERE m.tenant_id = :tenantId
                  AND m.environment_id = :envId
                  AND m.capability = :capability
                  AND m.revoked_at IS NULL
                  AND m.expires_at > :now
                  AND p.status = 'ENABLED'
                  AND p.principal_id <> :excludingId
                """)
                .param("tenantId", tenantId)
                .param("envId", environmentId)
                .param("capability", capability)
                .param("now", Timestamp.from(now))
                .param("excludingId", excludingPrincipalId)
                .query(Long.class)
                .single();
    }

    public GrantDecisionView insertGrantDecision(CreateGrantDecisionRequest req, UUID approverId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO foundation.grant_decision
                  (grant_decision_id, tenant_id, environment_id, target_principal_id, capability,
                   service_scope, approver_principal_id, decision, rationale, expires_at)
                VALUES
                  (:id, :tenantId, :envId, :targetId, :capability, :scope, :approverId, :decision, :rationale, :expiresAt)
                """)
                .param("id", id)
                .param("tenantId", req.tenantId())
                .param("envId", req.environmentId())
                .param("targetId", req.targetPrincipalId())
                .param("capability", req.capability())
                .param("scope", req.serviceScope())
                .param("approverId", approverId)
                .param("decision", req.decision())
                .param("rationale", req.rationale())
                .param("expiresAt", Timestamp.from(req.expiresAt()))
                .update();

        GrantDecisionView view = new GrantDecisionView(id, req.tenantId(), req.environmentId(),
                req.targetPrincipalId(), req.capability(), req.serviceScope(), approverId,
                req.decision(), req.rationale(), req.expiresAt(), null);
        recordAuditAndOutbox(req.tenantId(), req.environmentId(), approverId, "GRANT_DECISION_RECORDED",
                "GRANT_DECISION", id, req.rationale(), null, view, 1L);
        return view;
    }

    public Optional<GrantDecisionView> lockGrantDecision(UUID tenantId, UUID environmentId, UUID grantDecisionId) {
        return jdbc.sql("""
                SELECT grant_decision_id, tenant_id, environment_id, target_principal_id, capability,
                       service_scope, approver_principal_id, decision, rationale, expires_at, consumed_at
                FROM foundation.grant_decision
                WHERE grant_decision_id = :id AND tenant_id = :tenantId AND environment_id = :envId
                FOR UPDATE
                """)
                .param("id", grantDecisionId)
                .param("tenantId", tenantId)
                .param("envId", environmentId)
                .query((rs, rowNum) -> new GrantDecisionView(
                        rs.getObject("grant_decision_id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("environment_id", UUID.class),
                        rs.getObject("target_principal_id", UUID.class),
                        rs.getString("capability"),
                        rs.getString("service_scope"),
                        rs.getObject("approver_principal_id", UUID.class),
                        rs.getString("decision"),
                        rs.getString("rationale"),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("consumed_at") == null ? null : rs.getTimestamp("consumed_at").toInstant()
                ))
                .optional();
    }

    public MembershipView consumeDecisionAndInsertMembership(CreateMembershipRequest req, UUID managerId, Instant now) {
        int marked = jdbc.sql("""
                UPDATE foundation.grant_decision
                SET consumed_at = :now
                WHERE grant_decision_id = :id AND consumed_at IS NULL
                """)
                .param("now", Timestamp.from(now))
                .param("id", req.grantDecisionId())
                .update();
        if (marked != 1) {
            throw new IdentityDomainException(HttpStatus.CONFLICT, "GRANT_DECISION_ALREADY_CONSUMED",
                    "Grant decision was already consumed");
        }

        UUID membershipId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO foundation.membership
                  (membership_id, tenant_id, environment_id, principal_id, capability, service_scope,
                   grant_decision_id, granted_by_principal_id, expires_at)
                VALUES
                  (:id, :tenantId, :envId, :principalId, :capability, :scope, :decisionId, :managerId, :expiresAt)
                """)
                .param("id", membershipId)
                .param("tenantId", req.tenantId())
                .param("envId", req.environmentId())
                .param("principalId", req.principalId())
                .param("capability", req.capability())
                .param("scope", req.serviceScope())
                .param("decisionId", req.grantDecisionId())
                .param("managerId", managerId)
                .param("expiresAt", Timestamp.from(req.expiresAt()))
                .update();

        MembershipView view = new MembershipView(membershipId, req.tenantId(), req.environmentId(),
                req.principalId(), req.capability(), req.serviceScope(), req.grantDecisionId(),
                managerId, req.expiresAt(), null, 1L);
        recordAuditAndOutbox(req.tenantId(), req.environmentId(), managerId, "MEMBERSHIP_GRANTED",
                "MEMBERSHIP", membershipId, "Approved membership granted", null, view, 1L);
        return view;
    }

    public List<MembershipView> listMemberships(UUID tenantId, UUID environmentId) {
        return jdbc.sql("""
                SELECT membership_id, tenant_id, environment_id, principal_id, capability, service_scope,
                       grant_decision_id, granted_by_principal_id, expires_at, revoked_at, version
                FROM foundation.membership
                WHERE tenant_id = :tenantId AND environment_id = :envId
                ORDER BY created_at DESC
                """)
                .param("tenantId", tenantId)
                .param("environmentId", environmentId)
                .query((rs, rowNum) -> mapMembership(rs))
                .list();
    }

    public List<MembershipView> findActiveMembershipsForPrincipal(UUID tenantId, UUID environmentId,
                                                                  UUID principalId, String serviceScope, Instant now) {
        return jdbc.sql("""
                SELECT membership_id, tenant_id, environment_id, principal_id, capability, service_scope,
                       grant_decision_id, granted_by_principal_id, expires_at, revoked_at, version
                FROM foundation.membership
                WHERE tenant_id = :tenantId
                  AND environment_id = :envId
                  AND principal_id = :principalId
                  AND (service_scope = 'GLOBAL' OR service_scope = :serviceScope)
                  AND revoked_at IS NULL
                  AND expires_at > :now
                """)
                .param("tenantId", tenantId)
                .param("envId", environmentId)
                .param("principalId", principalId)
                .param("serviceScope", serviceScope)
                .param("now", Timestamp.from(now))
                .query((rs, rowNum) -> mapMembership(rs))
                .list();
    }

    public void revokeMembership(UUID tenantId, UUID environmentId, UUID membershipId, UUID actorId, String reason, Instant now) {
        int updated = jdbc.sql("""
                UPDATE foundation.membership
                SET revoked_at = :now, version = version + 1
                WHERE membership_id = :id AND tenant_id = :tenantId AND environment_id = :envId AND revoked_at IS NULL
                """)
                .param("now", Timestamp.from(now))
                .param("id", membershipId)
                .param("tenantId", tenantId)
                .param("envId", environmentId)
                .update();
        if (updated != 1) {
            throw new IdentityDomainException(HttpStatus.NOT_FOUND, "MEMBERSHIP_NOT_ACTIVE", "Active membership not found");
        }
        recordAuditAndOutbox(tenantId, environmentId, actorId, "MEMBERSHIP_REVOKED",
                "MEMBERSHIP", membershipId, reason, null, Map.of("revokedAt", now.toString()), 2L);
    }

    public Optional<StoredIdempotency> findIdempotency(UUID tenantId, UUID environmentId, UUID actorId,
                                                       String operation, String key) {
        return jdbc.sql("""
                SELECT request_digest, response_status, response_body::text
                FROM foundation.idempotency_record
                WHERE tenant_id = :tenantId AND environment_id = :envId AND actor_id = :actorId
                  AND operation = :op AND idempotency_key = :key
                """)
                .param("tenantId", tenantId)
                .param("envId", environmentId)
                .param("actorId", actorId)
                .param("op", operation)
                .param("key", key)
                .query((rs, rowNum) -> new StoredIdempotency(
                        rs.getString("request_digest"),
                        rs.getInt("response_status"),
                        rs.getString("response_body")
                ))
                .optional();
    }

    public void saveIdempotency(UUID tenantId, UUID environmentId, UUID actorId, String operation,
                                String key, String requestDigest, int status, Object body) {
        jdbc.sql("""
                INSERT INTO foundation.idempotency_record
                  (tenant_id, environment_id, actor_id, operation, idempotency_key, request_digest, response_status, response_body)
                VALUES
                  (:tenantId, :envId, :actorId, :op, :key, :digest, :status, CAST(:body AS jsonb))
                """)
                .param("tenantId", tenantId)
                .param("envId", environmentId)
                .param("actorId", actorId)
                .param("op", operation)
                .param("key", key)
                .param("digest", requestDigest)
                .param("status", status)
                .param("body", toJson(body))
                .update();
    }

    private void recordAuditAndOutbox(UUID tenantId, UUID environmentId, UUID actorId, String action,
                                      String entityType, UUID entityId, String reason,
                                      Object beforeObj, Object afterObj, long aggregateVersion) {
        String beforeDigest = beforeObj == null ? null : CanonicalJson.digestObject(beforeObj);
        String afterJson = toJson(afterObj);
        String afterDigest = CanonicalJson.digestObject(afterObj);

        jdbc.sql("""
                INSERT INTO foundation.audit_event
                  (audit_event_id, tenant_id, environment_id, actor_id, action, entity_type, entity_id, reason, before_digest, after_digest)
                VALUES
                  (:auditId, :tenantId, :envId, :actorId, :action, :entityType, :entityId, :reason, :beforeDigest, :afterDigest)
                """)
                .param("auditId", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("envId", environmentId)
                .param("actorId", actorId)
                .param("action", action)
                .param("entityType", entityType)
                .param("entityId", entityId)
                .param("reason", reason)
                .param("beforeDigest", beforeDigest)
                .param("afterDigest", afterDigest)
                .update();

        jdbc.sql("""
                INSERT INTO integration.outbox_event
                  (outbox_event_id, tenant_id, aggregate_type, aggregate_id, aggregate_version, event_type, payload, payload_digest)
                VALUES
                  (:outboxId, :tenantId, :aggType, :aggId, :aggVer, :eventType, CAST(:payload AS jsonb), :digest)
                """)
                .param("outboxId", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("aggType", entityType)
                .param("aggId", entityId)
                .param("aggVer", aggregateVersion)
                .param("eventType", action)
                .param("payload", afterJson)
                .param("digest", afterDigest)
                .update();
    }

    private String toJson(Object value) {
        return CanonicalJson.toJson(value);
    }

    private static PrincipalView mapPrincipal(ResultSet rs, String runtimeEffect) throws SQLException {
        return new PrincipalView(
                rs.getObject("principal_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("environment_id", UUID.class),
                rs.getString("principal_type"),
                rs.getString("issuer"),
                rs.getString("external_subject"),
                rs.getString("display_name"),
                rs.getString("email"),
                rs.getString("status"),
                rs.getLong("version"),
                runtimeEffect
        );
    }

    private static MembershipView mapMembership(ResultSet rs) throws SQLException {
        return new MembershipView(
                rs.getObject("membership_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("environment_id", UUID.class),
                rs.getObject("principal_id", UUID.class),
                rs.getString("capability"),
                rs.getString("service_scope"),
                rs.getObject("grant_decision_id", UUID.class),
                rs.getObject("granted_by_principal_id", UUID.class),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant(),
                rs.getLong("version")
        );
    }

    public record StoredIdempotency(String requestDigest, int status, String responseJson) {
    }
}
