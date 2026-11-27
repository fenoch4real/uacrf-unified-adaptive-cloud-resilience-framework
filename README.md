# UACRF — Unified Adaptive Cloud Resilience Framework

Spring Boot microservices implementation of the **Unified Adaptive Cloud Resilience Framework (UACRF)** covering **Assess**, **Control**, **Recover**, and **Evidence**, backed by **PostgreSQL** (transactional system of record), **Neo4j** (resolved operational topology & graph intelligence), **MinIO / S3** (immutable evidence storage), **Open Policy Agent (OPA)**, **Temporal**, **Keycloak**, and a **Thymeleaf + Bootstrap 5 + Cytoscape.js** web experience.

## Quick Start (Milestone 0)

### Prerequisites
- Docker & Docker Compose (`docker` 29.x + `docker-compose`)
- Java 25 JDK and Maven 3.9+ (or use the Dockerized Maven container below if Java/Maven are not installed locally)

### 1. Start Backing Infrastructure
```bash
docker-compose up -d postgres neo4j minio keycloak opa temporal
```

### 2. Initialize Neo4j Constraints & Indexes
```bash
docker-compose exec -T neo4j cypher-shell -u neo4j -p uacrf_neo4j_dev < contracts/neo4j/01_constraints_and_indexes.cypher
```

### 3. Build & Verify Modules
With local JDK 25 + Maven:
```bash
mvn verify
```
Or using Dockerized Maven:
```bash
docker run --rm -v "$PWD":/workspace -v "$HOME/.m2":/root/.m2 -w /workspace maven:3.9-eclipse-temurin-25 mvn verify
```

## Module Layout
- `libs/uacrf-contracts` — Canonical API/event DTOs, Neo4j 58-label / 55-relationship ontology validator, camelCase/snake_case mappers
- `libs/uacrf-crypto` — Deterministic RFC 8785 JSON canonicalization, SHA-256 digests, RS256 JWS signing & verification
- `libs/uacrf-outbox` — PostgreSQL transactional outbox & lease-fenced worker primitives
- `contracts/` — Neo4j Cypher constraints, ontology JSON catalog, OpenAPI/AsyncAPI specs, traceability register
- `docs/` — Architecture specs, Mermaid sequence/activity/deployment diagrams, and ADRs
