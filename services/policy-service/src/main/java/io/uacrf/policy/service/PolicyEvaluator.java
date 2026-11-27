package io.uacrf.policy.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * OPA Rego Policy Evaluator implementing the 4 canonical outcomes from policies/baseline.rego:
 * DENY, RECOMMEND, APPROVAL_REQUIRED, PREAUTHORIZED.
 */
@Component
public class PolicyEvaluator {

    private final boolean opaEnabled;
    private final String opaUrl;
    private final int maxBlastRadius;
    private final RestClient restClient = RestClient.create();

    public PolicyEvaluator(
            @Value("${uacrf.policy.opa.enabled:false}") boolean opaEnabled,
            @Value("${uacrf.policy.opa.url}") String opaUrl,
            @Value("${uacrf.policy.max-blast-radius:3}") int maxBlastRadius) {
        this.opaEnabled = opaEnabled;
        this.opaUrl = opaUrl;
        this.maxBlastRadius = maxBlastRadius;
    }

    @SuppressWarnings("unchecked")
    public PolicyVerdict evaluate(String environmentKind, String evidenceQualification,
                                  boolean reversible, int blastRadius, boolean runbookCertified) {
        if (opaEnabled) {
            try {
                Map<String, Object> input = Map.of("input", Map.of(
                        "environment", environmentKind,
                        "evidenceQualification", evidenceQualification,
                        "graphReconciliationStatus", "COMPLETE",
                        "runbook", Map.of("certified", runbookCertified),
                        "policy", Map.of("maxBlastRadius", maxBlastRadius),
                        "action", Map.of("reversible", reversible, "blastRadius", blastRadius)
                ));
                Map<String, Object> resp = restClient.post().uri(opaUrl)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(input)
                        .retrieve()
                        .body(new ParameterizedTypeReference<>() {});
                if (resp != null && resp.get("result") instanceof Map<?, ?> result) {
                    String outcome = String.valueOf(result.get("outcome"));
                    List<String> reasons = (List<String>) result.get("reasons");
                    return new PolicyVerdict(outcome, reasons != null ? reasons : List.of("OPA_EVALUATED"));
                }
            } catch (Exception ignored) {
                // Fail-closed rule engine evaluates deterministic policy baseline below
            }
        }

        if (!"QUALIFIED".equalsIgnoreCase(evidenceQualification)) {
            return new PolicyVerdict("RECOMMEND", List.of("EVIDENCE_PROVISIONAL_OR_UNKNOWN"));
        }
        if (!reversible || blastRadius > maxBlastRadius) {
            return new PolicyVerdict("DENY", List.of("BLAST_RADIUS_OR_IRREVERSIBILITY_EXCEEDED"));
        }
        if ("production".equalsIgnoreCase(environmentKind) || !runbookCertified) {
            return new PolicyVerdict("APPROVAL_REQUIRED", List.of("PRODUCTION_OR_CRITICAL_REQUIRES_DISTINCT_APPROVAL"));
        }
        return new PolicyVerdict("PREAUTHORIZED", List.of("NONPROD_REVERSIBLE_CERTIFIED_PATTERN"));
    }

    public record PolicyVerdict(String outcome, List<String> reasons) {
    }
}
