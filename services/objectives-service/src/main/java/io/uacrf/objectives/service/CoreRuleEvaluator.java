package io.uacrf.objectives.service;

import io.uacrf.objectives.api.ObjectivesDtos.EvaluateRiskRequest;
import io.uacrf.objectives.api.ObjectivesDtos.TriggeredRuleDto;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic Core Control Rule Engine implementing Appendix I multi-layer rules
 * (INF-*, PLT-*, APP-*, AI-*, SEC-*, GOV-*) driving the UACRF RiskState machine.
 */
@Component
public class CoreRuleEvaluator {

    public EvaluationVerdict evaluate(EvaluateRiskRequest req) {
        if (Boolean.TRUE.equals(req.telemetryStale()) || req.orderSuccessRatePct() == null) {
            return new EvaluationVerdict(
                    "UNKNOWN",
                    List.of(new TriggeredRuleDto("GOV-TEL-001", "GOVERNANCE", "UNKNOWN",
                            "Required telemetry missing or expired; Core cannot assert NORMAL state.")),
                    "Telemetry freshness missing or incomplete."
            );
        }

        List<TriggeredRuleDto> triggered = new ArrayList<>();
        String state = "NORMAL";

        if (Boolean.TRUE.equals(req.securityAlertActive())) {
            triggered.add(new TriggeredRuleDto("SEC-AUTH-001", "SECURITY", "CRITICAL",
                    "Security integrity or identity anomaly detected."));
            state = "CRITICAL";
        }

        if (req.orderSuccessRatePct() < 75.0) {
            triggered.add(new TriggeredRuleDto("APP-ERR-002", "APPLICATION", "CRITICAL",
                    "Order success dropped below 75.0% critical SLO threshold (" + req.orderSuccessRatePct() + "%)."));
            state = "CRITICAL";
        } else if (req.orderSuccessRatePct() < 95.0) {
            triggered.add(new TriggeredRuleDto("APP-ERR-001", "APPLICATION", "ELEVATED",
                    "Order success degraded to " + req.orderSuccessRatePct() + "% (SLO target 99.5%)."));
            if (!"CRITICAL".equals(state)) {
                state = "ELEVATED";
            }
        } else if (req.orderSuccessRatePct() < 99.5) {
            triggered.add(new TriggeredRuleDto("APP-SLO-003", "APPLICATION", "GUARDED",
                    "Error budget burn elevated (" + req.orderSuccessRatePct() + "% vs 99.5% SLO)."));
            if ("NORMAL".equals(state)) {
                state = "GUARDED";
            }
        }

        if (req.cpuSaturationPct() != null && req.cpuSaturationPct() > 85.0) {
            triggered.add(new TriggeredRuleDto("INF-SAT-001", "INFRASTRUCTURE", "ELEVATED",
                    "Compute saturation reached " + req.cpuSaturationPct() + "%."));
            if ("NORMAL".equals(state) || "GUARDED".equals(state)) {
                state = "ELEVATED";
            }
        }

        if (req.computeHeadroomPct() != null && req.computeHeadroomPct() < 15.0) {
            triggered.add(new TriggeredRuleDto("INF-CMP-001", "INFRASTRUCTURE", "ELEVATED",
                    "Compute autoscaling headroom fell to " + req.computeHeadroomPct() + "% (minimum 15%)."));
            if ("NORMAL".equals(state) || "GUARDED".equals(state)) {
                state = "ELEVATED";
            }
        }

        if (req.storageIopsSaturationPct() != null && req.storageIopsSaturationPct() > 80.0) {
            triggered.add(new TriggeredRuleDto("INF-STR-001", "INFRASTRUCTURE", "ELEVATED",
                    "Storage IOPS saturation reached " + req.storageIopsSaturationPct() + "%."));
            if ("NORMAL".equals(state) || "GUARDED".equals(state)) {
                state = "ELEVATED";
            }
        }

        if (req.networkPacketLossPct() != null && req.networkPacketLossPct() > 1.0) {
            triggered.add(new TriggeredRuleDto("INF-NET-001", "INFRASTRUCTURE", "CRITICAL",
                    "Network packet loss breached 1.0% threshold (" + req.networkPacketLossPct() + "%)."));
            state = "CRITICAL";
        }

        if (req.queueDeadLetterCount() != null && req.queueDeadLetterCount() > 50) {
            triggered.add(new TriggeredRuleDto("PLT-QUE-001", "PLATFORM", "ELEVATED",
                    "Async queue DLQ backlog accumulated " + req.queueDeadLetterCount() + " messages."));
            if ("NORMAL".equals(state) || "GUARDED".equals(state)) {
                state = "ELEVATED";
            }
        }

        if (req.dbConnectionPoolPct() != null && req.dbConnectionPoolPct() > 90.0) {
            triggered.add(new TriggeredRuleDto("DAT-DB-001", "DATA", "CRITICAL",
                    "Database connection pool exhaustion reached " + req.dbConnectionPoolPct() + "%."));
            state = "CRITICAL";
        }

        if (Boolean.TRUE.equals(req.aiSafetyDriftDetected())) {
            triggered.add(new TriggeredRuleDto("AI-SAF-001", "AI_DATA", "ELEVATED",
                    "Protected AI dependency subgroup safety or feature drift flagged."));
            if ("NORMAL".equals(state) || "GUARDED".equals(state)) {
                state = "ELEVATED";
            }
        }

        String summary = triggered.isEmpty()
                ? "All Core SLO and multi-layer resilience rules within normal envelope."
                : triggered.stream().map(TriggeredRuleDto::ruleId).reduce((a, b) -> a + ", " + b).orElse("")
                + " triggered -> RiskState=" + state;

        return new EvaluationVerdict(state, triggered, summary);
    }

    public record EvaluationVerdict(
            String riskState,
            List<TriggeredRuleDto> triggeredRules,
            String summary
    ) {
    }
}
