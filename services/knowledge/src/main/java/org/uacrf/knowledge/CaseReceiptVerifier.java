package org.uacrf.knowledge;

import java.util.List;

interface CaseReceiptVerifier {
  boolean enabled();

  Verification verify(String caseId, String purpose, String tenantId, String environmentId);

  record CaseReceipt(
      String caseId, String purpose, String caseDigest, String workloadId, List<String> requiredSourceKinds) {}

  record Verification(CaseReceipt receipt, String reason) {
    static Verification verified(CaseReceipt receipt) {
      return new Verification(receipt, null);
    }

    static Verification failed(String reason) {
      return new Verification(null, reason);
    }

    boolean verified() {
      return receipt != null;
    }
  }
}
