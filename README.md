# Unified Adaptive Cloud Resilience Framework (UACRF)

UACRF is in design and prototyping for assessing the resilience of services
and AI-agent workloads, governing bounded operational actions, and reviewing
recovery evidence. Customer journeys connect those capabilities to outcomes;
services and agents remain separately managed workloads.

This repository records the current product and architecture baseline and the
work planned to implement it. It is not a release of a completed UACRF
microservices system.

## Published paper

The framework is introduced in the following publication:

Fabiyi, O. O., & Yeboah, M. M. (2026). [*UACRF: A Unified Adaptive Framework
for Resilient AI-Cloud Systems Supporting Critical Digital Infrastructure*](https://nahdapublications.org/nid/nid/njbei.2026.0001).
NJBEI Review, 1.0, 1–29. Published September 18, 2026. NID:
nid/njbei.2026.0001.

This repository describes the product and implementation design that builds
on the paper's framework.

## Project status

The design and prototype phase is complete enough to begin implementation.
The repository contains a detailed
microservices architecture, supporting contracts and inventories, and a
static UI prototype. The target distributed services have not yet been
implemented or accepted.

The architecture uses Java with Spring Boot, PostgreSQL for authoritative
transactional data, and Neo4j as a verified graph projection. The browser
experience is designed around the existing Bootstrap-based UI. Kafka and
object storage are also part of the target design. See the [architecture
overview](docs/architecture-overview.md) for boundaries and important
constraints.

The existing Java application is retained as a migration and regression
reference. Its historical test results describe that application and do not
demonstrate that the target microservices work.

## Explore the project

- [Microservices architecture](docs/ARCHITECTURE.md) —
  service boundaries, data ownership, and system behavior.
- [Architecture implementation roadmap](docs/implementation-roadmap.md) —
  staged implementation and migration milestones.
- [UI prototype](UI/ui_workloads/index.html) and [setup guide](UI/ui_workloads/START_HERE.html) —
  illustrative screens and local-only flows.
- [Acceptance criteria and risks](docs/ACCEPTANCE_AND_RISKS.md) —
  planned system-level acceptance tests and principal risks.

The [implementation roadmap](docs/implementation-roadmap.md) summarizes the
planned sequence.

## Important limitations

- The UI prototype uses illustrative local state. Its actions do not call the
  target microservices, persist production data, or perform cloud operations.
- Architecture checks demonstrate allocation and consistency of design
  artifacts, not runtime behavior, production readiness, or completion of
  acceptance tests.
- Cloud-provider integrations, live identity configuration, production
capacity, retention, and commercial policies require implementation decisions
and validation before use.
- This repository does not grant rights to any third-party material. No
software license has been selected; until one is added, do not assume that
the code or documents may be reused or redistributed.

## Implementation starting point

Implementation is planned to begin with milestone M0: preserve and reconcile
the migration baseline, establish the build and CI foundation, and record
security and contract policies. Milestone M1 then establishes the first real
multi-service workflow for identity, catalog, and customer journeys.

See the [implementation plan](docs/IMPLEMENTATION_PLAN.md)
for milestone entry and completion criteria.
