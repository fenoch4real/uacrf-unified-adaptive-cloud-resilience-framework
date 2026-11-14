package org.uacrf.assessment;

import org.uacrf.assessment.AssessmentResponses.GraphReceipt;
import org.uacrf.assessment.AssessmentResponses.MeasurementReceipt;
import org.uacrf.assessment.AssessmentResponses.ObjectiveReceipt;
import org.uacrf.assessment.AssessmentRequests.Measurement;
import java.time.Instant;
import java.util.List;
import java.util.Map;

interface ReceiptVerifier {
  Verification verify(AssessmentResponses.Case item, List<Measurement> measurements, Instant cutoff,
      String tenantId, String environmentId);

  record Verification(String status, String reasonCode, ObjectiveReceipt objectiveReceipt,
      GraphReceipt graphReceipt, List<MeasurementReceipt> measurementReceipts, Map<String, String> sourceFailures) {
    static Verification skipped() { return new Verification("SKIPPED", null, null, null, null, Map.of()); }
    static Verification verified(ObjectiveReceipt objective, GraphReceipt graph, List<MeasurementReceipt> measurements) {
      return new Verification("VERIFIED", null, objective, graph, List.copyOf(measurements), Map.of());
    }
    static Verification failed(String reason, ObjectiveReceipt objective, GraphReceipt graph,
        List<MeasurementReceipt> measurements, Map<String, String> sourceFailures) {
      return new Verification("FAILED", reason, objective, graph, List.copyOf(measurements), Map.copyOf(sourceFailures));
    }
  }
}
