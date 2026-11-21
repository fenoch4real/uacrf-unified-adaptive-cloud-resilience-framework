# UACRF architecture overview

The Unified Adaptive Cloud Resilience Framework (UACRF) is designed as a set
of independently deployable Java Spring Boot services. A browser application
reaches those services through an API edge and a unified experience API. The
experience API presents task-oriented workflows without making the browser a
privileged cloud client.

For service routes and communication contracts, see
[API and service communication contracts](api-service-contracts.md).

## Core design

The architecture defines fourteen domain services, an experience API, and an
API edge. Each domain service owns its business records, database credentials,
migrations, and API or event contracts. Services do not share tables or rely
on cross-domain database foreign keys.

PostgreSQL is the transactional authority for domain records. The topology
service owns Neo4j as a graph projection; accepted graph material remains
authoritative in PostgreSQL. A graph projection is eligible for decision use
only after its content and revision have been verified.

Services exchange authenticated HTTP requests when they need a current answer.
They use durable events for work that can be processed asynchronously. The
design expects duplicate delivery and uses owner-local outbox and inbox
processing rather than claiming exactly-once effects.

Provider collection, model invocation, and privileged execution use isolated
workers and explicit contracts. Collection credentials do not authorize
recovery actions, and advisory-agent output does not grant operational
authority.

## Product boundaries

- **Services and agents** are separately managed protected workloads. An agent
  can be registered independently or associated with a customer journey.
- **Customer journeys** describe outcomes and ordered steps. They link
  workloads and shared resources to customer goals; they do not replace
  workload inventories or define telemetry targets.
- **Shared resources** such as models, tools, knowledge, memory, runtimes, and
  identities can support services, agents, or both.
- **UACRF advisory agents** assist with evidence and recommendations. They are
  distinct from protected customer agents and do not authorize actions.
- **Recovery execution** requires current authorization, a bounded action,
  retained execution evidence, and independent validation. A provider's
  success response alone does not establish recovery.

## Reliability and migration principles

The design uses owner-local durable workflows, current authorization checks,
idempotent command handling, and explicit handling for uncertain provider
outcomes. If a provider may have accepted an action but its response is lost,
the outcome remains unknown until reconciled; the system must not blindly
repeat the mutation.

Migration proceeds one domain at a time. Each cutover has one authoritative
writer, a reconciled data and event watermark, and an ownership epoch.
Routing a request back to an older service is not sufficient to reverse writes
accepted by a new service.

## Status

The target design is not an implemented production architecture. The
[implementation status](DELIVERY_CHECKLIST.md) tracks delivered slices and
remaining acceptance work. The service boundaries, contracts, diagrams, and
planned acceptance tests are documented in the
[MS01 architecture package](../design/ms01/README.md).

This two-layer map connects repository-backed UACRF services to the broader
cloud, agent, and infrastructure ecosystem in the MS01 target. Solid arrows
show implemented or local paths; dotted arrows show target, incomplete, or
unaccepted paths. The diagrams summarize the main paths; the component
inventory and [API contracts](api-service-contracts.md) provide more detail.
The [implementation status](DELIVERY_CHECKLIST.md) is authoritative for
delivery and acceptance claims.

## Application Architecture

<!-- mermaid-checked: no \n, no em-dash/en-dash, no {} in labels, subgraphs are id["label"], arrows are -->|"label"|, all subgraphs closed by end, ids unique -->
```mermaid
%%{init: {"theme": "base", "themeVariables": {"clusterBkg": "#f7f9fc", "clusterBorder": "#bbcad9", "lineColor": "#45617d", "edgeLabelBackground": "#f7f9fc", "fontFamily": "Arial", "primaryTextColor": "#152b43"}, "flowchart": {"curve": "stepAfter"}}}%%
flowchart TD
    subgraph Access1["People and access"]
        Browser1["Browser"]
        Web1["Web application"]
        Ux1["UX01 experience API"]
        Edge1["EDGE01 API edge"]
    end
    subgraph CurrentOwners1["Current owner service modules"]
        IdentityCatalog1["S01 identity and S02 catalog"]
        Observation1["S03 connections and S04 evidence"]
        Assessment1["S05 objectives S06 topology and S07 assessment"]
        KnowledgePolicy1["S08 knowledge and S10 governance"]
    end
    subgraph Advisory1["Advisory"]
        S09["S09 advisory agent groundwork"]
        ModelEndpoint1["Approved model endpoint"]
    end
    subgraph FutureOwners1["Planned owner services"]
        Execution1["S11 execution and S12 recovery"]
        Reporting1["S13 billing and S14 reporting"]
    end
    subgraph Cloud1["Cloud provider and telemetry endpoints"]
        Providers1["AWS Azure and GCP sources not accepted"]
        TelemetrySources1["Kubernetes and OpenTelemetry sources planned"]
    end
    subgraph Platform1["Runtime and state infrastructure"]
        Postgres1[("Owner PostgreSQL databases")]
        Neo4j1[("Neo4j graph projection")]
        KafkaStore1[("Kafka and object storage planned")]
    end
    Browser1 -->|"loads protected pages"| Web1
    Web1 -->|"identity and context"| Ux1
    Ux1 -->|"identity routes"| Edge1
    Web1 -->|"catalog and connections"| Edge1
    Edge1 -->|"sliced owner routes"| CurrentOwners1
    CurrentOwners1 -->|"owner local transactions"| Postgres1
    Assessment1 -->|"S06 verified projection"| Neo4j1
    Observation1 -.->|"S03 read only collection"| Providers1
    Observation1 -.->|"future source integrations"| TelemetrySources1
    Assessment1 -.->|"case context"| S09
    KnowledgePolicy1 -.->|"reviewed citations"| S09
    S09 -.->|"advisory inference"| ModelEndpoint1
    KnowledgePolicy1 -.->|"future action authorization"| Execution1
    CurrentOwners1 -.->|"future events and artifacts"| KafkaStore1
    Reporting1 -.->|"future events and exports"| KafkaStore1
    classDef access fill:#e8f2ff,stroke:#7893ad,color:#152b43,stroke-width:1.5px;
    classDef component fill:#ffffff,stroke:#7893ad,color:#152b43,stroke-width:1.5px;
    classDef platform fill:#f4f7fc,stroke:#7893ad,color:#152b43,stroke-width:1.5px;
    class Browser1,Web1,Ux1,Edge1 access;
    class IdentityCatalog1,Observation1,Assessment1,KnowledgePolicy1,S09,ModelEndpoint1,Execution1,Reporting1,Providers1,TelemetrySources1 component;
    class Postgres1,Neo4j1,KafkaStore1 platform;
```

### Technology Stack Summary

| Layer | Technology | Version | Purpose |
|---|---|---|---|
| Application services | Java and Spring Boot | Java 25, Spring Boot 4.1.2 | Independently built API and owner-service modules |
| HTTP and security | Spring MVC, Spring Security, OAuth2 client | Managed by the Spring Boot parent | Service APIs, browser login, and protected routes |
| Browser | Thymeleaf | Managed by the Spring Boot parent | Server-rendered operator experience |
| Owner state | PostgreSQL | Digest-pinned local image; server version not declared here | Separate transactional database and runtime role per owner |
| Graph projection | Neo4j Community | 5.26.0 in local Compose | S06-only verified graph projection |
| Local identity | OIDC and Keycloak | Keycloak 26.8.2 in local Compose | Development identity provider; S01 remains permission authority |
| Provider collection | AWS Config, STS and CloudWatch; Azure Resource Graph and Monitor; GCP Asset Inventory and Monitoring | Provider-specific; conformance varies | S03 read-only source integrations, not interchangeable capability claims |
| Events and artifacts | Transactional outbox; Kafka and object storage in target design | Target components not deployed by current Compose | Durable cross-owner events and large immutable artifacts |
| Runtime | OCI images and Docker Compose; Kubernetes in target design | Kubernetes version not selected | Local infrastructure and future isolated production service pools |
| Advisory agents | S09 analyst, reviewer, and critic roles | Provider-disabled or incomplete path | Bounded recommendations only; no direct provider authority |

### Data Storage & External Services

Current local infrastructure provides owner-specific PostgreSQL instances,
Neo4j for the S06 projection, and Keycloak for development OIDC. Kafka,
production object storage, and Kubernetes are target architecture components,
not evidence of a deployed runtime. S03 has bounded read-only implementations
for AWS Config and CloudWatch, Azure Resource Graph and Monitor, and Google
Cloud Asset Inventory and Monitoring. These are partial capabilities, and all
three providers remain UNACCEPTED pending live read-only conformance. Kubernetes
and OpenTelemetry source integrations remain target work. Provider credentials
and actions are separate: S03 is read-only collection, while S11 and isolated
execution cells are planned for individually authorized mutations. Synthetic
sample observations do not establish provider conformance.

### Key Architectural Decisions

- Each domain owner controls its PostgreSQL records and authorization decisions; S06 alone writes its Neo4j projection.
- UACRF advisory agents are separate from protected customer agents. S09 recommendations remain non-executable; S10 authorization and S11 execution are distinct boundaries.
- Cloud integrations are qualified independently by provider and capability. A connector, UI icon, or target component does not establish live conformance.

## Component Relationships

<!-- mermaid-checked: no \n, no em-dash/en-dash, no {} in labels, subgraphs are id["label"], arrows are -->|"label"|, all subgraphs closed by end, ids unique -->
```mermaid
%%{init: {"theme": "base", "themeVariables": {"clusterBkg": "#f7f9fc", "clusterBorder": "#bbcad9", "lineColor": "#45617d", "edgeLabelBackground": "#f7f9fc", "fontFamily": "Arial", "primaryTextColor": "#152b43"}, "flowchart": {"curve": "stepAfter"}}}%%
flowchart LR
    subgraph Presentation2["Presentation and transport"]
        cEdge2["EDGE01"]
    end
    subgraph Domain2["Evidence and assessment owners"]
        cS032["S03 connections"]
        cS042["S04 evidence"]
        cS052["S05 objectives"]
        cS062["S06 topology"]
        cS072["S07 assessment"]
    end
    subgraph OtherOwners2["Other owner services"]
        cIdentityCatalog2["S01 identity and S02 catalog"]
        cS082["S08 knowledge"]
        cS102["S10 decision governance"]
    end
    subgraph Advisory2["Advisory module"]
        cS092["S09 advisory groundwork"]
    end
    subgraph Storage2["Persistence and event patterns"]
        cOwnerDb2[("Owner PostgreSQL databases")]
        cNeo4j2[("S06 Neo4j projection")]
        cOutbox2["Owner transactional outbox"]
        cKafka2[("Kafka target only")]
    end
    cEdge2 -->|"sliced routes"| Domain2
    cEdge2 -->|"sliced routes"| OtherOwners2
    cS032 -->|"outbox events to inbox"| cS042
    cS042 -->|"qualified receipts"| cS072
    cS052 -->|"effective objectives"| cS072
    cS062 -->|"graph context"| cS072
    cS062 -->|"verified projection"| cNeo4j2
    cS072 -.->|"case context"| cS092
    cS082 -.->|"reviewed citations"| cS092
    Domain2 -->|"owner local records"| cOwnerDb2
    OtherOwners2 -->|"owner local records"| cOwnerDb2
    Domain2 -->|"owner local events"| cOutbox2
    cOutbox2 -.->|"target event transport"| cKafka2
    classDef access fill:#e8f2ff,stroke:#7893ad,color:#152b43,stroke-width:1.5px;
    classDef component fill:#ffffff,stroke:#7893ad,color:#152b43,stroke-width:1.5px;
    classDef platform fill:#f4f7fc,stroke:#7893ad,color:#152b43,stroke-width:1.5px;
    class cEdge2 access;
    class cIdentityCatalog2,cS032,cS042,cS052,cS062,cS072,cS082,cS102,cS092 component;
    class cOwnerDb2,cNeo4j2,cOutbox2,cKafka2 platform;
```

### Component Inventory

| Component | Layer | Type | Responsibility |
|---|---|---|---|
| Browser and Thymeleaf application | Presentation | Spring MVC and Thymeleaf | Human sign-in, identity pages through UX01, and catalog and connection routes through EDGE01 |
| UX01 experience API | Presentation | Spring Boot API | Server-side identity composition and fixed downstream calls |
| EDGE01 API edge | Presentation | Spring Boot route controllers | Public routing to implemented owner slices |
| S01 Identity and access | Business logic | Spring Boot owner service | Principals, memberships, scoped grants, and delegations |
| S02 Estate catalog | Business logic | Spring Boot owner service | Services, workloads, journeys, and agent profiles |
| S03 Connections and ingestion | Business logic | Spring Boot owner service | Connections, source bindings, leases, and observation intake |
| S04 Evidence and qualification | Business logic | Spring Boot owner service | Evidence retention, qualification, and owner receipts |
| S05 Objectives and imports | Business logic | Spring Boot owner service | Objective revisions, approvals, and active objective reads |
| S06 Topology and dependencies | Business logic | Spring Boot owner service | Accepted graph revisions, scoped queries, and reconciliation |
| S07 Assessment and learning | Business logic | Spring Boot owner service | Cases, deterministic evaluations, and recommendations |
| S08 Knowledge and retrieval | Business logic | Spring Boot owner service | Reviewed knowledge sources and purpose-bound retrieval |
| S09 Advisory orchestration | Business logic | Partial module and library | Advisory role groundwork; connected run admission and dispatch remain open |
| S10 Decision governance | Business logic | Partial Spring Boot owner service | Policy proposal, review, and read; operational authorization remains open |
| S03 collector | Worker | Leased provider worker | Bounded read-only inventory and metric collection; all providers remain unaccepted |
| S04 qualification worker | Worker | Owner worker | Apply retained schema and qualification policy to evidence |
| S06 graph projector | Worker | Lease-based projection worker | Project accepted revisions and verify Neo4j content |
| S07 assessment worker | Worker | Owner worker | Evaluate fixed cases against retained owner context |
| S09 advisory roles | Agent layer | Analyst, reviewer, and critic | Produce bounded advisory outputs; no operational authority |
| S11 execution and workload control | Target only | Planned owner service and isolated workers | Execute only current, bounded, authorized provider plans |
| S12 recovery assurance | Target only | Planned validation service | Run independent recovery probes and certify declared paths |
| S13 commercial and billing | Target only | Planned owner service | Govern pilots, usage, invoices, and reconciliation |
| S14 audit and notifications | Target only | Planned reporting service | Build attributable activity projections, exports, and delivery history |
| Owner PostgreSQL databases | Data access | Separate owner databases | Transactional authority, migrations, and local outbox records |
| S06 Neo4j projection | Data access | Graph database | Verified read projection; no other service writes it |
| Transactional outbox | Infrastructure | Owner-local persistence pattern | Publish durable events without cross-owner transactions |
| Kafka and object storage | Target only | Planned platform services | Event transport and immutable large-artifact retention |
| Delegation authentication | Cross cutting | Shared Java security library | Validate audience-, resource-, and purpose-scoped service credentials |
| OIDC and Keycloak | Cross cutting | Identity provider | Local development sign-in; production identity provider is deployment-specific |
| Kubernetes and execution cells | Target only | Runtime and network isolation | Separate ingress, control, collection, and privileged execution pools |

The target architecture and service ownership boundaries are documented in
[ARCHITECTURE.md](../design/ms01/ARCHITECTURE.md) and
[DEPLOYMENT_AND_OPERATIONS.md](../design/ms01/DEPLOYMENT_AND_OPERATIONS.md).
The diagram distinguishes those target components from the currently
implemented service slices; it does not mark any provider or full-system
workflow as accepted.

## Verified local sequence: S03 to S04

The following is the local HTTP outbox/inbox path, not the planned Kafka
workflow. The optional S03 publisher uses a narrow S01 delegation. In the
[focused process test](../integration-tests/m2-live/runtime-validation-report.md#test-evidence-and-journeys),
a test proxy returns 503 after S04 persists the first event; S03 retries it,
receives `DUPLICATE`, verifies the acknowledgement event ID, and marks the
outbox row published. S01, S03, and S04 run as real services in that test,
but the directory and OAuth/OIDC components are test fixtures.

```mermaid
%%{init: {"theme": "base", "themeVariables": {"primaryColor": "#e8f2ff", "primaryBorderColor": "#7893ad", "primaryTextColor": "#152b43", "lineColor": "#45617d", "noteBkgColor": "#f4f7fc", "noteBorderColor": "#7893ad", "noteTextColor": "#152b43", "fontFamily": "Arial"}}}%%
sequenceDiagram
    participant S03 as S03 connections
    participant DB03 as S03 database
    participant S01 as S01 identity
    participant Proxy as Test proxy
    participant S04 as S04 evidence
    participant DB04 as S04 database
    S03->>DB03: Commit business record and outbox event
    S03->>S01: Request S04 scoped delegation
    S01-->>S03: Delegation
    S03->>Proxy: Send event
    Proxy->>S04: Forward event
    S04->>DB04: Persist inbox event
    DB04-->>S04: Commit
    S04-->>Proxy: RECEIVED and event ID
    Proxy-->>S03: Inject 503 after persistence
    Note over S03,DB03: Outbox event remains pending
    S03->>Proxy: Retry same event
    Proxy->>S04: Forward retry
    S04->>DB04: Check retained event
    DB04-->>S04: Duplicate
    S04-->>Proxy: DUPLICATE and event ID
    Proxy-->>S03: Forward acknowledgement
    Note over S03: Verify acknowledgement event ID
    S03->>DB03: Mark outbox event published
```

The proxy is test-only. This evidence does not establish Kafka delivery,
production identity, live provider conformance, or full M2 acceptance.
The [connections guide](../services/connections/README.md#s03-outbox-publisher)
describes the publisher's other acknowledgement and retry behavior.

## Target and historical diagram index

The following diagrams describe intended behavior, not verified deployed
workflows. Consult the [implementation status](DELIVERY_CHECKLIST.md) for
what is currently implemented and tested.

- **Activity:** [advisory request to human review](../design/ms01/diagrams/advisory_activity.svg).
- **Sequences:** [onboarding](../design/ms01/diagrams/onboarding.svg),
  [advisory](../design/ms01/diagrams/advisory_sequence.svg),
  [Kafka event delivery](../design/ms01/diagrams/event_delivery.svg),
  [authorized execution](../design/ms01/diagrams/execution_sequence.svg), and
  [recovery validation](../design/ms01/diagrams/recovery_validation.svg).
- **Design and operations:** [domain ownership model](../design/ms01/diagrams/platform_domain_classes.svg),
  [infrastructure](../design/ms01/diagrams/infrastructure_detail.svg), and
  [deployment](../design/ms01/diagrams/deployment_detail.svg).
- **Historical:** [technical-approach graph and runtime illustrations](../design/technical-approach/diagrams/README.md)
  are archival, with known limitations described in their index.

The [full MS01 diagram index](../design/ms01/README.md#diagram-index) includes
additional views and editable sources. Update current-state diagrams alongside
the relevant implementation and test evidence; retain target and historical
artifacts with their provenance rather than presenting them as runtime proof.

### Additional target diagrams

The system-context diagram shows the browser, API edge, experience API,
domain services, and supporting infrastructure:

![UACRF system-context architecture](images/system_context.svg)

The deployment diagram shows the planned deployment and privilege boundaries:

![UACRF deployment and privilege boundaries](images/deployment.svg)
