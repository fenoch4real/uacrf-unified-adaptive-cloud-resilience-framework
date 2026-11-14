# Configure and apply objective templates

Version 1.0 · Guide revision 13 · UI refinement 8

## Configure reusable templates

Open Settings and choose Objective templates and rules. Select a template to create its next local version, or create a new template. Define its name, service or agent scope, metric, unit, default target, minimum and maximum, measurement window and required evidence. The prototype rejects incomplete definitions and ranges that do not contain the default target. Percent ranges cannot exceed one hundred. Saving adds a local version without overwriting earlier versions.

## Apply a template to workload targets

Use the Objectives link under Services or Agents, or choose the matching Apply button in template administration. These links open the workload-target tab in the shared objective workspace. Choose a relevant template and apply its defaults, then add the proposed target, effective date and reason. The proposal must use the selected template version and unit and stay within its allowed range. If the catalog changes that template, apply the current version before submitting a new proposal.

## Review history and operating boundary

A submitted proposal retains its template snapshot. Later template edits do not rewrite that proposal or the example measurement result. A separate reviewer can rehearse approval or rejection in the workload tab; neither action activates a target or proves compliance. Administrator configuration and workload approval are separate responsibilities. The catalog, proposal history and decisions exist only in page memory and disappear on reload. Live scoped permissions, persistent storage, audit, activation and runtime use still require the proposed backend extension.

## Navigation and document control

UI-22 has one canonical placement under Settings, with scoped Objectives aliases under Services and Agents. This preserves forty-nine canonical views and thirteen main destinations. Guide revision 13 and UI refinement 8 describe the local catalog flow. Version 1.0, the architecture and database baselines, formal requirements and patent claims remain unchanged. The proposed objective schemas require the exact template identifier and version; the canonical API count remains seventy-four.

## Import review and signal timing

The Import review tab rehearses a fictional source comparison, explicit conflict resolution and preparation of a local workload proposal. It does not fetch or verify an external definition. Connection review includes a provider-scoped timing plan for collection, freshness and reconciliation; it starts no polling or scheduler. The full proposed import and signal design lives in import_signal_design/README.md at the release root. Imported definitions require source identity and version history, while measuring them requires separate operational evidence.

## Implementation review 1

The current review distinguishes design coverage from completed application behavior. Of 74 canonical operations, 29 have machine-readable API contracts and 45 still need complete wire contracts. The existing bundles now include typed response projections. Objective templates specify comparison, measurement type and conditional percentile, with exact window and unit retained in local proposals. The proposed integrity patch adds submission and lifecycle guards, but controlled procedures, persistent reasons, mandatory-rule bindings, source checks and production privileges remain open. Nineteen sequential objective assertions supplement the rerun baseline database evidence. No readiness score changes.

The earlier flow notes describe 90 baseline flows, 180 diagrams and revision-specific implementation gates. Treat those counts and architecture/database labels as historical; diagrams do not establish executed HTTP handlers or acceptance.

For current implementation and test status, read the [runtime gap-closure report](../runtime/GAP_CLOSURE.md), [verification snapshot guide](../verification/README.md) and [integration test plan](../verification/INTEGRATION_TEST_PLAN.md).
