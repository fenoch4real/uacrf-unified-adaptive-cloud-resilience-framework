package org.uacrf.advisory;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

final class RunPreparationCheck {
  private final RequesterSourceCheck sourceCheck;

  record Snapshot(String tenantId, String environmentId, AdvisoryRunRequest request,
                  SourceReceiptConsistency.Expected expected, ModelConfiguration models,
                  PromptConfiguration prompts, RequesterSourceCheck.Snapshot sources) {}

  RunPreparationCheck(RequesterSourceCheck sourceCheck) {
    this.sourceCheck = Objects.requireNonNull(sourceCheck, "sourceCheck");
  }

  // Point-in-time consistency only; caller-supplied artifacts do not establish authorization or provenance.
  Snapshot check(String requesterToken, String tenantId, String environmentId, AdvisoryRunRequest request,
                 SourceReceiptConsistency.Expected expected, DigestBoundArtifact modelArtifact,
                 DigestBoundArtifact promptArtifact, Map<String, DigestBoundArtifact> sources)
      throws IOException, InterruptedException {
    Objects.requireNonNull(request, "request");
    SourceReceiptConsistency.validateExpected(expected);
    if (!request.agentCaseId().equals(expected.caseId())
        || !request.retrievalManifestId().equals(expected.retrievalId())) {
      throw new IllegalArgumentException("Run request does not match the expected advisory sources.");
    }
    var models = ModelConfiguration.parse(tenantId, environmentId, request.modelConfigReference(),
        request.modelConfigDigest(), modelArtifact);
    var prompts = PromptConfiguration.parse(tenantId, environmentId, request.promptReference(),
        request.promptDigest(), promptArtifact);
    var verifiedSources = sourceCheck.checkWithSources(requesterToken, tenantId, environmentId, expected, sources);
    return new Snapshot(tenantId, environmentId, request, expected, models, prompts, verifiedSources);
  }
}
