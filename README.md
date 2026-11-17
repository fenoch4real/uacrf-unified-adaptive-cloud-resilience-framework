# Unified Adaptive Cloud Resilience Framework (UACRF)

UACRF is being developed as a framework for assessing the resilience of cloud
services and AI-agent workloads, governing bounded operational actions, and
reviewing recovery evidence. Customer journeys connect those capabilities to
outcomes; services and agents remain separately managed workloads.

This repository records the product and architecture baseline and tracks
incremental implementation. It is not a release of a completed UACRF
microservices system.

## Published paper

The UACRF paper proposes a unified approach to cloud and AI-agent resilience,
linking dependency-aware evidence and assessment to bounded actions, independent
recovery validation, and learning from outcomes. It keeps advisory assistance
separate from operational authority: recommendations do not authorize recovery
or prove that it succeeded.

The framework is introduced in the following publication:

Fabiyi, O. O., & Yeboah, M. M. (2026). [*UACRF: A Unified Adaptive Framework
for Resilient AI-Cloud Systems Supporting Critical Digital Infrastructure*](https://nahdapublications.org/nid/nid/njbei.2026.0001).
NJBEI Review, 1.0, 1–29. Published September 18, 2026. NID:
nid/njbei.2026.0001.

This repository contains the product design and incremental implementation
that build on the paper's framework.

## Project status

Implementation is underway, but this is not a complete or production-ready
release. The repository contains the target microservices architecture,
contracts and inventories, a static UI prototype, and a Maven multi-module
system with selected service slices and focused database-backed tests. No M0-M8
milestone is complete, and the distributed system has not passed full runtime
acceptance. For current scope, test evidence, and open gaps, see
[implementation status](docs/IMPLEMENTATION_STATUS.md).

The target architecture uses Java 25 and Spring Boot, PostgreSQL for
authoritative transactional data, and Neo4j as a verified graph projection.
Kafka and object storage are also in the target design; not all target
components or service flows are connected. See the
[architecture overview](docs/architecture-overview.md) and
[service architecture](docs/ARCHITECTURE.md) for boundaries and constraints.

This is a fresh microservices implementation. The existing Java application
is not a build, runtime, regression, or acceptance dependency. Delivery status
is based on the target services and their executed tests.

## Explore the project

- [Microservices architecture](docs/ARCHITECTURE.md) —
  service boundaries, data ownership, and system behavior.
- [Architecture implementation roadmap](docs/implementation-roadmap.md) —
  staged implementation and migration milestones.
- [Current implementation status](docs/IMPLEMENTATION_STATUS.md) —
  implemented slices, evidence, and remaining work.
- [UI prototype](design/ui-prototype/ui_workloads/index.html) and [setup guide](design/ui-prototype/ui_workloads/START_HERE.html) —
  illustrative screens and local-only flows.
- [Acceptance criteria and risks](docs/ACCEPTANCE_AND_RISKS.md) —
  planned system-level acceptance tests and principal risks.
- [Build and dependency policy](docs/BUILD_AND_DEPENDENCY_POLICY.md) —
  supported build baseline and supply-chain controls.

## Important limitations

- The UI prototype uses illustrative local state. Its actions do not call the
  target microservices, persist production data, or perform cloud operations.
- Architecture checks demonstrate allocation and consistency of design
  artifacts, not runtime behavior, production readiness, or completion of
  acceptance tests.
- Provider integrations, production identity configuration, operational
  capacity, retention, and commercial policies require further implementation
  and validation before use.
- This repository does not grant rights to any third-party material. No
software license has been selected; until one is added, do not assume that
the code or documents may be reused or redistributed.

## Build and validate

Requirements: Java 25, Python 3 for the MS01 design check, and a
Docker-compatible runtime for integration tests that use Testcontainers.

Run the same primary checks configured in CI:

```sh
python3 -B design/ms01/tools/check_architecture.py
./mvnw --batch-mode --no-transfer-progress verify
```

These checks do not establish full-system runtime acceptance. See the
[implementation roadmap](docs/implementation-roadmap.md) for milestones and
the [implementation status](docs/IMPLEMENTATION_STATUS.md) for current evidence.
