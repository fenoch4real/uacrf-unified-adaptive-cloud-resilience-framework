package org.uacrf.assessment;

import org.uacrf.assessment.AssessmentResponses.GraphReceipt;
import org.uacrf.assessment.AssessmentResponses.ObjectiveReceipt;

interface ReceiptVerifier {
  Verification verify(AssessmentResponses.Case item, String tenantId, String environmentId);

  record Verification(String status, String reasonCode, ObjectiveReceipt objectiveReceipt,
      GraphReceipt graphReceipt) {
    static Verification skipped() { return new Verification("SKIPPED", null, null, null); }
    static Verification verified(ObjectiveReceipt objective, GraphReceipt graph) {
      return new Verification("VERIFIED", null, objective, graph);
    }
    static Verification failed(String reason, ObjectiveReceipt objective, GraphReceipt graph) {
      return new Verification("FAILED", reason, objective, graph);
    }
  }
}
