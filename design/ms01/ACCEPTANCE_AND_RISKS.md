# Architecture acceptance and risk register

Version 1.0 | Architecture revision MS 01

The checks below are planned acceptance criteria. No distributed execution is claimed by this design. Existing local test results remain attributed to the single application and its controlled fixtures.

## Distributed acceptance matrix

| ID | Test or fault | Required observed result |
|---|---|---|
| MS T01 | Independent S01 and S02 with separate database roles | Real sign-in and journey/workload creation; cross-database query/write denied |
| MS T02 | Tenant/environment substitution in HTTP and events | Owner rejects access without disclosing the foreign aggregate |
| MS T03 | Outbox publisher crashes after broker acknowledgement | Duplicate event produces one logical consumer update and retained delivery history |
| MS T04 | Consumer crashes before or after commit | Inbox and projection stay consistent; restart does not repeat a domain effect |
| MS T05 | Same aggregate sequence with different payload digest | Conflict quarantined; no downstream decision uses ambiguous context |
| MS T06 | Gap or out-of-order graph event | Latest valid projection preserved; gap visible; required current-revision action blocked |
| MS T07 | Neo4j marker exists but graph bytes differ | Verification fails; no eligible context from the marker alone |
| MS T08 | Evidence/knowledge bytes unavailable or substituted | Retryable unavailable or explicit invalidity; no fabricated qualification or model context |
| MS T09 | Objective import changes after preview | Old resolution/proposal conflicts; new source revision reviewed explicitly |
| MS T10 | Revocation races authorization reservation and dispatch | Outcomes match documented ordering; no stale cached permission admitted as current |
| MS T11 | Provider accepts an action and the response is lost | Attempt remains unknown until status reconciliation; no blind repeat |
| MS T12 | Executor loses its lease before commit | Late worker cannot overwrite the new lease/generation result |
| MS T13 | Cancellation with work already in flight | New work stops; actual late outcomes remain attributable and ineligible where required |
| MS T14 | Agent gateway timeout or invalid/oversized response | Unknown usage and abstention retained; no selected proposal or invented zero charge |
| MS T15 | Runtime restarts after dispatch intent or output-send failure | Journal prevents a second observed gateway invocation for that attempt |
| MS T16 | Multi-agent missing output, abstention, rejection or dissent | Complete retained set drives BLOCKED or HUMAN_REVIEW; dissent is never omitted |
| MS T17 | Provider success with failed or missing mandatory probes | Workflow not certified recovered; failed evidence and next action retained |
| MS T18 | Protected agent checkpoint or memory contract changes | Resume requires current version/generation, provenance and required independent evaluation |
| MS T19 | Billing event/webhook duplicated or reordered | One admitted source identity; exact adjustment/reconciliation without duplicate charge |
| MS T20 | Price, allowance or currency changes during a period | Frozen applicable rules used; correction represented by retained adjustment |
| MS T21 | Audit/reporting service unavailable | Domain commits remain durable; projection catches up without rewriting business facts |
| MS T22 | Notification destination fails or owner changes | Delivery failure visible; no false acknowledgement or finding closure |
| MS T23 | Decision replay has provider credentials unavailable | Historical reasoning reconstructs read-only; no operational side effect |
| MS T24 | Restore databases, artifacts and projections at different times | Inconsistency detected and reconciled before current authority/certification resumes |
| MS T25 | Old/new services overlap at migration cutover | One authoritative writer; row/digest/event watermarks reconcile |
| MS T26 | Schema/event expansion and rollback | Older consumers continue on compatible additions; breaking changes rejected by CI |
| MS T27 | Complete customer journey in a real browser | Owners, imports, linking, objectives, graphs, findings, decisions, recovery and billing finish their tasks |
| MS T28 | Keyboard, text labels and role-sensitive controls | Accessible navigation and understandable errors; provider icons do not carry meaning alone |
| MS T29 | Forecast evaluation with temporal leakage or poor calibration | Model not promoted; uncertainty and failed acceptance remain visible |
| MS T30 | Adaptive proposal fails shadow/regression tests | Existing approved production version remains; no automatic policy promotion |

Run the retained thirty-nine full-system scenarios alongside this matrix. Test each selected provider action's actual conditional updates, idempotency, cancellation, credential scope, retention and recovery semantics. A containerized local adapter establishes local behavior only.

## Principal risks and concrete mitigation

| Risk | Consequence | Design response and acceptance gate |
|---|---|---|
| Distributed authorization race | A formerly allowed action could be dispatched too late | S10 control fences, short reservations, online final checks, conditional provider writes and measured ordering tests; disclose the residual provider boundary |
| Distributed monolith | Services require shared SQL or synchronized releases | Exclusive owner databases, owner APIs, contract compatibility and cross-database denial tests at M1 |
| Fourteen-service operational overhead | Excess memory, connection pools, on-call burden and upgrade complexity | Staged vertical slices, shared physical infrastructure with isolated logical ownership, measured pools and capacity profiles |
| Divergent artifacts and old guide assertions | Implementer follows stale wire shapes or counts | Effective contract inventory, revisioned source snapshot, one architecture library and all-artifact release gate |
| Eventual consistency mistaken for current proof | Lagging graph or identity projections enable a decision | Visible watermark/age, explicit current owner receipts and unavailable outcomes |
| Uncontrolled provider or model effects | Duplicate mutation or spend beyond a declared reservation | Isolated adapters, stable request IDs, journal/attempt ledger, provider admission controls and live per-capability qualification |
| Hidden shared dependency among agents | Apparent independent agreement survives one shared failure | Typed model/identity/runtime dependencies, declared independence assumptions and scenario-based S12 validation |
| Audit/billing payload leak | Confidential data appears in events or reports | Minimal event payloads, source-controlled artifacts, current disclosure, log redaction and export authorization |
| Migration rollback loses accepted facts | New domain writes disappear on routing reversal | One writer, consistent watermark and forward repair or reconciled reverse migration |
| Unsupported prediction claims | Observed risk is sold as a forecast | Model cards, temporal holdout tests, calibration, shadow mode and explicit feature promotion |

## Definition of service completion

For each owner, retain implemented API/serializer contracts, migrations and least-privilege runtime roles; happy/adverse integration results; inbox/outbox and command status behavior; current authorization and artifact disclosure; metrics/traces/audit; source and image hashes; customer task walkthrough; deployment and restore evidence appropriate to the claimed scope. A placeholder, an always-rejected action or an unexecuted schema cannot close the workflow.

## Review outcome for this architecture

The design addresses the known gaps in service ownership, graph authority, command routing, source provenance, identity, objectives/imports, advisory agents, provider execution, independent recovery validation, billing and customer navigation. The machine inventories and artifact checks establish coverage and internal consistency of the package, not implementation or acceptance of these capabilities. Deployment sizing, primary provider, live identities, retention, charging policy and selected initial provider actions remain recorded deployment decisions with explicit milestone gates.
