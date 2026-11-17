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
      String signedAssertion) {}

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
      String assertionDigest) {}

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
          "DRAFT",
          null,
          null,
          null,
          false,
          new Material("SHA-256", "UTF-8", canonicalText, digest),
          AUTHORITY,
          effectiveFrom,
          null,
          null,
          null,
          null,
          null);
    }

    PolicySummary summary() {
      return new PolicySummary(
          id,
          revision,
          serviceId,
          expiresAt,
          digest,
          proposedBy,
          proposalReason,
          proposedAt,
          "DRAFT",
          null,
          null,
          null,
          false,
          AUTHORITY,
          effectiveFrom,
          null,
          null,
          null,
          null);
    }
  }
}
