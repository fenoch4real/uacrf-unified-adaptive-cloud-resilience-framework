package org.uacrf.objectives;

import java.math.BigDecimal;
import java.util.List;

final class ObjectivesResponses {
  private ObjectivesResponses() {}

  record Page<T>(List<T> items, String nextCursor) {}

  record Objective(
      String id,
      String workloadId,
      String title,
      String metric,
      String comparator,
      BigDecimal target,
      String unit,
      int windowSeconds,
      String origin,
      String sourceRef,
      String sourceDefinitionId,
      String sourceRevision,
      String sourceVerifiedAt,
      String contentDigest,
      String state,
      long revision,
      String proposedBy,
      String decidedBy,
      String decisionReason,
      String activatedBy) {}

  record Constraint(String metric, String unit, BigDecimal minTarget, BigDecimal maxTarget) {}

  record ActiveObjective(
      String id, String metric, String comparator, BigDecimal target, String unit, int windowSeconds,
      long revision, String contentDigest) {}

  record ObjectiveReceipt(
      String workloadId, String purpose, List<ActiveObjective> objectives, String setDigest) {}

  record Error(String code, String message, String requestId) {}
}
