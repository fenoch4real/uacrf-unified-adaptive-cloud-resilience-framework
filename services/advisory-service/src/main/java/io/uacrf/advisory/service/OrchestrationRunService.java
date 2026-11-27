package io.uacrf.advisory.service;

import io.uacrf.advisory.api.AdvisoryDomainException;
import io.uacrf.crypto.CanonicalJson;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Asynchronous Protected Agent Runtime Admission, Budget Reservation, Checkpoint & Cancellation Ledger
 * (FR-AWL-004..009, FR-ORC-001..006).
 */
@Service
public class OrchestrationRunService {

    private final JdbcTemplate jdbc;

    public OrchestrationRunService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<OrchestrationRunDto> RUN_MAPPER = (rs, rowNum) -> new OrchestrationRunDto(
            rs.getObject("run_id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("environment_id", UUID.class),
            rs.getString("workload_id"),
            rs.getString("idempotency_key"),
            rs.getString("request_digest"),
            rs.getString("status"),
            rs.getInt("max_steps"),
            rs.getInt("steps_used"),
            rs.getInt("max_tokens"),
            rs.getInt("tokens_used"),
            rs.getInt("max_cost_cents"),
            rs.getInt("cost_used_cents"),
            rs.getString("checkpoint_sha256"),
            rs.getObject("parent_run_id", UUID.class),
            rs.getString("created_by"),
            rs.getTimestamp("expires_at").toInstant(),
            rs.getTimestamp("created_at").toInstant()
    );

    public List<OrchestrationRunDto> listRuns(UUID tenantId, UUID environmentId) {
        return jdbc.query("""
                SELECT * FROM advisory.orchestration_run
                WHERE tenant_id = ? AND environment_id = ?
                ORDER BY created_at DESC
                """, RUN_MAPPER, tenantId, environmentId);
    }

    public List<Map<String, Object>> listEvents(UUID runId) {
        return jdbc.queryForList("""
                SELECT event_id, event_kind, accepted, detail, event_digest, created_at
                FROM advisory.orchestration_event WHERE run_id = ? ORDER BY created_at ASC
                """, runId);
    }

    @Transactional
    public OrchestrationRunDto admitRun(AdmitRunRequest req) {
        String reqDigest = CanonicalJson.digestObject(Map.of(
                "workloadId", req.workloadId(),
                "maxSteps", req.maxSteps(),
                "maxTokens", req.maxTokens(),
                "maxCostCents", req.maxCostCents()
        ));
        var existing = jdbc.query("""
                SELECT * FROM advisory.orchestration_run
                WHERE tenant_id = ? AND environment_id = ? AND idempotency_key = ?
                """, RUN_MAPPER, req.tenantId(), req.environmentId(), req.idempotencyKey());
        if (!existing.isEmpty()) {
            OrchestrationRunDto prev = existing.getFirst();
            if (!prev.requestDigest().equals(reqDigest)) {
                throw new AdvisoryDomainException(HttpStatus.CONFLICT, "IDEMPOTENCY_DIGEST_MISMATCH",
                        "Idempotency key reused with conflicting payload.");
            }
            return prev;
        }

        UUID runId = UUID.randomUUID();
        Instant now = Instant.now();
        Instant expires = now.plus(Math.max(1, req.ttlMinutes()), ChronoUnit.MINUTES);
        jdbc.update("""
                INSERT INTO advisory.orchestration_run (
                    run_id, tenant_id, environment_id, workload_id, idempotency_key, request_digest,
                    status, max_steps, steps_used, max_tokens, tokens_used, max_cost_cents, cost_used_cents,
                    created_by, expires_at, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'ADMITTED', ?, 0, ?, 0, ?, 0, ?, ?, ?)
                """,
                runId, req.tenantId(), req.environmentId(), req.workloadId(), req.idempotencyKey(), reqDigest,
                req.maxSteps(), req.maxTokens(), req.maxCostCents(), req.createdBy(),
                Timestamp.from(expires), Timestamp.from(now));
        appendEvent(runId, req.tenantId(), req.environmentId(), "RUN_ADMITTED", true,
                "Scoped admission granted for " + req.workloadId());
        return getRun(runId);
    }

    @Transactional
    public OrchestrationRunDto recordStepCheckpoint(UUID runId, StepCheckpointRequest req) {
        OrchestrationRunDto run = getRun(runId);
        if ("CANCELLED".equals(run.status()) || "PAUSED".equals(run.status())) {
            appendEvent(runId, run.tenantId(), run.environmentId(), "LATE_CALLBACK_FENCED", false,
                    "Rejected late step after terminal/fenced status=" + run.status());
            throw new AdvisoryDomainException(HttpStatus.CONFLICT, "RUN_FENCED",
                    "Orchestration run is " + run.status() + "; late step callback preserved as unaccepted.");
        }
        if (run.stepsUsed() + 1 > run.maxSteps()
                || run.tokensUsed() + req.tokensConsumed() > run.maxTokens()
                || run.costUsedCents() + req.costCents() > run.maxCostCents()) {
            throw new AdvisoryDomainException(HttpStatus.TOO_MANY_REQUESTS, "AGENT_BUDGET_EXHAUSTED",
                    "Per-attempt step, token, or cost ceiling exceeded.");
        }
        String checkpointDigest = CanonicalJson.digestObject(req.checkpointPayload());
        jdbc.update("""
                UPDATE advisory.orchestration_run
                SET status = 'RUNNING',
                    steps_used = steps_used + 1,
                    tokens_used = tokens_used + ?,
                    cost_used_cents = cost_used_cents + ?,
                    checkpoint_sha256 = ?
                WHERE run_id = ?
                """, req.tokensConsumed(), req.costCents(), checkpointDigest, runId);
        appendEvent(runId, run.tenantId(), run.environmentId(), "STEP_CHECKPOINT", true,
                "Step checkpoint bound to " + checkpointDigest);
        return getRun(runId);
    }

    @Transactional
    public OrchestrationRunDto cancelRun(UUID runId, String reason) {
        OrchestrationRunDto run = getRun(runId);
        jdbc.update("UPDATE advisory.orchestration_run SET status = 'CANCELLED' WHERE run_id = ?", runId);
        appendEvent(runId, run.tenantId(), run.environmentId(), "RUN_CANCELLED", true, reason);
        return getRun(runId);
    }

    @Transactional
    public OrchestrationRunDto resumeFromCheckpoint(UUID parentRunId, ResumeRunRequest req) {
        OrchestrationRunDto parent = getRun(parentRunId);
        if (parent.checkpointSha256() == null || !parent.checkpointSha256().equals(req.expectedCheckpointSha256())) {
            throw new AdvisoryDomainException(HttpStatus.CONFLICT, "CHECKPOINT_MISMATCH",
                    "Cannot resume: checkpoint digest does not match verified state.");
        }
        int remSteps = Math.max(0, parent.maxSteps() - parent.stepsUsed());
        int remTokens = Math.max(0, parent.maxTokens() - parent.tokensUsed());
        int remCost = Math.max(0, parent.maxCostCents() - parent.costUsedCents());
        if (remSteps == 0 || remTokens == 0 || !parent.expiresAt().isAfter(Instant.now())) {
            throw new AdvisoryDomainException(HttpStatus.CONFLICT, "RESUMPTION_BUDGET_EXHAUSTED",
                    "No remaining step/token budget or deadline remaining on parent run.");
        }
        UUID resumedId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO advisory.orchestration_run (
                    run_id, tenant_id, environment_id, workload_id, idempotency_key, request_digest,
                    status, max_steps, steps_used, max_tokens, tokens_used, max_cost_cents, cost_used_cents,
                    checkpoint_sha256, parent_run_id, created_by, expires_at, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'ADMITTED', ?, 0, ?, 0, ?, 0, ?, ?, ?, ?, ?)
                """,
                resumedId, parent.tenantId(), parent.environmentId(), parent.workloadId(),
                req.idempotencyKey(), parent.requestDigest(), remSteps, remTokens, remCost,
                parent.checkpointSha256(), parentRunId, req.resumedBy(),
                Timestamp.from(parent.expiresAt()), Timestamp.from(now));
        appendEvent(resumedId, parent.tenantId(), parent.environmentId(), "RESUMED_FROM_CHECKPOINT", true,
                "Linked to parent run " + parentRunId + " within remaining budget");
        return getRun(resumedId);
    }

    public OrchestrationRunDto getRun(UUID runId) {
        var list = jdbc.query("SELECT * FROM advisory.orchestration_run WHERE run_id = ?", RUN_MAPPER, runId);
        if (list.isEmpty()) {
            throw new AdvisoryDomainException(HttpStatus.NOT_FOUND, "RUN_NOT_FOUND", "Orchestration run not found: " + runId);
        }
        return list.getFirst();
    }

    private void appendEvent(UUID runId, UUID tenantId, UUID envId, String kind, boolean accepted, String detail) {
        String digest = CanonicalJson.digestObject(Map.of("runId", runId, "kind", kind, "accepted", accepted, "detail", detail));
        jdbc.update("""
                INSERT INTO advisory.orchestration_event (event_id, run_id, tenant_id, environment_id, event_kind, accepted, detail, event_digest)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), runId, tenantId, envId, kind, accepted, detail, digest);
    }

    public record AdmitRunRequest(
            @NotNull UUID tenantId, @NotNull UUID environmentId,
            @NotBlank String workloadId, @NotBlank String idempotencyKey,
            @Min(1) int maxSteps, @Min(1) int maxTokens, @Min(1) int maxCostCents,
            @Min(1) int ttlMinutes, @NotBlank String createdBy
    ) {
    }

    public record StepCheckpointRequest(
            @Min(0) int tokensConsumed, @Min(0) int costCents,
            @NotNull Map<String, Object> checkpointPayload
    ) {
    }

    public record ResumeRunRequest(
            @NotBlank String idempotencyKey, @NotBlank String expectedCheckpointSha256, @NotBlank String resumedBy
    ) {
    }

    public record OrchestrationRunDto(
            UUID runId, UUID tenantId, UUID environmentId, String workloadId, String idempotencyKey,
            String requestDigest, String status, int maxSteps, int stepsUsed,
            int maxTokens, int tokensUsed, int maxCostCents, int costUsedCents,
            String checkpointSha256, UUID parentRunId, String createdBy, Instant expiresAt, Instant createdAt
    ) {
    }
}
