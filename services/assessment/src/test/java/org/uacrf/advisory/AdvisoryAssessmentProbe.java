package org.uacrf.advisory;

import java.io.IOException;
import java.net.URI;
import java.util.List;

public final class AdvisoryAssessmentProbe {
  private AdvisoryAssessmentProbe() {}

  public static String fetchCaseMaterial(URI assessment, String caseId, String caseDigest, String caseToken)
      throws IOException, InterruptedException {
    var expected = new SourceReceiptConsistency.Expected(
        caseId, "retrieval-1", "bc".repeat(32), "assessment", SourceReceiptConsistency.Classification.INTERNAL);
    var receipt = new SourceReceiptConsistency.CaseReceipt(
        caseId, "assessment", caseDigest, "workload-1", List.of("metrics"));
    var client = new CaseMaterialClient(true, assessment, true, (tenant, environment, audience, caps) -> {
      if (!"tenant-advisory".equals(tenant) || !"env-1".equals(environment)
          || !"S07".equals(audience) || !List.of("assessment.case.read").equals(caps)) {
        throw new IOException("Unexpected delegation context or scope.");
      }
      return caseToken;
    });
    return client.fetch("tenant-advisory", "env-1", expected, receipt);
  }

  public static String checkRequesterSources(URI identity, URI assessment, URI knowledge,
                                             String caseId, String manifestDigest)
      throws IOException, InterruptedException {
    var expected = new SourceReceiptConsistency.Expected(
        caseId, "retrieval-1", manifestDigest, "assessment", SourceReceiptConsistency.Classification.INTERNAL);
    var snapshot = new RequesterSourceCheck(true, identity, assessment, knowledge, true)
        .check("requester-token", "tenant-advisory", "env-1", expected);
    if (!"requester".equals(snapshot.requester().id()) || !snapshot.receipts().excerpts().equals(List.of("metrics guidance"))) {
      throw new IllegalStateException("Unexpected requester or knowledge excerpts.");
    }
    return snapshot.caseMaterial();
  }

  public static List<String> fetch(URI assessment, URI knowledge, String caseId,
                                   String manifestDigest, String caseToken)
      throws IOException, InterruptedException {
    var expected = new SourceReceiptConsistency.Expected(
        caseId, "retrieval-1", manifestDigest, "assessment", SourceReceiptConsistency.Classification.INTERNAL);
    var client = new OwnerReceiptClient(true, assessment, knowledge, true, (tenant, environment, audience, caps) -> {
      if (!"tenant-advisory".equals(tenant) || !"env-1".equals(environment)) {
        throw new IOException("Unexpected delegation context.");
      }
      if ("S07".equals(audience) && List.of("assessment.case.read").equals(caps)) return caseToken;
      if ("S08".equals(audience)
          && List.of("knowledge.retrieval.read", "knowledge.classification.internal.read").equals(caps)) {
        return "knowledge-delegation";
      }
      throw new IOException("Unexpected delegation scope.");
    });
    return client.fetch("tenant-advisory", "env-1", expected).excerpts();
  }
}
