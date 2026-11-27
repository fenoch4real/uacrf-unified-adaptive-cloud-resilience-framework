package io.uacrf.recovery.service;

import io.uacrf.contracts.authority.BoundedRecoveryAuthorization;
import io.uacrf.crypto.CanonicalJson;
import io.uacrf.recovery.adapter.ProviderActionAdapter;
import io.uacrf.recovery.api.RecoveryDomainException;
import io.uacrf.recovery.api.RecoveryDtos.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class RecoveryOrchestrationService {

    private final RecoveryRepository repository;
    private final CrossLayerValidator validator;
    private final Map<String, ProviderActionAdapter> adapters;

    public RecoveryOrchestrationService(RecoveryRepository repository,
                                        CrossLayerValidator validator,
                                        List<ProviderActionAdapter> adapterList) {
        this.repository = repository;
        this.validator = validator;
        this.adapters = adapterList.stream().collect(Collectors.toMap(ProviderActionAdapter::adapterKey, Function.identity()));
    }

    public KillSwitchDto getKillSwitch(UUID tenantId, UUID environmentId) {
        return repository.getKillSwitch(tenantId, environmentId);
    }

    @Transactional
    public KillSwitchDto setKillSwitch(UpdateKillSwitchRequest req) {
        var ks = repository.setKillSwitch(req.tenantId(), req.environmentId(), req.active(), req.reason(), req.updatedBy());
        repository.insertOutbox(req.tenantId(), req.environmentId(), "recovery.kill_switch.updated",
                "KillSwitch", req.environmentId().toString(), UUID.randomUUID(), CanonicalJson.toJson(ks));
        return ks;
    }

    public List<RecoveryExecutionDto> listExecutions(UUID tenantId, UUID environmentId) {
        return repository.listExecutions(tenantId, environmentId);
    }

    @Transactional
    public RecoveryExecutionDto executeRecovery(ExecuteRecoveryRequest req) {
        KillSwitchDto ks = repository.getKillSwitch(req.tenantId(), req.environmentId());
        if (ks.active()) {
            throw new RecoveryDomainException(HttpStatus.CONFLICT, "KILL_SWITCH_ACTIVE",
                    "Recovery execution blocked: operator kill switch is ACTIVE (" + ks.reason() + ").");
        }

        BoundedRecoveryAuthorization auth = req.authorization();
        if (auth.expiresAt() == null || !auth.expiresAt().isAfter(Instant.now())) {
            throw new RecoveryDomainException(HttpStatus.FORBIDDEN, "AUTHORIZATION_EXPIRED",
                    "BoundedRecoveryAuthorization expired.");
        }

        if (!req.tenantId().toString().equals(auth.tenantId()) || !req.environmentId().toString().equals(auth.environmentId())) {
            throw new RecoveryDomainException(HttpStatus.FORBIDDEN, "TENANT_OR_ENV_MISMATCH",
                    "Authorization does not match requested tenant or environment.");
        }

        if (!req.adapterKey().equals(auth.audience())) {
            throw new RecoveryDomainException(HttpStatus.FORBIDDEN, "AUDIENCE_MISMATCH",
                    "Requested adapter [" + req.adapterKey() + "] != signed audience [" + auth.audience() + "].");
        }

        if (auth.targetCanonicalIds() == null || !auth.targetCanonicalIds().contains(req.requestedTargetCanonicalId())) {
            throw new RecoveryDomainException(HttpStatus.FORBIDDEN, "TARGET_SUBSTITUTION_FORBIDDEN",
                    "Target substitution blocked: [" + req.requestedTargetCanonicalId() + "] is outside allowlisted targets.");
        }

        ProviderActionAdapter adapter = adapters.get(req.adapterKey());
        if (adapter == null || !adapter.supportsAction(auth.actionType())) {
            throw new RecoveryDomainException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_ADAPTER_OR_ACTION",
                    "Adapter " + req.adapterKey() + " does not support " + auth.actionType());
        }

        boolean freshNonce = repository.tryConsumeNonce(auth.nonce(), UUID.fromString(auth.authorizationId()));
        if (!freshNonce) {
            throw new RecoveryDomainException(HttpStatus.CONFLICT, "NONCE_REPLAY_DETECTED",
                    "Single-use authorization nonce [" + auth.nonce() + "] has already been consumed.");
        }

        Instant startedAt = Instant.now();
        var actionResult = adapter.execute(auth, req.requestedTargetCanonicalId(), req.requestedTargetVersion());
        var validation = validator.validateExecution(req.simulateProbeFailure());

        boolean compensated = false;
        String finalState = "SUCCEEDED";
        String summary = actionResult.summary();

        if (!validation.passed()) {
            var compResult = adapter.compensate(auth, req.requestedTargetCanonicalId(), "v43");
            compensated = compResult.succeeded();
            finalState = compensated ? "COMPENSATED" : "ESCALATED";
            summary = summary + " | " + compResult.summary();
        }

        RecoveryExecutionDto exec = new RecoveryExecutionDto(
                UUID.randomUUID(), req.tenantId(), req.environmentId(), UUID.fromString(auth.authorizationId()),
                auth.nonce(), req.requestedTargetCanonicalId(), auth.actionType(), req.adapterKey(),
                req.requestedTargetVersion(), finalState, summary, validation.steps(), compensated,
                req.executedBy(), startedAt, Instant.now()
        );
        repository.insertExecution(exec);
        repository.insertOutbox(req.tenantId(), req.environmentId(), "recovery.execution." + finalState.toLowerCase(),
                "RecoveryExecution", exec.executionId().toString(), UUID.randomUUID(), CanonicalJson.toJson(exec));
        return exec;
    }
}
