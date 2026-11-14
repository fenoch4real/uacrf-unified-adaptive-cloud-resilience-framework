# Recovery assurance activities and sequences

Version 1.0 · Flow revision 9

The numbered steps follow the Java and SQL workflow. The first three flows describe implemented Java diagnostics. ASSURE-UI-01 is a local UI rehearsal that makes no server request. Human sign-off, controlled test execution and live collection remain separate. Sequence SVGs provide the numbered actor handoffs; editable Mermaid sources express the message and failure branches.

## ASSURE-01 Establish an attributable diagnostic baseline

[Activity](svg/ASSURE-01_activity.svg) · [Sequence](svg/ASSURE-01_sequence.svg) · [Editable activity](mermaid/ASSURE-01_activity.mmd) · [Editable sequence](mermaid/ASSURE-01_sequence.mmd)

S01. Human → Java API: Submit journey graph primary recovery scenario and reason.

S02. Java API → PostgreSQL: Verify current identity journey membership and all workload permissions.

S03. Java API → Neo4j: Verify complete accepted graph content against the SQL snapshot.

S04. Java API → Java API: Find shared dependencies within bounded traversal.

S05. Java API → PostgreSQL: Check idempotency and append baseline review audit and outbox atomically.

S06. Java API → Human: Return diagnostic findings with independence NOT_CERTIFIED.

F1 Stop on denied access stale revision missing evidence store mismatch or traversal limits. No recovery action or certification.

## ASSURE-02 Review changes against the immutable baseline

[Activity](svg/ASSURE-02_activity.svg) · [Sequence](svg/ASSURE-02_sequence.svg) · [Editable activity](mermaid/ASSURE-02_activity.mmd) · [Editable sequence](mermaid/ASSURE-02_sequence.mmd)

S01. Human → Java API: Read assessment and pending accepted graph revisions.

S02. Java API → PostgreSQL: Check current access to baseline review history and pending graph scopes.

S03. Human → Java API: Select accepted graph and submit expected revision and review reason.

S04. Java API → Neo4j: Verify selected projection and current SQL evidence eligibility.

S05. Java API → Java API: Compare relevant nodes and semantic relationships with the original baseline.

S06. Java API → PostgreSQL: Append review with revision fencing audit and notification outbox.

S07. Java API → Human: Explain relevant changes shared dependencies and required retest review.

F1 Stop on denied access stale revision missing evidence store mismatch or traversal limits. No recovery action or certification.

## ASSURE-03 Read evidence limits and preserve history

[Activity](svg/ASSURE-03_activity.svg) · [Sequence](svg/ASSURE-03_sequence.svg) · [Editable activity](mermaid/ASSURE-03_activity.mmd) · [Editable sequence](mermaid/ASSURE-03_sequence.mmd)

S01. Human → Java API: Request current diagnostic assessment.

S02. Java API → PostgreSQL: Recheck current actor grants journey linkage and represented workloads.

S03. Java API → Neo4j: Verify latest reviewed graph when its evidence remains eligible.

S04. Java API → Java API: Derive insufficient evidence or pending review without rewriting history.

S05. Java API → Human: Return bounded history pending revisions and explicit diagnostic limits.

F1 Stop on denied access stale revision missing evidence store mismatch or traversal limits. No recovery action or certification.

## ASSURE-UI-01 Follow one assessment across application workspaces

[Activity](svg/ASSURE-UI-01_activity.svg) · [Sequence](svg/ASSURE-UI-01_sequence.svg) · [Editable activity](mermaid/ASSURE-UI-01_activity.mmd) · [Editable sequence](mermaid/ASSURE-UI-01_sequence.mmd)

S01. Human → Main UI: Choose a registered example journey inside Assess.

S02. Main UI → Main UI: Validate scenario reason workload and local owner before local baseline creation.

S03. Human → Main UI: Inspect a local change and review the diagnostic result.

S04. Main UI → Main UI: Append local reassessment and preserve earlier local reviews.

S05. Main UI → Human: Show the same scoped status in journey graph evidence recovery and activity views.

F1 Stop on denied access stale revision missing evidence store mismatch or traversal limits. No recovery action or certification.

