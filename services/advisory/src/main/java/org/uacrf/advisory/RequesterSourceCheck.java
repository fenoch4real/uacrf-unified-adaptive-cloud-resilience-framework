package org.uacrf.advisory;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class RequesterSourceCheck {
  private static final String INTERNAL = "knowledge.classification.internal.read";
  private static final String RESTRICTED = "knowledge.classification.restricted.read";
  private static final String RETRIEVAL_READ = "knowledge.retrieval.read";

  private final boolean enabled;
  private final URI identityBase;
  private final URI assessmentBase;
  private final URI knowledgeBase;
  private final URI catalogBase;
  private final boolean allowLoopbackHttp;

  record Snapshot(RequesterAccessClient.Principal requester, OwnerReceiptClient.Receipts receipts,
                  String caseMaterial,
                  Map<String, AgentProfileVersionClient.ActiveVersion> profileVersions) {
    Snapshot {
      profileVersions = Map.copyOf(profileVersions);
    }
  }

  RequesterSourceCheck(boolean enabled, URI identityBase, URI assessmentBase, URI knowledgeBase,
                       boolean allowLoopbackHttp) {
    this(enabled, identityBase, assessmentBase, knowledgeBase, null, allowLoopbackHttp);
  }

  RequesterSourceCheck(
      boolean enabled,
      URI identityBase,
      URI assessmentBase,
      URI knowledgeBase,
      URI catalogBase,
      boolean allowLoopbackHttp) {
    this.enabled = enabled;
    this.identityBase = InternalJsonClient.validateBase(identityBase, allowLoopbackHttp);
    this.assessmentBase = InternalJsonClient.validateBase(assessmentBase, allowLoopbackHttp);
    this.knowledgeBase = InternalJsonClient.validateBase(knowledgeBase, allowLoopbackHttp);
    this.catalogBase =
        catalogBase == null ? null : InternalJsonClient.validateBase(catalogBase, allowLoopbackHttp);
    this.allowLoopbackHttp = allowLoopbackHttp;
  }

  // This is a point-in-time prerequisite, not authorization to admit or dispatch a run.
  Snapshot check(String requesterToken, String tenantId, String environmentId,
                 SourceReceiptConsistency.Expected expected) throws IOException, InterruptedException {
    return check(requesterToken, tenantId, environmentId, expected, null, null);
  }

  // Supplied source bytes prove integrity only, not ownership or permission to disclose them.
  Snapshot checkWithSources(String requesterToken, String tenantId, String environmentId,
                            SourceReceiptConsistency.Expected expected, Map<String, DigestBoundArtifact> sources)
      throws IOException, InterruptedException {
    return checkWithSources(requesterToken, tenantId, environmentId, expected, sources, null);
  }

  Snapshot checkWithProfiles(
      String requesterToken,
      String tenantId,
      String environmentId,
      SourceReceiptConsistency.Expected expected,
      Map<String, DigestBoundArtifact> sources,
      Map<String, ProfileConfigurationCheck.VersionPins> profiles)
      throws IOException, InterruptedException {
    if (catalogBase == null) {
      throw new IllegalStateException("S02 profile version checking is not configured.");
    }
    return checkWithSources(requesterToken, tenantId, environmentId, expected, sources, profiles);
  }

  private Snapshot checkWithSources(
      String requesterToken,
      String tenantId,
      String environmentId,
      SourceReceiptConsistency.Expected expected,
      Map<String, DigestBoundArtifact> sources,
      Map<String, ProfileConfigurationCheck.VersionPins> profiles)
      throws IOException, InterruptedException {
    if (!enabled) {
      throw new IllegalStateException("Requester source checking is disabled.");
    }
    if (sources == null || sources.isEmpty() || sources.size() > 10) {
      throw new IllegalArgumentException("One to ten digest-keyed knowledge sources are required.");
    }
    Map<String, DigestBoundArtifact> snapshot = new HashMap<>();
    for (var entry : sources.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null
          || !entry.getKey().equals(entry.getValue().digest()) || snapshot.size() >= 10) {
        throw new IllegalArgumentException("One to ten digest-keyed knowledge sources are required.");
      }
      snapshot.put(entry.getKey(), entry.getValue());
    }
    if (snapshot.isEmpty()) {
      throw new IllegalArgumentException("One to ten digest-keyed knowledge sources are required.");
    }
    Map<String, ProfileConfigurationCheck.VersionPins> profileSnapshot =
        profiles == null ? null : ProfileConfigurationCheck.snapshot(profiles);
    return check(
        requesterToken,
        tenantId,
        environmentId,
        expected,
        Map.copyOf(snapshot),
        profileSnapshot);
  }

  private Snapshot check(String requesterToken, String tenantId, String environmentId,
                         SourceReceiptConsistency.Expected expected,
                         Map<String, DigestBoundArtifact> sources,
                         Map<String, ProfileConfigurationCheck.VersionPins> expectedProfiles)
      throws IOException, InterruptedException {
    if (!enabled) {
      throw new IllegalStateException("Requester source checking is disabled.");
    }
    SourceReceiptConsistency.validateExpected(expected);
    var http = new InternalJsonClient(Duration.ofSeconds(5));
    var access = new RequesterAccessClient(true, identityBase, allowLoopbackHttp, http);
    var requester =
        access.check(
            requesterToken,
            tenantId,
            environmentId,
            expected.classification(),
            expectedProfiles != null);
    var s01 = new S01DelegationClient(true, identityBase, allowLoopbackHttp, () -> requesterToken, http);
    OwnerReceiptClient.DelegationProvider delegations = (tenant, environment, audience, capabilities) -> {
      if ("S08".equals(audience) && List.of(RETRIEVAL_READ, INTERNAL).equals(capabilities)
          && !requester.capabilities().contains(INTERNAL)
          && requester.capabilities().contains(RESTRICTED)) {
        return s01.delegation(tenant, environment, audience, List.of(RETRIEVAL_READ, RESTRICTED));
      }
      return s01.delegation(tenant, environment, audience, capabilities);
    };
    var owners = new OwnerReceiptClient(true, assessmentBase, knowledgeBase, allowLoopbackHttp, delegations, http);
    var caseReceipt = owners.fetchCase(tenantId, environmentId, expected);
    var caseMaterial = new CaseMaterialClient(true, assessmentBase, allowLoopbackHttp, delegations, http)
        .fetch(tenantId, environmentId, expected, caseReceipt);
    var receipts = owners.fetchRetrieval(tenantId, environmentId, expected, caseReceipt, sources);
    Map<String, AgentProfileVersionClient.ActiveVersion> profileVersions = Map.of();
    if (expectedProfiles != null) {
      profileVersions =
          new AgentProfileVersionClient(catalogBase, allowLoopbackHttp, s01, http)
              .fetchActive(tenantId, environmentId, expectedProfiles);
    }
    var refreshed =
        access.check(
            requesterToken,
            tenantId,
            environmentId,
            expected.classification(),
            expectedProfiles != null);
    if (!requester.id().equals(refreshed.id())) {
      throw new IOException("Requester identity changed during source checking.");
    }
    http.requireBudget();
    return new Snapshot(refreshed, receipts, caseMaterial, profileVersions);
  }
}
