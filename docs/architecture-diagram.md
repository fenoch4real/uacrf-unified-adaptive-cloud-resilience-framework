# Architecture Diagram

This two-layer map connects repository-backed UACRF services to the broader
cloud, agent, and infrastructure ecosystem in the MS01 target. Solid arrows
show implemented or local paths; dotted arrows show target, incomplete, or
unaccepted paths. The [implementation status](IMPLEMENTATION_STATUS.md)
is authoritative for delivery and acceptance claims.

## Application Architecture

<!-- mermaid-checked: no \n, no em-dash/en-dash, no {} in labels, subgraphs are id["label"], arrows are -->|"label"|, all subgraphs closed by end, ids unique -->
```mermaid
flowchart TD
    subgraph People1["People and protected workloads"]
        Operator1["Operators owners and reviewers"]
        Browser1["Browser"]
        CustomerServices1["Customer cloud and platform services"]
        ProtectedAgents1["Protected customer agent runtimes"]
        SharedAgentResources1["Models tools memory identity runtime"]
    end
    subgraph Access1["Browser access and API entry"]
        Web1["Thymeleaf web application"]
        Ux1["UX01 experience API"]
        Edge1["EDGE01 API edge"]
    end
    subgraph CurrentOwners1["Current owner service modules"]
        S01["S01 identity and access slice"]
        S02["S02 estate catalog slice"]
        S03["S03 connections and ingestion slice"]
        S04["S04 evidence and qualification slice"]
        S05["S05 objectives and imports slice"]
        S06["S06 topology and graph slice"]
        S07["S07 assessment and learning slice"]
        S08["S08 knowledge and retrieval slice"]
        S10["S10 decision governance slice"]
    end
    subgraph AdvisoryOwner1["Advisory agent module"]
        S09["S09 advisory agent groundwork"]
    end
    subgraph FutureOwners1["MS01 target owners"]
        S11["S11 execution control planned"]
        S12["S12 recovery assurance planned"]
        S13["S13 commercial billing planned"]
        S14["S14 audit and notifications planned"]
    end
    subgraph Agents1["UACRF advisory agents"]
        AdvisoryRoles1["Analyst reviewer and critic roles"]
        ModelEndpoint1["Approved model endpoint"]
    end
    subgraph Cloud1["Cloud provider and telemetry endpoints"]
        AWS1["AWS Config inventory not accepted"]
        Azure1["Azure Resource Graph inventory not accepted"]
        Gcp1["GCP Asset Inventory not accepted"]
        KubernetesApi1["Kubernetes API source planned"]
        OTel1["OpenTelemetry sources planned"]
        PaymentApi1["Payment provider integration planned"]
    end
    subgraph Platform1["Runtime and state infrastructure"]
        Postgres1[("Separate owner PostgreSQL databases")]
        Neo4j1[("Neo4j S06 graph projection")]
        Kafka1[("Kafka target event platform")]
        ObjectStore1[("Object storage target artifact store")]
        Oidc1["Local Keycloak or external OIDC"]
        KubeRuntime1["Kubernetes production target"]
        ExecCell1["Isolated execution cell planned"]
        Telemetry1["Micrometer and OpenTelemetry target"]
        DelegationLib1["Delegation authentication library"]
    end
    Operator1 -->|"uses"| Browser1
    Browser1 -->|"loads protected pages"| Web1
    Web1 -->|"identity and context requests"| Ux1
    Web1 -->|"local catalog routes"| Edge1
    Ux1 -->|"public identity routes"| Edge1
    Browser1 -->|"OIDC sign in"| Oidc1
    Edge1 -->|"sliced owner routes"| CurrentOwners1
    S01 -->|"issues narrowed delegations"| DelegationLib1
    DelegationLib1 -.->|"validates internal calls"| CurrentOwners1
    CurrentOwners1 -->|"owner local transactions"| Postgres1
    FutureOwners1 -.->|"future owner local databases"| Postgres1
    S06 -->|"verified projection"| Neo4j1
    S03 -.->|"read only inventory and metrics"| AWS1
    S03 -.->|"read only inventory not accepted"| Azure1
    S03 -.->|"read only inventory not accepted"| Gcp1
    S03 -.->|"future workload inventory"| KubernetesApi1
    S03 -.->|"source observations"| OTel1
    CustomerServices1 -.->|"emits telemetry"| OTel1
    S03 -->|"normalized observations"| S04
    S04 -->|"qualified evidence receipts"| S07
    S05 -->|"effective objectives"| S07
    S06 -->|"accepted graph context"| S07
    S06 -->|"bounded objective receipt"| S05
    S07 -.->|"case context"| S09
    S08 -.->|"reviewed citations"| S09
    S09 -.->|"bounded role assignments"| AdvisoryRoles1
    AdvisoryRoles1 -.->|"advisory inference only"| ModelEndpoint1
    S09 -.->|"non executable recommendations"| Operator1
    S10 -.->|"permit and current fences"| S11
    S11 -.->|"bounded authorized plan"| ExecCell1
    ExecCell1 -.->|"scoped provider actions"| AWS1
    ExecCell1 -.->|"scoped provider actions"| Azure1
    ExecCell1 -.->|"scoped provider actions"| Gcp1
    S11 -.->|"controls protected runtimes"| ProtectedAgents1
    S12 -.->|"independent recovery probes"| CustomerServices1
    S12 -.->|"agent recovery validation"| ProtectedAgents1
    ProtectedAgents1 -->|"uses governed dependencies"| SharedAgentResources1
    S02 -->|"registers workloads and profiles"| ProtectedAgents1
    CurrentOwners1 -.->|"owner outbox events"| Kafka1
    FutureOwners1 -.->|"future owner events"| Kafka1
    Kafka1 -.->|"audit projections"| S14
    S14 -.->|"sealed exports"| ObjectStore1
    S13 -.->|"payment reconciliation"| PaymentApi1
    KubeRuntime1 -.->|"hosts isolated service pools"| CurrentOwners1
    KubeRuntime1 -.->|"hosts target owner pools"| FutureOwners1
    KubeRuntime1 -.->|"separates execution pool"| ExecCell1
    Telemetry1 -.->|"service health and traces"| CurrentOwners1
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
| Provider collection | AWS Config and STS; Azure Resource Graph and Monitor; GCP Asset Inventory and Monitoring | Provider-specific; conformance varies | S03 read-only source integrations, not interchangeable capability claims |
| Events and artifacts | Transactional outbox; Kafka and object storage in target design | Target components not deployed by current Compose | Durable cross-owner events and large immutable artifacts |
| Runtime | OCI images and Docker Compose; Kubernetes in target design | Kubernetes version not selected | Local infrastructure and future isolated production service pools |
| Advisory agents | S09 analyst, reviewer, and critic roles | Provider-disabled or incomplete path | Bounded recommendations only; no direct provider authority |

### Data Storage & External Services

Current local infrastructure provides owner-specific PostgreSQL instances,
Neo4j for the S06 projection, and Keycloak for development OIDC. Kafka,
production object storage, and Kubernetes are target architecture components,
not evidence of a deployed runtime. The AWS Config inventory collector has
implementation coverage but AWS connections remain UNACCEPTED pending live
read-only conformance; CloudWatch metric collection is not implemented. Azure
Resource Graph and Google Cloud Asset Inventory have partial read-only
inventory implementations, but Azure and GCP connections also remain
UNACCEPTED pending live conformance. Azure Monitor and Google Cloud Monitoring
metrics, Kubernetes, and OpenTelemetry source integrations remain target work.
Provider credentials and actions are separate: S03 is read-only collection,
while S11 and isolated execution cells are planned for individually authorized
mutations.

### Key Architectural Decisions

- Each domain owner controls its PostgreSQL records and authorization decisions; S06 alone writes its Neo4j projection.
- UACRF advisory agents are separate from protected customer agents. S09 recommendations remain non-executable; S10 authorization and S11 execution are distinct boundaries.
- Cloud integrations are qualified independently by provider and capability. A connector, UI icon, or target component does not establish live conformance.

## Component Relationships

<!-- mermaid-checked: no \n, no em-dash/en-dash, no {} in labels, subgraphs are id["label"], arrows are -->|"label"|, all subgraphs closed by end, ids unique -->
```mermaid
flowchart LR
    subgraph Presentation2["Presentation and transport"]
        cBrowser2["Browser"]
        cWeb2["Thymeleaf web application"]
        cUx2["UX01 experience API"]
        cEdge2["EDGE01 route controllers"]
    end
    subgraph Domain2["Current owner services"]
        cS012["S01 identity"]
        cS022["S02 estate catalog"]
        cS032["S03 connections"]
        cS042["S04 evidence"]
        cS052["S05 objectives"]
        cS062["S06 topology"]
        cS072["S07 assessment"]
        cS082["S08 knowledge"]
        cS102["S10 decision governance"]
    end
    subgraph Advisory2["Advisory module"]
        cS092["S09 advisory groundwork"]
    end
    subgraph Workers2["Owned workers and agent roles"]
        cCollector2["S03 leased collector"]
        cQualifier2["S04 qualification worker"]
        cProjector2["S06 graph projector"]
        cEvaluator2["S07 assessment worker"]
        cAgentRoles2["Analyst reviewer and critic"]
    end
    subgraph Storage2["Persistence and event patterns"]
        cOwnerDb2[("Owner PostgreSQL databases")]
        cNeo4j2[("S06 Neo4j projection")]
        cOutbox2["Owner transactional outbox"]
        cKafka2[("Kafka target only")]
    end
    subgraph CrossCutting2["Cross cutting security and health"]
        cOidc2["OIDC login provider"]
        cDelegation2["Delegation authentication library"]
        cActuator2["Spring Boot health endpoints"]
    end
    cBrowser2 -->|"uses"| cWeb2
    cWeb2 -->|"server side requests"| cUx2
    cUx2 -->|"public API requests"| cEdge2
    cWeb2 -->|"local catalog routes"| cEdge2
    cEdge2 -->|"sliced routes"| cS012
    cEdge2 -->|"sliced routes"| cS022
    cEdge2 -->|"sliced routes"| cS032
    cEdge2 -->|"sliced routes"| cS042
    cEdge2 -->|"sliced routes"| cS052
    cEdge2 -->|"sliced routes"| cS062
    cEdge2 -->|"sliced routes"| cS072
    cEdge2 -->|"sliced routes"| cS082
    cEdge2 -->|"sliced routes"| cS102
    cWeb2 -->|"OIDC sign in"| cOidc2
    cS012 -->|"issues narrowed delegations"| cDelegation2
    cDelegation2 -.->|"validates service tokens"| Domain2
    Domain2 -->|"owner local persistence"| cOwnerDb2
    Domain2 -->|"writes owner events"| cOutbox2
    cS032 -->|"owns collection leases"| cCollector2
    cCollector2 -->|"submits observations"| cS042
    cS042 -->|"qualifies evidence"| cQualifier2
    cQualifier2 -->|"qualified receipts"| cS072
    cS052 -->|"effective objectives"| cS072
    cS062 -->|"projects accepted revisions"| cProjector2
    cProjector2 -->|"verified graph content"| cNeo4j2
    cS072 -->|"evaluates retained context"| cEvaluator2
    cS072 -.->|"case context"| cS092
    cS082 -.->|"reviewed citations"| cS092
    cS092 -.->|"assigns bounded roles"| cAgentRoles2
    cOutbox2 -.->|"target event transport"| cKafka2
    cActuator2 -.->|"readiness and health"| Domain2
```

### Component Inventory

| Component | Layer | Type | Responsibility |
|---|---|---|---|
| Browser and Thymeleaf application | Presentation | Spring MVC and Thymeleaf | Human sign-in, identity pages through UX01, and local catalog routes through EDGE01 |
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
| S03 collector | Worker | Leased provider worker | Read-only collection; AWS remains unaccepted and Azure/GCP are incomplete |
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
