package io.uacrf.policy.service;

import com.nimbusds.jose.jwk.RSAKey;
import io.uacrf.crypto.CanonicalJson;
import io.uacrf.crypto.EnvelopeSigner;
import io.uacrf.policy.api.PolicyDomainException;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Controlled Break-Glass Escalation Authority (FR-APR-002):
 * Enforces MFA step-up verification, mandatory justification, TTL <= 120 minutes,
 * JWS signing, and a mandatory post-incident review lock.
 */
@Service
public class BreakGlassService {

    private static final int MAX_BREAK_GLASS_TTL_MINUTES = 120;

    private final JdbcTemplate jdbc;
    private final RSAKey rsaKey = EnvelopeSigner.generateEphemeralRsaKey("uacrf-s10-breakglass-1");
    private final EnvelopeSigner signer = new EnvelopeSigner(rsaKey, Clock.systemUTC());

    public BreakGlassService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<BreakGlassGrantDto> MAPPER = (rs, rowNum) -> new BreakGlassGrantDto(
            rs.getObject("break_glass_id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("environment_id", UUID.class),
            rs.getString("service_canonical_id"),
            rs.getString("action_type"),
            rs.getString("requested_by"),
            rs.getString("justification"),
            rs.getBoolean("mfa_step_up_verified"),
            rs.getString("signed_jws_grant"),
            rs.getString("post_incident_review_status"),
            rs.getTimestamp("expires_at").toInstant(),
            rs.getTimestamp("created_at").toInstant()
    );

    public List<BreakGlassGrantDto> listGrants(UUID tenantId, UUID environmentId) {
        return jdbc.query("""
                SELECT * FROM policy.break_glass_grant
                WHERE tenant_id = ? AND environment_id = ?
                ORDER BY created_at DESC
                """, MAPPER, tenantId, environmentId);
    }

    @Transactional
    public BreakGlassGrantDto issueBreakGlass(BreakGlassRequest req) {
        if (!req.mfaStepUpVerified()) {
            throw new PolicyDomainException(HttpStatus.FORBIDDEN, "MFA_STEP_UP_REQUIRED",
                    "Break-glass emergency escalation requires verified step-up MFA authentication.");
        }
        if (req.ttlMinutes() > MAX_BREAK_GLASS_TTL_MINUTES) {
            throw new PolicyDomainException(HttpStatus.BAD_REQUEST, "BREAK_GLASS_TTL_EXCEEDED",
                    "Break-glass duration cannot exceed " + MAX_BREAK_GLASS_TTL_MINUTES + " minutes.");
        }
        UUID grantId = UUID.randomUUID();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(req.ttlMinutes(), ChronoUnit.MINUTES);
        String jws = signer.sign("uacrf-s10-breakglass", req.actionType(), req.serviceCanonicalId(),
                grantId.toString(), expiresAt, Map.of(
                        "tenantId", req.tenantId().toString(),
                        "environmentId", req.environmentId().toString(),
                        "requestedBy", req.requestedBy(),
                        "breakGlass", true
                ));

        jdbc.update("""
                INSERT INTO policy.break_glass_grant (
                    break_glass_id, tenant_id, environment_id, service_canonical_id, action_type,
                    requested_by, justification, mfa_step_up_verified, signed_jws_grant,
                    post_incident_review_status, expires_at, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'REQUIRED_OPEN', ?, ?)
                """,
                grantId, req.tenantId(), req.environmentId(), req.serviceCanonicalId(), req.actionType(),
                req.requestedBy(), req.justification(), true, jws, Timestamp.from(expiresAt), Timestamp.from(now));

        jdbc.update("""
                INSERT INTO integration.outbox_event (
                    outbox_id, tenant_id, environment_id, event_type, aggregate_type, aggregate_id, correlation_id, payload_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """,
                UUID.randomUUID(), req.tenantId(), req.environmentId(), "policy.break_glass.issued",
                "BreakGlassGrant", grantId.toString(), UUID.randomUUID(), CanonicalJson.toJson(req));

        return new BreakGlassGrantDto(grantId, req.tenantId(), req.environmentId(), req.serviceCanonicalId(),
                req.actionType(), req.requestedBy(), req.justification(), true, jws, "REQUIRED_OPEN", expiresAt, now);
    }

    public record BreakGlassRequest(
            @NotNull UUID tenantId, @NotNull UUID environmentId,
            @NotBlank String serviceCanonicalId, @NotBlank String actionType,
            @NotBlank String requestedBy, @NotBlank String justification,
            boolean mfaStepUpVerified, @Min(1) int ttlMinutes
    ) {
    }

    public record BreakGlassGrantDto(
            UUID breakGlassId, UUID tenantId, UUID environmentId,
            String serviceCanonicalId, String actionType, String requestedBy,
            String justification, boolean mfaStepUpVerified, String signedJwsGrant,
            String postIncidentReviewStatus, Instant expiresAt, Instant createdAt
    ) {
    }
}
