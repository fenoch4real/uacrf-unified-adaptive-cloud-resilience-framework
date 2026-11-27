package uacrf.authz

# Non-overridable UACRF baseline safety policy (M0 placeholder; expanded in M4).
# Default decision is always fail-closed DENY unless explicit requirements are met.

default decision := {
  "outcome": "DENY",
  "reasons": ["DEFAULT_FAIL_CLOSED"]
}

decision := {
  "outcome": "RECOMMEND",
  "reasons": ["EVIDENCE_PROVISIONAL_OR_UNKNOWN"]
} if {
  input.evidenceQualification != "QUALIFIED"
}

decision := {
  "outcome": "APPROVAL_REQUIRED",
  "reasons": ["PRODUCTION_OR_CRITICAL_REQUIRES_DISTINCT_APPROVAL"]
} if {
  input.evidenceQualification == "QUALIFIED"
  input.graphReconciliationStatus == "COMPLETE"
  input.action.reversible == true
  input.environment == "production"
}

decision := {
  "outcome": "PREAUTHORIZED",
  "reasons": ["NONPROD_REVERSIBLE_CERTIFIED_PATTERN"]
} if {
  input.evidenceQualification == "QUALIFIED"
  input.graphReconciliationStatus == "COMPLETE"
  input.action.reversible == true
  input.environment != "production"
  input.runbook.certified == true
  input.action.blastRadius <= input.policy.maxBlastRadius
}
