package org.uacrf.advisory;

import java.io.IOException;
import java.net.URI;
import java.util.List;

final class RequesterSourceCheck {
  private static final String INTERNAL = "knowledge.classification.internal.read";
  private static final String RESTRICTED = "knowledge.classification.restricted.read";
  private static final String RETRIEVAL_READ = "knowledge.retrieval.read";

  private final boolean enabled;
  private final URI identityBase;
  private final URI assessmentBase;
  private final URI knowledgeBase;
  private final boolean allowLoopbackHttp;
  private final RequesterAccessClient access;

  record Snapshot(RequesterAccessClient.Principal requester, OwnerReceiptClient.Receipts receipts) {}

  RequesterSourceCheck(boolean enabled, URI identityBase, URI assessmentBase, URI knowledgeBase,
                       boolean allowLoopbackHttp) {
    this.enabled = enabled;
    this.identityBase = InternalJsonClient.validateBase(identityBase, allowLoopbackHttp);
    this.assessmentBase = InternalJsonClient.validateBase(assessmentBase, allowLoopbackHttp);
    this.knowledgeBase = InternalJsonClient.validateBase(knowledgeBase, allowLoopbackHttp);
    this.allowLoopbackHttp = allowLoopbackHttp;
    this.access = new RequesterAccessClient(enabled, identityBase, allowLoopbackHttp);
  }

  // This is a point-in-time prerequisite, not authorization to admit or dispatch a run.
  Snapshot check(String requesterToken, String tenantId, String environmentId,
                 SourceReceiptConsistency.Expected expected) throws IOException, InterruptedException {
    if (!enabled) {
      throw new IllegalStateException("Requester source checking is disabled.");
    }
    SourceReceiptConsistency.validateExpected(expected);
    var requester = access.check(requesterToken, tenantId, environmentId, expected.classification());
    var s01 = new S01DelegationClient(true, identityBase, allowLoopbackHttp, () -> requesterToken);
    OwnerReceiptClient.DelegationProvider delegations = (tenant, environment, audience, capabilities) -> {
      if ("S08".equals(audience) && List.of(RETRIEVAL_READ, INTERNAL).equals(capabilities)
          && !requester.capabilities().contains(INTERNAL)
          && requester.capabilities().contains(RESTRICTED)) {
        return s01.delegation(tenant, environment, audience, List.of(RETRIEVAL_READ, RESTRICTED));
      }
      return s01.delegation(tenant, environment, audience, capabilities);
    };
    var owners = new OwnerReceiptClient(true, assessmentBase, knowledgeBase, allowLoopbackHttp, delegations);
    return new Snapshot(requester, owners.fetch(tenantId, environmentId, expected));
  }
}
