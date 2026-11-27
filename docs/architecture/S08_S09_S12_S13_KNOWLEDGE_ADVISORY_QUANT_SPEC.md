# S08 Knowledge, S12 Learning, S13 Experiments, S09 Advisors (P7) & Quantitative Engine Spec

## 1. Service Separation
- **`S08`/`S12`/`S13` (`knowledge-service`, port `8091`)**:
  - **S08**: Knowledge quarantine + two-person review (`SELF_APPROVAL_FORBIDDEN`). Citations serve as reference only (`No-Synthetic-Fallback`).
  - **S12**: `LearningItem` closure requires verified artifact SHA-256 digest (`409 VERIFICATION_REQUIRED`).
  - **S13**: Fault injection experiments enforce blast-radius caps and automatic SLO guardrail abort (`ABORTED_BY_GUARDRAIL`).
  - **Section 10.5 Quantitative Shadow Engine**: Computes $r(t+1)=\sigma(Cr(t)+u(t)-m(t))$, $S(t)=w^Tr(t)+\lambda r(t)^TCr(t)$, $RCI(t)=\sum \alpha_k c_k(t)$, and $ER(t)=RCI(t)(1-S(t))$ strictly as `SHADOW_NON_AUTHORITATIVE`.
- **`S09` (`advisory-service`, port `8092`) — Solution-Specific Agent Advisors**:
  - Clear isolation from Protected Customer Agent Workloads (`S02`/`S04`).
  - Prompt-injection firewall (`422 PROMPT_INJECTION_BLOCKED`), token budget cap (`429 BUDGET_EXHAUSTED`), advisory kill switch, blocking `CriticDissent` preservation, and permutation-invariant `DeterministicArbiter` (`P7`).
