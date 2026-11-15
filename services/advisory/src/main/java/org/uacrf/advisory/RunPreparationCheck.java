package org.uacrf.advisory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class RunPreparationCheck {
  private final RequesterSourceCheck sourceCheck;
  private final Clock clock;

  record Snapshot(String tenantId, String environmentId, AdvisoryRunRequest request,
                  SourceReceiptConsistency.Expected expected, ModelConfiguration models,
                  PromptConfiguration prompts, RequesterSourceCheck.Snapshot sources) {}

  record ProfileSnapshot(Snapshot preparation, Map<String, ProfileConfigurationCheck.VersionPins> profiles,
                         Map<String, ProfileConfigurationCheck.RequestLimits> limits,
                         Map<String, InputPacket> packets,
                         Map<String, AdvisoryGateway.Request> gatewayRequests,
                         Map<String, ProfileConfigurationCheck.Selection> selections) {
    ProfileSnapshot {
      profiles = Map.copyOf(profiles);
      limits = Map.copyOf(limits);
      packets = Map.copyOf(packets);
      gatewayRequests = Map.copyOf(gatewayRequests);
      selections = Map.copyOf(selections);
    }

    List<DispatchJournal.Intent> journalIntents(DispatchJournal.RunKey runKey, long generation,
                                                Map<String, String> assignmentIds) {
      Objects.requireNonNull(runKey, "runKey");
      if (generation < 1 || !runKey.tenantId().equals(preparation.tenantId())
          || !runKey.environmentId().equals(preparation.environmentId())) {
        throw new IllegalArgumentException("Journal run identity does not match the prepared request.");
      }
      var assignments = assignmentSnapshot(assignmentIds, preparation.request().budget());
      List<DispatchJournal.Intent> intents = new ArrayList<>();
      for (var allocation : preparation.request().budget().allocations()) {
        String profileId = allocation.agentProfileId();
        var requestLimits = limits.get(profileId);
        var selection = selections.get(profileId);
        var packet = packets.get(profileId);
        var gatewayRequest = gatewayRequests.get(profileId);
        if (requestLimits == null || selection == null || packet == null || gatewayRequest == null
            || !requestLimits.attemptId().equals(gatewayRequest.attemptId())
            || !selection.model().reference().equals(gatewayRequest.model())
            || gatewayRequest.maxOutputTokens() != requestLimits.maxOutputTokens()
            || !gatewayRequest.timeout().equals(requestLimits.timeout())
            || !Arrays.equals(packet.bytes(), gatewayRequest.context().getBytes(StandardCharsets.UTF_8))) {
          throw new IllegalArgumentException("Prepared gateway request does not match its journal packet.");
        }
        intents.add(new DispatchJournal.Intent(new DispatchJournal.Key(
            runKey.tenantId(), runKey.environmentId(), requestLimits.attemptId()), runKey.runId(),
            assignments.get(profileId), generation, allocation.role(), profileId, gatewayRequest,
            packet.bytesDigest(), packet));
      }
      return List.copyOf(intents);
    }

    private static Map<String, String> assignmentSnapshot(Map<String, String> assignmentIds,
                                                           AdvisoryBudget budget) {
      if (assignmentIds == null || assignmentIds.size() != budget.allocations().size()) {
        throw new IllegalArgumentException("Assignment IDs must match the budget allocations exactly.");
      }
      Map<String, String> snapshot = new HashMap<>();
      HashSet<String> unique = new HashSet<>();
      for (var allocation : budget.allocations()) {
        String assignmentId = assignmentIds.get(allocation.agentProfileId());
        if (assignmentId == null || !unique.add(assignmentId)) {
          throw new IllegalArgumentException("Each budget allocation requires a distinct assignment ID.");
        }
        snapshot.put(allocation.agentProfileId(), assignmentId);
      }
      if (!snapshot.keySet().equals(assignmentIds.keySet())) {
        throw new IllegalArgumentException("Assignment IDs must match the budget allocations exactly.");
      }
      return Map.copyOf(snapshot);
    }
  }

  private record Configurations(ModelConfiguration models, PromptConfiguration prompts) {}

  RunPreparationCheck(RequesterSourceCheck sourceCheck) {
    this(sourceCheck, Clock.systemUTC());
  }

  RunPreparationCheck(RequesterSourceCheck sourceCheck, Clock clock) {
    this.sourceCheck = Objects.requireNonNull(sourceCheck, "sourceCheck");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  // Point-in-time consistency only; caller-supplied artifacts do not establish authorization or provenance.
  Snapshot check(String requesterToken, String tenantId, String environmentId, AdvisoryRunRequest request,
                 SourceReceiptConsistency.Expected expected, DigestBoundArtifact modelArtifact,
                 DigestBoundArtifact promptArtifact, Map<String, DigestBoundArtifact> sources)
      throws IOException, InterruptedException {
    requireLiveDeadline(request);
    var configuration = configurations(tenantId, environmentId, request, expected, modelArtifact, promptArtifact);
    var verifiedSources = sourceCheck.checkWithSources(requesterToken, tenantId, environmentId, expected, sources);
    requireLiveDeadline(request);
    return new Snapshot(tenantId, environmentId, request, expected,
        configuration.models(), configuration.prompts(), verifiedSources);
  }

  // S02 receipts bind profile versions; caller-supplied gateway limits remain assertions.
  ProfileSnapshot checkWithProfiles(String requesterToken, String tenantId, String environmentId,
                                    AdvisoryRunRequest request, SourceReceiptConsistency.Expected expected,
                                    DigestBoundArtifact modelArtifact, DigestBoundArtifact promptArtifact,
                                    Map<String, DigestBoundArtifact> sources,
                                    Map<String, ProfileConfigurationCheck.VersionPins> profiles,
                                    Map<String, ProfileConfigurationCheck.RequestLimits> limits)
      throws IOException, InterruptedException {
    requireLiveDeadline(request);
    var configuration = configurations(tenantId, environmentId, request, expected, modelArtifact, promptArtifact);
    var profileSnapshot = ProfileConfigurationCheck.snapshot(profiles);
    var limitsSnapshot = ProfileConfigurationCheck.snapshot(limits);
    ProfileConfigurationCheck.checkAll(request.budget(), configuration.models(),
        configuration.prompts(), profileSnapshot, limitsSnapshot);
    var verifiedSources =
        sourceCheck.checkWithProfiles(
            requesterToken, tenantId, environmentId, expected, sources, profileSnapshot);
    Map<String, ProfileConfigurationCheck.VersionPins> verifiedProfiles = new HashMap<>();
    for (String profileId : profileSnapshot.keySet()) {
      AgentProfileVersionClient.ActiveVersion active = verifiedSources.profileVersions().get(profileId);
      if (active == null) {
        throw new IOException("S02 omitted an active workload version receipt.");
      }
      verifiedProfiles.put(profileId, active.pins());
    }
    var selections =
        ProfileConfigurationCheck.checkAll(
            request.budget(), configuration.models(), configuration.prompts(),
            Map.copyOf(verifiedProfiles), limitsSnapshot);
    requireLiveDeadline(request);
    var preparation = new Snapshot(tenantId, environmentId, request, expected,
        configuration.models(), configuration.prompts(), verifiedSources);
    Map<String, InputPacket> packets = new HashMap<>();
    Map<String, AdvisoryGateway.Request> gatewayRequests = new HashMap<>();
    for (var allocation : request.budget().allocations()) {
      String profileId = allocation.agentProfileId();
      var selected = selections.get(profileId);
      var requestLimits = limitsSnapshot.get(profileId);
      var packet = AdvisoryInputPacket.create(expected, verifiedSources.receipts(),
          verifiedSources.caseMaterial(), selected.prompt());
      packets.put(profileId, packet);
      gatewayRequests.put(profileId, new AdvisoryGateway.Request(requestLimits.attemptId(),
          selected.model().reference(), new String(packet.bytes(), StandardCharsets.UTF_8),
          requestLimits.maxOutputTokens(), requestLimits.timeout()));
    }
    requireLiveDeadline(request);
    return new ProfileSnapshot(preparation, profileSnapshot, limitsSnapshot, packets, gatewayRequests, selections);
  }

  private void requireLiveDeadline(AdvisoryRunRequest request) {
    Objects.requireNonNull(request, "request");
    if (!request.deadline().isAfter(clock.instant())) {
      throw new IllegalArgumentException("Advisory run deadline has expired.");
    }
  }

  private static Configurations configurations(String tenantId, String environmentId, AdvisoryRunRequest request,
                                                SourceReceiptConsistency.Expected expected,
                                                DigestBoundArtifact modelArtifact, DigestBoundArtifact promptArtifact) {
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
    return new Configurations(models, prompts);
  }
}
