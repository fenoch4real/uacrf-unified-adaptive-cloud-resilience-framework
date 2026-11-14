package org.uacrf.assessment;

import java.time.Instant;
import java.util.List;
import org.uacrf.assessment.AssessmentRequests.*;

final class AssessmentResponses {
  private AssessmentResponses() {}
  record Case(String id, String workloadId, String purpose, String metric, Comparator comparator,
      java.math.BigDecimal threshold, String unit, int windowSeconds, ObjectiveRef objectiveRef,
      GraphRef graphRef, List<String> requiredSourceKinds, String state, long revision, String caseDigest) {}
  record Receipt(String caseId, String purpose, String caseDigest, List<String> requiredSourceKinds) {}
  record Evidence(Measurement measurement, String disposition) {}
  record DependencyVector(String caseDigest, ObjectiveRef objectiveRef, GraphRef graphRef,
      List<Evidence> measurements) {}
  record Evaluation(String id, String caseId, long revision, Instant observationCutoff, Instant receivedAt,
      Mode mode, String outcome, DependencyVector dependencyVector, String resultDigest) {}
  record Bounds(String metric, Comparator comparator, java.math.BigDecimal threshold, String unit, String effect) {}
  record Prerequisites(String caseDigest, ObjectiveRef objectiveRef, GraphRef graphRef, String evaluationDigest) {}
  record Recommendation(String id, String evaluationId, String caseId, String workloadId,
      String kind, Bounds effectBounds, Prerequisites prerequisiteRefs, boolean executable) {}
  record Error(String code, String message, String requestId) {}
}
