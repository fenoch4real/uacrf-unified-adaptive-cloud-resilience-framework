package io.uacrf.advisory.service;

import io.uacrf.advisory.api.AdvisoryDtos.AgentProposalInput;
import io.uacrf.advisory.api.AdvisoryDtos.ArbitratedOutcome;
import io.uacrf.crypto.CanonicalJson;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Deterministic Non-Majority Arbitrator (Patent P7):
 * Produces order-invariant arbitration regardless of proposal arrival order and NEVER
 * suppresses blocking dissent from any critic/adversarial agent.
 */
@Component
public class DeterministicArbiter {

    public ArbitratedOutcome arbitrate(List<AgentProposalInput> proposals) {
        if (proposals == null || proposals.isEmpty()) {
            return new ArbitratedOutcome("NO_ADVISORY_PROPOSAL", true, false, List.of(), CanonicalJson.digestObject(List.of()));
        }

        // Canonical sort ensures permutation invariance
        List<AgentProposalInput> ordered = proposals.stream()
                .sorted(Comparator.comparing(AgentProposalInput::agentId)
                        .thenComparing(AgentProposalInput::role))
                .toList();

        List<String> dissentingIds = ordered.stream()
                .filter(AgentProposalInput::hasBlockingDissent)
                .map(AgentProposalInput::agentId)
                .toList();

        String topRec = ordered.stream()
                .max(Comparator.comparingDouble(AgentProposalInput::confidence)
                        .thenComparing(AgentProposalInput::agentId))
                .map(AgentProposalInput::actionRecommendation)
                .orElse("MANUAL_CORE_REVIEW");

        String digest = CanonicalJson.digestObject(ordered);
        return new ArbitratedOutcome(topRec, true, false, dissentingIds, digest);
    }
}
