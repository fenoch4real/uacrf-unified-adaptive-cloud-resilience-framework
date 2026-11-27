package io.uacrf.assessment.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Correlation-Domain Taxonomy Engine (P5): compares primary and alternate recovery paths across
 * independent failure domains (REGION, ZONE, CLUSTER, IDP, KMS_KEY, DNS, DATA_STORE, RELEASE, VENDOR).
 */
@Component
public class PathIndependenceCertifier {

    private static final Set<String> CRITICAL_CORRELATION_DOMAINS = Set.of(
            "REGION", "IDP", "KMS_KEY", "DNS", "DATA_STORE"
    );

    public CertificationOutcome certify(Map<String, String> primaryDomains,
                                        Map<String, String> alternateDomains) {
        if (primaryDomains.isEmpty() || alternateDomains.isEmpty()) {
            return new CertificationOutcome("UNKNOWN", List.of(),
                    "Incomplete correlation-domain telemetry; independence cannot be established.");
        }

        List<String> shared = new ArrayList<>();
        boolean sharedCritical = false;

        for (Map.Entry<String, String> entry : primaryDomains.entrySet()) {
            String domainKey = entry.getKey().toUpperCase();
            String primaryVal = entry.getValue();
            String altVal = alternateDomains.get(entry.getKey());
            if (altVal == null) {
                altVal = alternateDomains.get(domainKey);
            }
            if (primaryVal != null && primaryVal.equalsIgnoreCase(altVal)) {
                shared.add(domainKey);
                if (CRITICAL_CORRELATION_DOMAINS.contains(domainKey)) {
                    sharedCritical = true;
                }
            }
        }

        if (shared.isEmpty()) {
            return new CertificationOutcome("CERTIFIED", List.of(),
                    "Primary and alternate paths share zero inspected correlation domains.");
        }
        if (sharedCritical) {
            return new CertificationOutcome("NOT_INDEPENDENT", shared,
                    "Shared critical blast-radius domain(s): " + String.join(", ", shared)
                            + ". Failure of these shared dependencies can defeat alternate path failover.");
        }
        return new CertificationOutcome("CONDITIONALLY_CERTIFIED", shared,
                "Paths are separated on critical control planes, but share non-critical domain(s): "
                        + String.join(", ", shared));
    }

    public record CertificationOutcome(
            String status,
            List<String> sharedDomains,
            String witnessSummary
    ) {
    }
}
