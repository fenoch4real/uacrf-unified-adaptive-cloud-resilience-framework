# Unified Adaptive Cloud Resilience Framework (UACRF)

UACRF brings cloud-service and AI-agent resilience into a shared lifecycle:
assessing risk, governing bounded operational actions, and verifying recovery
with evidence. Customer journeys connect those capabilities to outcomes, while
services and agents remain separately managed workloads.

## Published paper

The paper proposes a unified approach to cloud and AI-agent resilience,
connecting dependency-aware evidence and assessment with bounded actions,
independent recovery validation, and learning from outcomes. Its central
boundary is that advice is not authority: a recommendation does not authorize
recovery, and a provider-reported success does not prove that recovery worked.

The framework is described in:

Fabiyi, O. O., & Yeboah, M. M. (2026). [*UACRF: A Unified Adaptive Framework
for Resilient AI-Cloud Systems Supporting Critical Digital Infrastructure*](https://nahdapublications.org/nid/nid/njbei.2026.0001).
NJBEI Review, 1.0, 1–29. Published September 18, 2026. NID:
nid/njbei.2026.0001.

This repository develops the product design and implementation that build on
the paper's framework.

## Project status

UACRF is being implemented incrementally. This repository brings together the
target microservices architecture, contracts and inventories, a static UI
prototype, and a Maven multi-module system with selected service slices and
focused database-backed tests. It is not yet a complete or production-ready
system: no M0-M8 milestone is complete, and the distributed system has not
passed full runtime acceptance. See [implementation status](docs/IMPLEMENTATION_STATUS.md)
for current scope, test evidence, and open gaps.

The target stack centers on Java 25 and Spring Boot, with PostgreSQL for
authoritative transactional data and Neo4j for a verified graph projection.
Kafka and object storage are also part of the design, though not all target
components and flows are connected. See the [architecture overview](docs/architecture-overview.md)
and [service architecture](docs/ARCHITECTURE.md) for system boundaries and
constraints.

This is a fresh microservices implementation; the existing Java application is
not a build, runtime, regression, or acceptance dependency. Progress is
measured against the target services and their executed tests.

## Explore the project

- [Microservices architecture](docs/ARCHITECTURE.md) —
  service boundaries, data ownership, and system behavior.
- [Architecture implementation roadmap](docs/implementation-roadmap.md) —
  staged implementation and migration milestones.
- [Current implementation status](docs/IMPLEMENTATION_STATUS.md) —
  milestone progress, implemented slices, evidence, and remaining work.
- [UI prototype](design/ui-prototype/ui_workloads/index.html) and [setup guide](design/ui-prototype/ui_workloads/START_HERE.html) —
  illustrative screens and local-only flows.
- [Acceptance criteria and risks](docs/ACCEPTANCE_AND_RISKS.md) —
  planned system-level acceptance tests and principal risks.
- [Build and dependency policy](docs/BUILD_AND_DEPENDENCY_POLICY.md) —
  supported build baseline and supply-chain controls.

## Important limitations

- The UI prototype runs on illustrative local state; it does not call the
  target microservices, persist production data, or perform cloud operations.
- Architecture checks verify the allocation and consistency of design
  artifacts. They do not establish runtime behavior, production readiness, or
  completion of acceptance tests.
- Provider integrations and production identity, capacity, retention, and
  commercial policies still require implementation and validation.
- This repository does not grant rights to any third-party material. No
  software license has been selected; until one is added, do not assume that
  the code or documents may be reused or redistributed.

## Build and validate

You will need Java 25, Python 3 for the MS01 design check, and a
Docker-compatible runtime for Testcontainers-based integration tests.

Run the same primary checks configured in CI:

```sh
python3 -B design/ms01/tools/check_architecture.py
./mvnw --batch-mode --no-transfer-progress verify
```

These checks do not establish full-system runtime acceptance. See the
[implementation roadmap](docs/implementation-roadmap.md) for milestone
sequencing and [implementation status](docs/IMPLEMENTATION_STATUS.md) for
current evidence.
