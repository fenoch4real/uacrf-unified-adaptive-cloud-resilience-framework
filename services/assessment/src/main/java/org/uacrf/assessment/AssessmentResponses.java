package org.uacrf.assessment;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import org.uacrf.assessment.AssessmentRequests.*;

final class AssessmentResponses {
  private AssessmentResponses() {}
  record Case(String id, String workloadId, String purpose, String metric, Comparator comparator,
      java.math.BigDecimal threshold, String unit, int windowSeconds, ObjectiveRef objectiveRef,
      GraphRef graphRef, List<String> requiredSourceKinds, String state, long revision, String caseDigest) {}
  record Receipt(
      String caseId, String purpose, String caseDigest, String workloadId, List<String> requiredSourceKinds) {}
  record CaseMaterial(String caseId, String caseDigest, String canonicalText) {}
  record Evidence(Measurement measurement, String disposition) {}
  record ObjectiveReceipt(String workloadId, String setDigest, String objectiveId, long revision, String digest) {}
  record GraphReceipt(String graphId, long revision, String digest) {}
  record MeasurementReceipt(String evidenceId, String purpose, String qualificationState,
      boolean usableAsEvidence, String payloadDigest, long revision, Instant observedAt,
      String sourceKind, String metric, java.math.BigDecimal value, String unit, String reason) {}
  record DependencyVector(String caseDigest, ObjectiveRef objectiveRef, GraphRef graphRef,
      String receiptVerification, String receiptReasonCode, ObjectiveReceipt objectiveReceipt,
      GraphReceipt graphReceipt, List<Evidence> measurements,
      @JsonInclude(JsonInclude.Include.NON_NULL) List<MeasurementReceipt> measurementReceipts) {}
  record Evaluation(String id, String caseId, long revision, Instant observationCutoff, Instant receivedAt,
      Mode mode, String outcome, DependencyVector dependencyVector, String resultDigest) {}
  record Bounds(String metric, Comparator comparator, java.math.BigDecimal threshold, String unit, String effect) {}
  record Prerequisites(String caseDigest, ObjectiveRef objectiveRef, GraphRef graphRef, String evaluationDigest) {}
  record Recommendation(String id, String evaluationId, String caseId, String workloadId,
      String kind, Bounds effectBounds, Prerequisites prerequisiteRefs, boolean executable) {}
  record ModelVersion(String id, String modelId, String version, String artifactRef, String digest,
      String modelCardRef, String evaluationPackRef, String reason, String state, long revision,
      String proposedBy, Instant createdAt, String reviewedBy, String decision,
      List<String> evaluationResultIds, String reviewReason, Instant reviewedAt,
      String promotedBy, String targetMode, String promotionReason, Instant promotedAt) {}
  record ModelEvaluationDeclaration(String id, String modelVersionId, String modelDigest,
      String trainingDataDigest, String holdoutDataDigest, String resultRef, String resultDigest,
      Instant trainingThrough, Instant holdoutFrom, Instant holdoutThrough, String reason,
      String status, String declaredBy, Instant createdAt) {}
  record ModelEvaluationDeclarationPage(List<ModelEvaluationDeclaration> items, String nextCursor) {}
  record Incident(String id, String workloadId, String sourceCaseId, Instant observedFrom,
      String description, List<String> evidenceRefs, String reason, long revision,
      String createdBy, Instant createdAt) {}
  record LearningRecord(String id, String incidentId, String experimentId, List<String> hypotheses,
      List<ActionItem> actionItems, List<String> evidenceRefs, long expectedRevision,
      String reason, String state, long revision, String proposedBy, Instant createdAt) {}
  record Error(String code, String message, String requestId) {}
}
