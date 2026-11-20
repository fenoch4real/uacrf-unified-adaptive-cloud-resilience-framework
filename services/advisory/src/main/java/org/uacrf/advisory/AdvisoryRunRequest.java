package org.uacrf.advisory;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// Shape and consistency only: this does not authorize or admit a run.
record AdvisoryRunRequest(String agentCaseId, String retrievalManifestId, WorkflowVersion workflowVersion,
                          String modelConfigReference, String modelConfigDigest,
                          String promptReference, String promptDigest, AdvisoryBudget budget,
                          Instant deadline, String reason, List<String> proposalIds) {
  private static final Set<String> FIELDS = Set.of("agent_case_id", "retrieval_manifest_id", "workflow_version",
      "model_config_ref", "model_config_digest", "prompt_ref", "prompt_digest", "max_calls", "max_tokens",
      "max_cost_units", "deadline", "reason", "allocations", "proposal_ids");
  private static final Set<String> ALLOCATION_FIELDS = Set.of("role", "agent_profile_id", "tokens", "cost_units");
  private static final ObjectMapper JSON = JsonMapper.builder().build();

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
    requireWorkflowRoles(workflowVersion, budget);
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

  String requestDigest() {
    return Sha256.hex(JSON.writeValueAsBytes(canonicalRequest()));
  }

  private Map<String, Object> canonicalRequest() {
    Map<String, Object> canonical = new LinkedHashMap<>();
    canonical.put("agent_case_id", agentCaseId);
    canonical.put("retrieval_manifest_id", retrievalManifestId);
    canonical.put("workflow_version", workflowVersion.name());
    canonical.put("model_config_ref", modelConfigReference);
    canonical.put("model_config_digest", modelConfigDigest);
    canonical.put("prompt_ref", promptReference);
    canonical.put("prompt_digest", promptDigest);
    canonical.put("max_calls", budget.maxCalls());
    canonical.put("max_tokens", budget.maxTokens());
    canonical.put("max_cost_units", budget.maxCostUnits());
    canonical.put("deadline", deadline.toString());
    canonical.put("reason", reason);
    List<Map<String, Object>> canonicalAllocations = new ArrayList<>();
    for (AdvisoryBudget.Allocation allocation : budget.allocations()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("role", allocation.role().name());
      item.put("agent_profile_id", allocation.agentProfileId());
      item.put("tokens", allocation.tokens());
      item.put("cost_units", allocation.costUnits());
      canonicalAllocations.add(item);
    }
    canonical.put("allocations", canonicalAllocations);
    canonical.put("proposal_ids", proposalIds);
    return canonical;
  }

  private static void requireWorkflowRoles(WorkflowVersion workflowVersion, AdvisoryBudget budget) {
    Set<AdvisoryBudget.Role> required = switch (workflowVersion) {
      case SINGLE_ANALYST_V1 -> Set.of(AdvisoryBudget.Role.ANALYST);
      case INDEPENDENT_REVIEW_V1 -> Set.of(AdvisoryBudget.Role.ANALYST, AdvisoryBudget.Role.REVIEWER);
      case ADVERSARIAL_REVIEW_V1 ->
          Set.of(AdvisoryBudget.Role.ANALYST, AdvisoryBudget.Role.REVIEWER, AdvisoryBudget.Role.CRITIC);
    };
    Set<AdvisoryBudget.Role> supplied = new HashSet<>();
    for (AdvisoryBudget.Allocation allocation : budget.allocations()) {
      supplied.add(allocation.role());
    }
    if (!required.equals(supplied)) {
      throw new IllegalArgumentException("Allocations do not match the workflow's required roles.");
    }
  }

  private static boolean matchesReference(String reference, String digest) {
    return digest != null && digest.matches("[a-f0-9]{64}") && ("artifact:sha256:" + digest).equals(reference);
  }
}
