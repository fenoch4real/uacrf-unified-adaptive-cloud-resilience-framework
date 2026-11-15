# Architecture Diagram

This view summarizes the current code structure, not the complete MS01 target.
The [implementation status](../../../../../docs/IMPLEMENTATION_STATUS.md) records
which capabilities are incomplete.

## Application Architecture

<!-- mermaid-checked: no \n, no em-dash/en-dash, no {} in labels, subgraphs are id["label"], arrows are -->|"label"|, all subgraphs closed by end, ids unique -->
```mermaid
flowchart TD
    subgraph Client["Client"]
        Browser1["Browser"]
    end
    subgraph Entry["Experience and edge"]
        Web1["Thymeleaf web application"]
        Edge1["API edge"]
        UX1["Experience API"]
    end
    subgraph Domain["Owner services - Spring Boot"]
        Identity1["S01 identity"]
        Catalog1["S02 catalog"]
        Owners1["S03 to S08 owner services"]
        Advisory1["S09 advisory library - unwired"]
    end
    subgraph Data["Owner data"]
        Postgres1[("Separate PostgreSQL databases")]
        Graph1[("S06 Neo4j projection")]
    end
    Idp1["OIDC identity provider"]
    Browser1 -->|"browser requests"| Web1
    Web1 -->|"catalog requests"| Edge1
    Web1 -->|"identity requests"| UX1
    UX1 -->|"public identity routes"| Edge1
    Edge1 --> Identity1
    Edge1 --> Catalog1
    Edge1 --> Owners1
    UX1 -->|"internal delegation"| Identity1
    Identity1 -->|"verify identity"| Idp1
    Identity1 --> Postgres1
    Catalog1 --> Postgres1
    Owners1 --> Postgres1
    Owners1 -->|"S06 only"| Graph1
```

### Technology Stack Summary

| Layer | Technology | Version | Purpose |
|---|---|---|---|
| Runtime | Java, Spring Boot | 25, 4.1.2 | Separately built service modules |
| Web | Thymeleaf | Managed by Spring Boot | Browser-facing pages |
| Owner state | PostgreSQL | Deployment-specific | Service-owned transactional records |
| Graph | Neo4j | 5.26 in local Compose | S06 graph projection |
| Identity | OIDC, Keycloak | 26.8.2 in local Compose | Authentication; S01 owns grants |

### Data Storage & External Services

Local Compose provides separate PostgreSQL instances, a Neo4j projection and
Keycloak. The advisory module is an unwired library; its simulated gateways
are not qualified for live provider dispatch.

### Key Architectural Decisions

- Each owner controls its database, migrations and domain decisions.
- Current identity and source checks do not authorize a new advisory run.

## Component Relationships

<!-- mermaid-checked: no \n, no em-dash/en-dash, no {} in labels, subgraphs are id["label"], arrows are -->|"label"|, all subgraphs closed by end, ids unique -->
```mermaid
flowchart LR
    subgraph Presentation2["Presentation"]
        cEdge2["Edge route controllers"]
        cWeb2["Web controllers"]
    end
    subgraph Domain2["Business logic"]
        cIdentity2["Identity access service"]
        cCatalog2["Catalog service"]
        cAssessment2["Assessment service"]
        cKnowledge2["Knowledge service"]
        cAdvisory2["Advisory preparation - unwired"]
    end
    subgraph Data2["Data access"]
        cIdentityDb2["Identity SQL"]
        cCatalogDb2["Catalog SQL"]
        cAssessmentDb2["Assessment SQL"]
        cKnowledgeDb2["Knowledge SQL"]
    end
    subgraph Infra2["Infrastructure"]
        cSecurity2["Delegation verification"]
        cHttp2["Internal HTTP clients"]
    end
    cWeb2 -->|"routes requests"| cEdge2
    cEdge2 --> cIdentity2
    cEdge2 --> cCatalog2
    cEdge2 --> cAssessment2
    cEdge2 --> cKnowledge2
    cIdentity2 --> cIdentityDb2
    cCatalog2 --> cCatalogDb2
    cAssessment2 --> cAssessmentDb2
    cKnowledge2 --> cKnowledgeDb2
    cAdvisory2 -->|"uses"| cHttp2
    cSecurity2 -.->|"protects"| Domain2
```

### Component Inventory

| Component | Layer | Type | Responsibility |
|---|---|---|---|
| Edge route controllers | Presentation | Spring controllers | Route supported public requests |
| Identity and catalog services | Business logic | Owner services | Grants and workload inventory |
| Assessment and knowledge services | Business logic | Owner services | Cases and reviewed retrieval |
| Advisory preparation | Business logic | Unwired library | Point-in-time consistency checks |
| Owner SQL | Data access | JDBC and migrations | Independent owner persistence |
| Delegation verification | Infrastructure | Security library | Validate scoped service tokens |
