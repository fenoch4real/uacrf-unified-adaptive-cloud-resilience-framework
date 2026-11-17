package org.uacrf.decisiongovernance;

import java.time.Instant;
import java.util.List;

final class PolicyResponses {
  static final String AUTHORITY = "SIGNED_POLICY_CONFIGURATION_NOT_ACTION_AUTHORIZATION";

  private PolicyResponses() {}

  record Material(String algorithm, String encoding, String canonicalText, String digest) {}

  record Policy(
      String id,
      long revision,
      String serviceId,
      Instant expiresAt,
      PolicyRequests.Definition definition,
      String digest,
      String proposedBy,
      String proposalReason,
      Instant proposedAt,
      String decision,
      String reviewerId,
      String reviewReason,
      Instant reviewedAt,
      boolean currentEligible,
      Material material,
      String authority,
      Instant effectiveFrom,
      String policyVersionId,
      String trustDigest,
      Long trustRevision,
      String assertionDigest,
      String signedAssertion,
      Review review) {}

  record Vote(
      String reviewerId,
      String decision,
      String reason,
      Instant reviewedAt,
      String keyId,
      Long trustRevision,
      String trustDigest,
      String assertionDigest,
      boolean currentlyTrusted) {}

  record Review(
      int requiredApprovalCount,
      int approvalCount,
      int validApprovalCount,
      int rejectionCount,
      List<Vote> votes) {}

  record Decided(
      String decision,
      String reviewerId,
      String reviewReason,
      Instant reviewedAt,
      boolean currentEligible,
      Long trustRevision,
      String trustDigest,
      String assertionDigest,
      String signedAssertion,
      Review review) {
    static Decided draft() {
      return new Decided(
          "DRAFT", null, null, null, false, null, null, null, null, new Review(0, 0, 0, 0, List.of()));
    }
  }

  record ProposalResult(Policy policy, String receiptId) {}

  record PolicySummary(
      String id,
      long revision,
      String serviceId,
      Instant expiresAt,
      String digest,
      String proposedBy,
      String proposalReason,
      Instant proposedAt,
      String decision,
      String reviewerId,
      String reviewReason,
      Instant reviewedAt,
      boolean currentEligible,
      String authority,
      Instant effectiveFrom,
      String policyVersionId,
      String trustDigest,
      Long trustRevision,
      String assertionDigest,
      int approvalCount,
      int validApprovalCount,
      int rejectionCount) {}

  record PolicyPage(List<PolicySummary> items, String nextCursor, String coverage) {}

  record PolicyRow(
      String id,
      long revision,
      String serviceId,
      Instant expiresAt,
      PolicyRequests.Definition definition,
      String digest,
      String proposedBy,
      String proposalReason,
      Instant proposedAt,
      Instant effectiveFrom,
      String canonicalText) {
    Policy policy() {
      return policy(Decided.draft());
    }

    Policy policy(Decided state) {
      return new Policy(
          id,
          revision,
          serviceId,
          expiresAt,
          definition,
          digest,
          proposedBy,
          proposalReason,
          proposedAt,
          state.decision(),
          state.reviewerId(),
          state.reviewReason(),
          state.reviewedAt(),
          state.currentEligible(),
          new Material("SHA-256", "UTF-8", canonicalText, digest),
          AUTHORITY,
          effectiveFrom,
          null,
          state.trustDigest(),
          state.trustRevision(),
          state.assertionDigest(),
          state.signedAssertion(),
          state.review());
    }

    PolicySummary summary() {
      return summary(Decided.draft());
    }

    PolicySummary summary(Decided state) {
      return new PolicySummary(
          id,
          revision,
          serviceId,
          expiresAt,
          digest,
          proposedBy,
          proposalReason,
          proposedAt,
          state.decision(),
          state.reviewerId(),
          state.reviewReason(),
          state.reviewedAt(),
          state.currentEligible(),
          AUTHORITY,
          effectiveFrom,
          null,
          state.trustDigest(),
          state.trustRevision(),
          state.assertionDigest(),
          state.review().approvalCount(),
          state.review().validApprovalCount(),
          state.review().rejectionCount());
    }
  }
}
