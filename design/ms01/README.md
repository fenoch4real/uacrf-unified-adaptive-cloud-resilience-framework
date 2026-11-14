# UACRF microservices design package

Version 1.0 | Architecture revision MS 01 | 3 November 2026

Start with [the reading library](index.html) or the [main architecture](ARCHITECTURE.md). The recommended target is fourteen independently deployable Java Spring Boot domain services, one unified experience API and one API edge. PostgreSQL remains the transactional authority; S06 alone owns the verified Neo4j projection.

This package is the architectural design for a fresh microservices implementation. The existing Java application is not a build or runtime dependency. The checked-in contracts and inventories are the implementation requirements; unavailable historical source evidence is not a gate to the new build. The microservices themselves have not been implemented or accepted by this package.

## Read in this order

1. [Architecture and operating principles](ARCHITECTURE.md)
2. [Detailed service specifications](SERVICE_DESIGN.md)
3. [Internal interfaces and state machines](INTERNAL_INTERFACES.md)
4. [Implementation milestones and migration procedure](IMPLEMENTATION_PLAN.md)
5. [Acceptance cases and risk register](ACCEPTANCE_AND_RISKS.md)
6. [Architecture decision records](DECISIONS.md)
7. [Additional target API capabilities](TARGET_API_ADDITIONS.md)

The Word and PDF editions combine these documents. The HTML reading library includes the diagrams and links to machine-readable inventories. All files in this folder form one design package and are listed in its manifest.

## Traceability and counts

The inherited route inventories differ: one records 107 registered routes and an older completion inventory records 99 routes and 44 unclosed original operations. These counts are planning inputs only; target delivery is tracked through implemented contracts and executed acceptance evidence.

The checked-in API inventory has 144 unique operations: 74 canonical and 70 other documented operations. Every included operation has one target owner. The proposed target additions define 47 additional interfaces for previously incomplete product workflows; this separate interface count does not replace the historical list of 47 unfinished operations. The architecture proposes 191 public method/path pairs in total, subject to contract publication and implementation gates. These counts describe the available design artifacts and do not establish exhaustive discovery from the absent legacy source.

The [original 47 disposition](inventories/ORIGINAL_47_DISPOSITION.json) retains the exact historical operation identities. The [data map](inventories/DATA_OWNERSHIP.csv) assigns all 153 source table declarations, including per-service technical patterns and the polymorphic analysis-case split. The [requirement allocation](inventories/REQUIREMENT_ALLOCATION.csv) assigns all 131 formal requirement IDs and preserves available source-row wording. The [scenario register](inventories/ACCEPTANCE_SCENARIOS.json) retains thirty-nine planned acceptance scenarios with no invented executed result.

## Implementation starting point

Begin milestone M0, followed by the real multi-service identity/catalog/customer-journey slice in M1. Use `development/UACRF_v1_0_microservices` for the target. Preserve the existing application, paper, recovered originals and historical archives. No continuing goal was created by producing this design.
