package io.uacrf.objectives.service;

import io.uacrf.crypto.CanonicalJson;
import io.uacrf.objectives.api.ObjectivesDtos.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Service
public class ObjectivesControlService {

    private final ObjectivesRepository repository;
    private final CoreRuleEvaluator evaluator;

    public ObjectivesControlService(ObjectivesRepository repository, CoreRuleEvaluator evaluator) {
        this.repository = repository;
        this.evaluator = evaluator;
    }

    public List<ObjectiveDto> listObjectives(UUID tenantId, UUID environmentId) {
        return repository.listObjectives(tenantId, environmentId);
    }

    public List<RiskEvaluationDto> listRiskEvaluations(UUID tenantId, UUID environmentId) {
        return repository.listRiskEvaluations(tenantId, environmentId);
    }

    @Transactional
    public RiskEvaluationDto evaluateRisk(EvaluateRiskRequest req) {
        var verdict = evaluator.evaluate(req);
        Instant now = Instant.now();
        String timeStr = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC).format(now);

        double val = req.orderSuccessRatePct() != null ? req.orderSuccessRatePct() : 0.0;
        double delta = val - 94.0;
        String deltaText = String.format("%+.1f pp vs. baseline", delta);

        RiskEvaluationDto eval = new RiskEvaluationDto(
                UUID.randomUUID(), req.tenantId(), req.environmentId(), req.serviceCanonicalId(),
                req.serviceName(), req.journeyName(), req.ownerName(), verdict.riskState(),
                val, deltaText, verdict.triggeredRules(), verdict.summary(),
                List.of(new TimelineEventDto(timeStr, "Core rule evaluation -> " + verdict.riskState(), verdict.summary())),
                now
        );

        repository.insertRiskEvaluation(eval);
        repository.insertOutbox(
                req.tenantId(), req.environmentId(), "control.risk_state.evaluated",
                "RiskEvaluation", eval.evaluationId().toString(),
                UUID.randomUUID(), CanonicalJson.toJson(eval)
        );
        return eval;
    }
}
