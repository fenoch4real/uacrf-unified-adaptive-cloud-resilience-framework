package org.uacrf.advisory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AdvisoryRunRequestTests {
  private static final String DIGEST = "a".repeat(64);
  private static final String REFERENCE = "artifact:sha256:" + DIGEST;
  private static final AdvisoryBudget BUDGET = new AdvisoryBudget(1, 100, 5,
      List.of(new AdvisoryBudget.Allocation(AdvisoryBudget.Role.ANALYST, "analyst", 100, 5)));
  private static final String DOCUMENT = """
      {"agent_case_id":"case-1","retrieval_manifest_id":"retrieval-1","workflow_version":"SINGLE_ANALYST_V1",
       "model_config_ref":"%s","model_config_digest":"%s","prompt_ref":"%s","prompt_digest":"%s",
       "max_calls":1,"max_tokens":100,"max_cost_units":5,"deadline":"2030-01-02T03:04:05+02:00",
       "reason":"Run assessment","allocations":[{"role":"ANALYST","agent_profile_id":"analyst",
       "tokens":100,"cost_units":5}],"proposal_ids":["proposal-1"]}
      """.formatted(REFERENCE, DIGEST, REFERENCE, DIGEST);

  @Test
  void parsesRunRequestWithBudgetAndOffsetDeadline() {
    var parsed = AdvisoryRunRequest.parse(DOCUMENT.getBytes(StandardCharsets.UTF_8));
    assertEquals("case-1", parsed.agentCaseId());
    assertEquals("retrieval-1", parsed.retrievalManifestId());
    assertEquals(AdvisoryRunRequest.WorkflowVersion.SINGLE_ANALYST_V1, parsed.workflowVersion());
    assertEquals(BUDGET, parsed.budget());
    assertEquals(Instant.parse("2030-01-02T01:04:05Z"), parsed.deadline());
    assertEquals(List.of("proposal-1"), parsed.proposalIds());
  }

  @Test
  void rejectsMalformedOrAmbiguousJsonAndInvalidContractValues() {
    for (String invalid : List.of(
        "[]",
        DOCUMENT + "{}",
        DOCUMENT.replace("\"agent_case_id\":\"case-1\"",
            "\"agent_case_id\":\"case-1\",\"agent_case_id\":\"case-1\""),
        DOCUMENT.replace("\"agent_case_id\":\"case-1\",", ""),
        DOCUMENT.replace("\"agent_case_id\":", "\"unexpected\":1,\"agent_case_id\":"),
        DOCUMENT.replace("\"role\":\"ANALYST\"", "\"role\":\"ANALYST\",\"extra\":1"),
        DOCUMENT.replace("SINGLE_ANALYST_V1", "UNKNOWN"),
        DOCUMENT.replace("\"role\":\"ANALYST\"", "\"role\":\"UNKNOWN\""),
        DOCUMENT.replace("\"max_calls\":1", "\"max_calls\":1.0"),
        DOCUMENT.replace("\"max_calls\":1", "\"max_calls\":null"),
        DOCUMENT.replace("\"tokens\":100", "\"tokens\":101"),
        DOCUMENT.replace("2030-01-02T03:04:05+02:00", "2030-01-02T03:04:05"),
        DOCUMENT.replace("2030-01-02T03:04:05+02:00", "2030-01-02T03:04:05-00:00"),
        DOCUMENT.replace("2030-01-02T03:04:05+02:00", "2030-01-02T03:04:05+02:00:15"),
        DOCUMENT.replace("2030-01-02T03:04:05+02:00", "2030-01-02T03:04:05.1234567890Z"),
        DOCUMENT.replace("\"proposal-1\"", "123"),
        DOCUMENT.replace("\"proposal-1\"", "\"proposal-1\",\"proposal-1\""),
        DOCUMENT.replace("\"reason\":\"Run assessment\"", "\"reason\":\"\\ud800\""))) {
      assertThrows(IllegalArgumentException.class,
          () -> AdvisoryRunRequest.parse(invalid.getBytes(StandardCharsets.UTF_8)), invalid);
    }
    assertThrows(IllegalArgumentException.class, () -> AdvisoryRunRequest.parse(null));
    assertThrows(IllegalArgumentException.class, () -> AdvisoryRunRequest.parse(new byte[0]));
    assertThrows(IllegalArgumentException.class,
        () -> AdvisoryRunRequest.parse(new byte[] {(byte) 0xc3, (byte) 0x28}));
    assertThrows(IllegalArgumentException.class,
        () -> AdvisoryRunRequest.parse(new byte[InputPacket.MAX_BYTES + 1]));
  }

  @Test
  void retainsValidatedFieldsAndCopiesProposalIds() {
    var proposals = new ArrayList<>(List.of("proposal-1", "proposal-2"));
    var request = request("case-1", "retrieval-1", AdvisoryRunRequest.WorkflowVersion.SINGLE_ANALYST_V1,
        REFERENCE, DIGEST, REFERENCE, DIGEST, Instant.EPOCH, "😀".repeat(2000), proposals);
    proposals.clear();
    assertEquals(List.of("proposal-1", "proposal-2"), request.proposalIds());
    assertThrows(UnsupportedOperationException.class, () -> request.proposalIds().clear());
  }

  @Test
  void rejectsInvalidScopeReferencesAndRequiredFields() {
    assertThrows(IllegalArgumentException.class,
        () -> request("bad case", "retrieval-1", AdvisoryRunRequest.WorkflowVersion.SINGLE_ANALYST_V1,
            REFERENCE, DIGEST, REFERENCE, DIGEST, Instant.EPOCH, "reason", List.of("proposal")));
    assertThrows(IllegalArgumentException.class,
        () -> request("case-1", "retrieval-1", null,
            REFERENCE, DIGEST, REFERENCE, DIGEST, Instant.EPOCH, "reason", List.of("proposal")));
    assertThrows(IllegalArgumentException.class,
        () -> request("case-1", "retrieval-1", AdvisoryRunRequest.WorkflowVersion.SINGLE_ANALYST_V1,
            REFERENCE, "b".repeat(64), REFERENCE, DIGEST, Instant.EPOCH, "reason", List.of("proposal")));
    assertThrows(IllegalArgumentException.class,
        () -> request("case-1", "retrieval-1", AdvisoryRunRequest.WorkflowVersion.SINGLE_ANALYST_V1,
            REFERENCE, DIGEST, REFERENCE, "A".repeat(64), Instant.EPOCH, "reason", List.of("proposal")));
    assertThrows(IllegalArgumentException.class,
        () -> request("case-1", "retrieval-1", AdvisoryRunRequest.WorkflowVersion.SINGLE_ANALYST_V1,
            REFERENCE, DIGEST, REFERENCE, DIGEST, null, "reason", List.of("proposal")));
  }

  @Test
  void rejectsInvalidReasonAndProposalLists() {
    for (String reason : List.of("  ", "😀".repeat(2001), "\ud800")) {
      assertThrows(IllegalArgumentException.class,
          () -> request("case-1", "retrieval-1", AdvisoryRunRequest.WorkflowVersion.SINGLE_ANALYST_V1,
              REFERENCE, DIGEST, REFERENCE, DIGEST, Instant.EPOCH, reason, List.of("proposal")));
    }
    for (List<String> proposals : List.of(List.<String>of(), List.of("proposal", "proposal"),
        List.of("bad proposal"), java.util.stream.IntStream.range(0, 11).mapToObj(i -> "p" + i).toList())) {
      assertThrows(IllegalArgumentException.class,
          () -> request("case-1", "retrieval-1", AdvisoryRunRequest.WorkflowVersion.SINGLE_ANALYST_V1,
              REFERENCE, DIGEST, REFERENCE, DIGEST, Instant.EPOCH, "reason", proposals));
    }
    var ten = java.util.stream.IntStream.range(0, 10).mapToObj(i -> "p" + i).toList();
    assertEquals(ten, request("case-1", "retrieval-1", AdvisoryRunRequest.WorkflowVersion.ADVERSARIAL_REVIEW_V1,
        REFERENCE, DIGEST, REFERENCE, DIGEST, Instant.EPOCH, "reason", ten).proposalIds());
  }

  private static AdvisoryRunRequest request(String caseId, String retrievalId,
                                             AdvisoryRunRequest.WorkflowVersion workflow,
                                             String modelReference, String modelDigest,
                                             String promptReference, String promptDigest,
                                             Instant deadline, String reason, List<String> proposals) {
    return new AdvisoryRunRequest(caseId, retrievalId, workflow, modelReference, modelDigest,
        promptReference, promptDigest, BUDGET, deadline, reason, proposals);
  }
}
