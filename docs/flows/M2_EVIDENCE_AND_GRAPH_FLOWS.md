# Milestone 2 Architecture, Sequence, Activity & Deployment Diagrams

## 1. Ingestion, Qualification & Graph Projection Architecture

```mermaid
flowchart LR
    Clouds["GCP / AWS / Azure / OTel / SAMPLE_LOCAL"] --> S03["S03 Discovery & Adapter SPI (:8084)"]
    S03 -->|"Normalized Observations + Edges"| S04["S04 Evidence Qualification (:8085)"]
    S03 -->|"Ontology-Checked Topology"| S06["S06 Sole Neo4j Writer (:8086)"]
    S04 -->|"WORM Object Blobs + Verdicts"| PG_EV[("PostgreSQL evidence / MinIO")]
    S06 -->|"58-Label / 55-Rel Cypher MERGE"| NEO4J[("Neo4j 5.26")]
    S06 -->|"Revision Digests"| PG_GR[("PostgreSQL graph")]
    UX01["UX01 Thymeleaf + Cytoscape.js (:8081)"] --> EDGE01["EDGE01 Gateway (:8080)"]
    EDGE01 --> S03
    EDGE01 --> S04
    EDGE01 --> S06
```

## 2. Evidence Qualification & No-Synthetic-Fallback Sequence Diagram

```mermaid
sequenceDiagram
    actor Owner as Service Owner
    participant UX as UX01 Evidence Hub
    participant EDGE as EDGE01 Gateway
    participant S04 as S04 Evidence Service
    participant QE as QualificationEngine
    participant WORM as Object Lock Blob Store

    Owner->>UX: Submit evidence item (sourceKind, detail, graphRevision)
    UX->>EDGE: POST /v1/evidence/packages/{id}/items
    EDGE->>S04: Forward with X-UACRF-Actor-Id
    S04->>QE: qualify(itemKey, sourceKind, expectedRevision, ttl)
    alt sourceKind is KNOWLEDGE_DOC or ADVISORY_DRAFT
        QE-->>S04: Throw SYNTHETIC_FALLBACK_FORBIDDEN (422)
        S04-->>UX: Flash error (Missing probes stay UNKNOWN)
    else Empirical evidence matches active graphRevision & TTL > 0
        QE-->>S04: QualificationVerdict(QUALIFIED, CURRENT, sha256)
        S04->>WORM: Store immutable payload (worm_locked=true)
        S04->>S04: Recompute package completeness & emit outbox event
        S04-->>UX: Updated EvidencePackageDto
    end
```

## 3. Dependency & AI Lineage Inspection Activity Diagram

```mermaid
flowchart TD
    Start([Open Dependencies or AI Dependencies View]) --> Query["S06 Bounded Traversal (depth 1..3)"]
    Query --> CheckUncertain{"Show uncertain links?"}
    CheckUncertain -- Yes --> IncludeDashed["Render CONFIRMED solid + UNCERTAIN dashed"]
    CheckUncertain -- No --> FilterConfirmed["Render CONFIRMED edges only"]
    IncludeDashed --> DisplayNotice["Display Non-Causation Notice & Cytoscape Graph"]
    FilterConfirmed --> DisplayNotice
    DisplayNotice --> TapEdge["Operator selects edge in graph"]
    TapEdge --> Inspect["Inspect Importance, Source, Owner Confirmation"]
    Inspect --> ConfirmOwner{"Owner confirms dependency?"}
    ConfirmOwner -- Yes --> PostConfirm["POST /v1/graph/edges/{id}/confirm -> CONFIRMED"]
    ConfirmOwner -- No --> KeepVisible["Keep edge flagged for review"]
```

## 4. Local & Target Deployment View (Milestone 2 Addition)

```mermaid
flowchart TB
    subgraph DockerHost["Docker Compose / Kubernetes Namespace"]
        UX01["UX01 (:8081)"] --> EDGE01["EDGE01 (:8080)"]
        EDGE01 --> S03["S03 Discovery (:8084)"]
        EDGE01 --> S04["S04 Evidence (:8085)"]
        EDGE01 --> S06["S06 Graph (:8086)"]
        S03 --> PG[("PostgreSQL 17 (:5432)")]
        S04 --> PG
        S06 --> PG
        S06 --> NEO[("Neo4j 5.26 (:7687)")]
        S04 --> MINIO[("MinIO / S3 Object Lock (:9000)")]
    end
```
