# Audit closure and remaining implementation work

Version 1.0 · Implementation review 6 · Runtime revision 3

The current Java backend connects PostgreSQL and Neo4j. It implements 27 routes: eleven of the 74 canonical operations and sixteen of the 32 proposed operations. Sixty-three canonical and sixteen proposed operations remain outside this runtime. The package does not return fabricated success for an unimplemented route.

## Findings addressed by the current runtime

| Finding | Current result | Acceptance boundary |
|---|---|---|
| R07 application server missing | Spring Boot serves the 27 mapped routes, validates contracts and verifies current authorization. | Partial; the remaining handlers, full decision engine and execution workflow remain open. |
| R11 relationship maintenance | Controlled synchronous update/retire transactions, history and active-only reads work for journey links and resource consumers. | Closed for these metadata families. Operational dependency proposal/approval APIs remain separate. |
| R12 fingerprint interchange | Java and Node verify actual PostgreSQL canonical UTF-8 bytes. | Closed for canonical-material interchange; independent reserialization remains unsupported. |
| R14 owner setup and association | User admission, eligible-owner selection, persistent owner-bound journeys and protected-agent/anchor transfer work locally. | Closed for the local backend; connected browser workflow and live identity-provider acceptance remain open. |
| R15 controlled identity administration | Offline bootstrap/recovery, restricted roles, signed sources, independent grants, revocation and last-admin guards execute. | Partial; production identity integration and operations remain open. |
| R16 Neo4j omitted from Spring Boot | Neo4j configuration/driver, accepted graph import, sealed canonical snapshots, durable leased projection, worker-event history, verified graph reads and bounded diagnostics execute. | Closed for local PostgreSQL/Neo4j integration; resilience certification and live source acceptance remain separate. |

The current suite passes 27 integration methods through real Spring Boot HTTP, PostgreSQL 18.6 and Neo4j 5.26.32. It includes source-import rollback, typed service/agent topology, all-workload authorization, traversal direction and bounds, cycles, a crash between database commits, stale lease rejection, concurrent workers, wrong endpoint rollback, same-count content tampering, actual Neo4j outage/restart and reconciliation. Worker lease/completion/retry/requeue events are append-only and identify the actual technical login. Synthetic signed identity/source fixtures remain explicit. The two owned containers are removed after the run.

## What this does not establish

R05 remains open for the full objective lifecycle, including exact source bindings, controlled review/activation, mandatory organization constraints and concurrent activation. Existing trigger evidence does not replace those handlers.

R06 remains open for provider-native cloud collection, source reconciliation, quarantine, ordering and evaluation invalidation. The offline graph importer requires already qualified evidence; it is not a cloud collector or an independent qualification service.

R07 remains open beyond this slice for complete policy/evidence decision integration, approval/authorization, graph-difference and asynchronous selection workflows, recovery-path certification, durable provider execution, ambiguous-effect reconciliation, independent outcome validation and billing integration. Diagnostic impact is DIAGNOSTIC_ONLY. Shared-dependency review always retains independence NOT_CERTIFIED. A graph view never grants recovery authority.

R10 remains open for connecting the 58-view prototype to persisted behavior, real provider sign-in and full browser/accessibility acceptance. The existing prototype is illustrative. All 39 full-system scenarios remain NOT_EXECUTED.

Neo4j Community integration tests use one isolated account; they do not establish production reader/writer/administrator role separation. Deployment still needs reviewed database privileges and isolation, TLS ingress, real source/provider adapters, approved-key rotation, operational secrets, monitoring, backup/restore and live acceptance evidence. No external cloud account was accessed or modified.

## Continue from the tested boundary

Reproduce runtime/README.md in an isolated environment. Confirm the graph worker's fixed tenant/environment binding and separate credentials, then accept a real source artifact with reviewed qualifications. Execute the restrictive cases with the selected identity provider and actual data-source process. Connect one browser journey to accepted routes before expanding decision or cloud-execution authority.

Use IMPLEMENTATION_MAP.json for exact code coverage, TEST_RESULTS.json and evidence/surefire-summary.xml for executed tests, and graph_design/README.md and graph_design/DIAGRAMS.md for the storage and transaction flow. Earlier missing-Neo4j and blanket missing-handler statements are superseded only within this explicit scope. Version 1.0 and the historical documentation score remain unchanged. Production readiness is not established.


## Recovery readiness assurance update

Version 1.0 runtime revision 4 adds a locally implemented recovery-assurance diagnostic workflow. Thirty routes comprise eleven canonical and nineteen proposed operations; 63 canonical and sixteen proposed operations remain unimplemented, with 35 proposed operations total. An immutable baseline, explicit reassessment, current evidence checks, pending accepted revisions, append-only reviews, audit and outbox support services and agents. No recovery certification, external action, live collection or unattended reassessment is claimed. Guide revision 19 and flow revision 9 identify the update. No score increase. Read [the implementation guide](../assurance_design/README.md), [the local walkthrough](../assurance_design/index.html) and [the step-labelled diagrams](../assurance_design/DIAGRAMS.md). Fresh installation includes runtime/003_assurance_runtime.sql; an existing runtime revision 3 deployment uses the offline Java admin assurance-install command after backup and migration review.
