package io.uacrf.policy.service;

import io.uacrf.contracts.CanonicalEnums.GraphDeltaClassification;
import org.springframework.stereotype.Component;

/**
 * P2 Material Graph Delta Classifier: compares approved proposal graph state against live graph state
 * immediately prior to bounded authorization issuance / dispatch.
 */
@Component
public class MaterialGraphDeltaClassifier {

    public GraphDeltaClassification classify(String expectedRevision, String liveRevision,
                                             String expectedSubgraphDigest, String liveSubgraphDigest,
                                             int expectedBlastRadius, int liveBlastRadius,
                                             boolean fallbackChanged) {
        if (liveRevision == null || liveRevision.isBlank() || liveSubgraphDigest == null || liveSubgraphDigest.isBlank()) {
            return GraphDeltaClassification.INDETERMINATE;
        }
        if (fallbackChanged) {
            return GraphDeltaClassification.FALLBACK_CHANGING;
        }
        if (expectedRevision.equals(liveRevision) && expectedSubgraphDigest.equals(liveSubgraphDigest)) {
            return GraphDeltaClassification.IMMATERIAL;
        }
        if (liveBlastRadius > expectedBlastRadius) {
            return GraphDeltaClassification.WIDENING;
        }
        if (!expectedSubgraphDigest.equals(liveSubgraphDigest) && liveBlastRadius <= expectedBlastRadius) {
            return GraphDeltaClassification.TARGET_CHANGING;
        }
        return GraphDeltaClassification.NARROWING;
    }

    public boolean permitsAuthorization(GraphDeltaClassification classification) {
        return classification == GraphDeltaClassification.IMMATERIAL
                || classification == GraphDeltaClassification.NARROWING;
    }
}
