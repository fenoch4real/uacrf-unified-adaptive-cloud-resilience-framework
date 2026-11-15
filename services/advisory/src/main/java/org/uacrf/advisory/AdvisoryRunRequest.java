package org.uacrf.advisory;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Shape and consistency only: this does not authorize or admit a run.
record AdvisoryRunRequest(String agentCaseId, String retrievalManifestId, WorkflowVersion workflowVersion,
                          String modelConfigReference, String modelConfigDigest,
                          String promptReference, String promptDigest, AdvisoryBudget budget,
                          Instant deadline, String reason, List<String> proposalIds) {
  private static final Set<String> FIELDS = Set.of("agent_case_id", "retrieval_manifest_id", "workflow_version",
      "model_config_ref", "model_config_digest", "prompt_ref", "prompt_digest", "max_calls", "max_tokens",
      "max_cost_units", "deadline", "reason", "allocations", "proposal_ids");
  private static final Set<String> ALLOCATION_FIELDS = Set.of("role", "agent_profile_id", "tokens", "cost_units");

  enum WorkflowVersion {
    SINGLE_ANALYST_V1, INDEPENDENT_REVIEW_V1, ADVERSARIAL_REVIEW_V1
  }

  static AdvisoryRunRequest parse(byte[] bytes) {
    var document = StrictBoundedJson.readObject(bytes, "Run request");
    if (!document.keySet().equals(FIELDS)) {
      throw new IllegalArgumentException("Invalid run request fields.");
    }
    WorkflowVersion workflow;
    try {
      workflow = WorkflowVersion.valueOf(text(document, "workflow_version"));
    } catch (IllegalArgumentException failure) {
      throw new IllegalArgumentException("Invalid advisory workflow version.", failure);
    }
    Instant deadline;
    String deadlineText = text(document, "deadline");
    if (!deadlineText.matches("(?i)[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}"
        + "(\\.[0-9]{1,9})?(Z|[+-][0-9]{2}:[0-9]{2})") || deadlineText.endsWith("-00:00")) {
      throw new IllegalArgumentException("Invalid advisory deadline.");
    }
    try {
      deadline = OffsetDateTime.parse(deadlineText).toInstant();
    } catch (DateTimeParseException failure) {
      throw new IllegalArgumentException("Invalid advisory deadline.", failure);
    }
    if (!(document.get("allocations") instanceof List<?> entries) || entries.isEmpty() || entries.size() > 3) {
      throw new IllegalArgumentException("One to three allocations are required.");
    }
    var allocations = new ArrayList<AdvisoryBudget.Allocation>();
    for (Object entry : entries) {
      if (!(entry instanceof Map<?, ?> allocation) || !allocation.keySet().equals(ALLOCATION_FIELDS)) {
        throw new IllegalArgumentException("Invalid advisory allocation fields.");
      }
      AdvisoryBudget.Role role;
      try {
        role = AdvisoryBudget.Role.valueOf(text(allocation, "role"));
      } catch (IllegalArgumentException failure) {
        throw new IllegalArgumentException("Invalid advisory allocation role.", failure);
      }
      allocations.add(new AdvisoryBudget.Allocation(role, text(allocation, "agent_profile_id"),
          integer(allocation, "tokens"), integer(allocation, "cost_units")));
    }
    if (!(document.get("proposal_ids") instanceof List<?> entriesForProposals)
        || entriesForProposals.isEmpty() || entriesForProposals.size() > 10) {
      throw new IllegalArgumentException("One to ten proposal IDs are required.");
    }
    var proposals = new ArrayList<String>();
    for (Object entry : entriesForProposals) {
      if (!(entry instanceof String proposal)) {
        throw new IllegalArgumentException("Proposal IDs must be strings.");
      }
      proposals.add(proposal);
    }
    var budget = new AdvisoryBudget(integer(document, "max_calls"), integer(document, "max_tokens"),
        integer(document, "max_cost_units"), allocations);
    return new AdvisoryRunRequest(text(document, "agent_case_id"), text(document, "retrieval_manifest_id"), workflow,
        text(document, "model_config_ref"), text(document, "model_config_digest"),
        text(document, "prompt_ref"), text(document, "prompt_digest"), budget, deadline, text(document, "reason"),
        proposals);
  }

  private static String text(Map<?, ?> object, String field) {
    if (!(object.get(field) instanceof String value)) {
      throw new IllegalArgumentException("Invalid run request text field: " + field);
    }
    return value;
  }

  private static int integer(Map<?, ?> object, String field) {
    if (!(object.get(field) instanceof Integer value)) {
      throw new IllegalArgumentException("Invalid run request integer field: " + field);
    }
    return value;
  }

  AdvisoryRunRequest {
    InternalJsonClient.requireId(agentCaseId);
    InternalJsonClient.requireId(retrievalManifestId);
    if (workflowVersion == null || budget == null || deadline == null
        || !matchesReference(modelConfigReference, modelConfigDigest)
        || !matchesReference(promptReference, promptDigest)) {
      throw new IllegalArgumentException("Invalid advisory run configuration.");
    }
    if (reason == null || reason.isBlank() || !UnicodeText.isValid(reason)
        || reason.codePointCount(0, reason.length()) > 2000) {
      throw new IllegalArgumentException("Invalid advisory run reason.");
    }
    if (proposalIds == null || proposalIds.isEmpty() || proposalIds.size() > 10) {
      throw new IllegalArgumentException("One to ten proposal IDs are required.");
    }
    var unique = new HashSet<String>();
    var snapshot = new ArrayList<String>();
    for (String id : proposalIds) {
      InternalJsonClient.requireId(id);
      if (snapshot.size() >= 10 || !unique.add(id)) {
        throw new IllegalArgumentException("Proposal IDs must be unique and bounded.");
      }
      snapshot.add(id);
    }
    if (snapshot.isEmpty()) {
      throw new IllegalArgumentException("One to ten proposal IDs are required.");
    }
    proposalIds = List.copyOf(snapshot);
  }

  private static boolean matchesReference(String reference, String digest) {
    return digest != null && digest.matches("[a-f0-9]{64}") && ("artifact:sha256:" + digest).equals(reference);
  }
}
