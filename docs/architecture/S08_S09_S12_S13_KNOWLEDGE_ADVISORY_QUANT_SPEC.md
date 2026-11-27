# S08 Knowledge, S12 Learning, S13 Experiments, S09 Advisors & Quantitative Engine Specification

## Overview
Long-term resilience improves through governed postmortem closures, controlled fault injection, quantitative modeling, and inspectable AI assistance. `knowledge-service` (`S08`/`S12`/`S13`) and `advisory-service` (`S09`) provide these capabilities while strictly preserving UACRF's boundary that advisory reasoning never overrides empirical truth or policy authority.

## Simple Requirements & Invariants
1. **Governed Knowledge Base (`S08`, port `8091`):** Enforces quarantine and two-person approval (`SELF_APPROVAL_FORBIDDEN`) for runbooks and architecture records; citations provide traceable reference context but cannot satisfy missing operational probes.
2. **Verified Closed-Loop Learning (`S12`):** Blocks closing incident learning items (`409 VERIFICATION_REQUIRED`) until verified SHA-256 rule, runbook, or regression test artifact digests are linked.
3. **Guardrailed Fault Experiments (`S13`):** Executes chaos experiments inside strict blast-radius caps and aborts automatically (`ABORTED_BY_GUARDRAIL`) whenever customer SLO guardrails breach.
4. **Quantitative Shadow Model (`§10.5`):** Evaluates recovery trajectory metrics $r(t+1)=\sigma(Cr(t)+u(t)-m(t))$, system score $S(t)=w^Tr(t)+\lambda r(t)^TCr(t)$, $RCI(t)=\sum \alpha_k c_k(t)$, and $ER(t)=RCI(t)(1-S(t))$ strictly in `SHADOW_NON_AUTHORITATIVE` mode for empirical comparison.
5. **Solution-Specific Agent Advisors (`S09`, port `8092`):** Operates behind a prompt-injection firewall (`422 PROMPT_INJECTION_BLOCKED`), token budget cap (`429 BUDGET_EXHAUSTED`), advisory kill switch, SSRF and PII-scrubbing LLM gateway (`EnterpriseLlmGateway`), blocking Critic dissent preservation, and permutation-invariant `DeterministicArbiter` (`P7`).
