# ADR-002: Advice Is Not Authority & No-Synthetic-Fallback

- **Status:** Accepted
- **Summary Description:** Separates AI/human recommendations from execution authorization and forbids substituting documentation, runbooks, or LLM summaries for missing empirical operational probes.

## Context
During outages or complex AI incidents, operations teams and automated assistants frequently mistake plausible textual recommendations or historical documentation for verified operational readiness, causing premature recovery actions that worsen customer blast radius.

## Decision
1. **Empirical Evidence Gate (`S04`):** `QualificationEngine` enforces source-kind admissibility, freshness TTLs, and graph-revision bindings, rejecting `KNOWLEDGE_DOC`, `ADVISORY_DRAFT`, `LLM_SUMMARY`, and `RUNBOOK` submissions with `422 SYNTHETIC_FALLBACK_FORBIDDEN`. Missing probes keep readiness strictly `UNKNOWN`.
2. **Isolated Solution-Specific Advisors (`S09`, `P7`):** Internal advisory agents operate behind a prompt-injection and SSRF firewall, token budgets, kill switches, and permutation-invariant arbitration that always preserves unresolved `BLOCKING_CONCERN` Critic dissent.
3. **Separate Customer AI Checks (`S02`, `S04`):** Customer-facing predictive and generative models require 6 empirical evidence checks (`endpoint_availability`, `latency_p95`, `model_quality`, `subgroup_safety`, `feature_freshness`, `fallback_capacity`).

## Consequences
AI copilots accelerate evidence discovery and root-cause explanation without ever holding execution privileges, minting authorizations, or concealing empirical gaps.
