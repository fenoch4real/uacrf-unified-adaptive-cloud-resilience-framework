# S05 Objectives/Rules & S10 OPA Policy / Bounded Authority Specification

## Overview
Continuous monitoring (`S05`) and deterministic policy gating (`S10`) ensure that resilience posture reacts immediately to degradation without ever allowing autonomous or human operators to bypass governance checks during an emergency.

## Simple Requirements & Invariants
1. **Objectives & Fail-Closed Rule Engine (`S05`, port `8088`, DB `uacrf_objectives`):** Stores versioned SLI/SLO/RTO/RPO targets and evaluates multi-layer rule packs (`APP-*`, `INF-*`, `AI-*`, `SEC-*`, `GOV-*`) into `RiskState` (`NORMAL`, `GUARDED`, `ELEVATED`, `CRITICAL`, `UNKNOWN`). Missing or expired telemetry strictly transitions to `UNKNOWN` rather than failing open.
2. **Live OPA Policy Gate & Separation of Duties (`S10`, port `8089`, DB `uacrf_policy`):** Evaluates proposals against `policies/baseline.rego` (`DENY`, `RECOMMEND`, `APPROVAL_REQUIRED`, `PREAUTHORIZED`) via an OPA sidecar with deterministic local fallback, blocking proposer self-approval (`403 SELF_APPROVAL_FORBIDDEN`).
3. **Pre-Execution Graph Delta Check (`P2`):** Before issuing execution authority, `MaterialGraphDeltaClassifier` verifies the live Neo4j topology revision against proposal creation and invalidates authorization (`409 MATERIAL_GRAPH_DELTA_INVALIDATED`) on widening or target shifts.
4. **Single-Use Execution Tokens (`P3`):** Issues short-lived, audience-bound, target-allowlisted RS256 `BoundedRecoveryAuthorization` tokens backed by a transactional nonce ledger.
