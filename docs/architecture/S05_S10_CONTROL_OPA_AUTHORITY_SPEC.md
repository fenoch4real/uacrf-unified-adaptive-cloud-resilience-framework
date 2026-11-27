# S05 Objectives/Rules & S10 OPA Policy / Bounded Authority (P2, P3) Specification

## 1. Service Ownership
- **`S05` (`objectives-service`, port `8088`, DB `uacrf_objectives`)**: Owns SLI/SLO/RTO/RPO definitions and deterministic multi-layer rule packs (`APP-*`, `INF-*`, `AI-*`, `SEC-*`, `GOV-*`), outputting `RiskState` (`NORMAL`, `GUARDED`, `ELEVATED`, `CRITICAL`, `UNKNOWN`). Missing/stale telemetry transitions strictly to `UNKNOWN` rather than failing open to `NORMAL`.
- **`S10` (`policy-service`, port `8089`, DB `uacrf_policy`)**: Evaluates `policies/baseline.rego` (`DENY`, `RECOMMEND`, `APPROVAL_REQUIRED`, `PREAUTHORIZED`), enforces proposer/approver separation of duties (`403 SELF_APPROVAL_FORBIDDEN`), executes **P2 `MaterialGraphDeltaClassifier`**, and issues short-lived, audience-bound, single-use **P3 RS256 `BoundedRecoveryAuthorization`** envelopes backed by a transactional nonce ledger.
