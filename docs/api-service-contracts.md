# API & Service Communication Contracts

The repository contains 11 Spring MVC applications with more than 100 API and browser routes, plus a non-HTTP advisory module. The service APIs communicate primarily through synchronous REST calls; S03 also publishes through a durable outbox, and S06 processes graph commands asynchronously.

## Service Catalog

| Service | Port | Category | Purpose |
|---|---:|---|---|
| API Edge (`services/api-edge`) | 8080 | API Layer | Routes identity and internal domain APIs to their owning services. |
| Experience API (`services/experience-api`) | 8081 | API Layer | Identity access proxy used by the browser experience. |
| Identity (`services/identity`) | 8082 | Business | Principals, memberships, access decisions, and scoped delegation exchange. |
| Estate Catalog (`services/estate-catalog`) | 8083 | Business | Service, agent-workload, customer-journey, immutable revision, and owner-receipt APIs. |
| Browser Experience (`web`) | 8084 | API Layer | OAuth2-authenticated Thymeleaf pages for identity and catalog workflows. |
| Connections (`services/connections`) | 8085 | Business | Source connections, observations, quarantine, and durable event publication. |
| Evidence (`services/evidence`) | 8086 | Business | Evidence intake, review, qualification receipts, and bounded topology summaries. |
| Objectives (`services/objectives`) | 8087 | Business | Objectives, organization constraints, and review-authority checks. |
| Topology (`services/topology`) | 8088 | Business | Dependency proposals, accepted graph reads, and asynchronous graph commands. |
| Assessment (`services/assessment`) | 8089 | Business | Cases, evaluations, model versions, incidents, learning, and adaptation proposals. |
| Knowledge (`services/knowledge`) | 8090 | Business | Versioned knowledge items and case-bound retrievals. |
| Advisory (`services/advisory`) | Not configured | Business module | Maven module with advisory orchestration code; no HTTP controller or standalone application port was found. |

The HTTP applications use Spring Boot and Spring MVC. Backend service dependencies include PostgreSQL/JDBC and Flyway; S06 additionally uses the Neo4j driver. The API Edge and Experience API use Spring `RestClient`; the browser application uses Spring MVC, Spring Security OAuth2 Client, Thymeleaf, and REST clients.

## API Endpoints Inventory

The codebase has more than 30 routes, so this inventory groups routes by service/controller and shows representative paths and contract types. Route families listed with `**` are pass-through proxies; the owner service remains authoritative for validation and response status.

| Service | Method | Path | Request Type | Response Type |
|---|---|---|---|---|
| Browser Experience | GET | `/`, `/access`, `/contexts`, `/principals`, `/memberships`, `/journeys`, `/journeys/{id}`, `/connections`, `/connections/{id}/inventory`, `/connections/sample-import` | Query parameters | Thymeleaf HTML view or redirect |
| Browser Experience | POST | `/memberships/{membershipId}:revoke`, `/agents`, `/services/{serviceId}/agents`, `/journeys`, `/journeys/{id}/steps`, `/journeys/{journeyId}/services`, `/journeys/{journeyId}/agents/link`, `/connections/sample-import` | Form data | Thymeleaf HTML view or redirect |
| Experience API | GET | `/v1/me/access`, `/v1/me/contexts`, `/v1/identity-providers`, `/v1/principals`, `/v1/memberships`, `/v1/owner-candidates` | Query parameters and bearer token | Identity JSON pass-through |
| Experience API | POST | `/v1/memberships/{id}:revoke`, `/v1/principals/{id}:set-status`, `/internal/v1/delegations` | JSON body and bearer token; idempotency key for mutations | Identity JSON pass-through |
| API Edge | GET, POST | `/v1/me/**`, `/v1/identity-providers`, `/v1/principals`, `/v1/memberships/**`, `/v1/owner-candidates` | Query/body JSON and bearer token | Identity JSON pass-through |
| API Edge | GET, POST | `/internal/catalog/v1/**`, `/internal/connections/v1/**`, `/internal/evidence/v1/**`, `/internal/objectives/v1/**`, `/internal/topology/v1/**`, `/internal/assessment/v1/**`, `/internal/knowledge/v1/**` | JSON pass-through; mutation routes forward bearer and idempotency headers | Owner-service JSON and status pass-through |
| Identity (S01) | GET | `/v1/identity-providers`, `/v1/principals`, `/v1/memberships`, `/v1/me/contexts`, `/v1/me/access`, `/v1/owner-candidates` | Query parameters and bearer JWT | `IdentityResponses` list/page/access records |
| Identity (S01) | POST | `/v1/memberships`, `/v1/principals`, `/v1/grant-decisions`, `/v1/memberships/{id}:revoke`, `/v1/principals/{id}:set-status` | `IdentityRequests` records; mutations use idempotency keys | `IdentityResponses.MutationReceipt` |
| Identity (S01) | POST | `/internal/v1/delegations`, `/internal/v1/delegations:exchange`, `/internal/v1/objective-review-authority:check` | Delegation, exchange, or authority-check JSON | Scoped delegation or authority response |
| Estate Catalog (S02) | GET, POST | `/v1/services`, `/v1/agent-workloads`, `/v1/agent-workloads/{id}/versions`, `/v1/agent-workloads/{id}:activate`, `/v1/customer-journeys/**` | `CatalogRequests` records, query/path parameters, idempotency key for writes | `CatalogResponses` receipts, pages, agents, services, and journeys |
| Estate Catalog (S02) | GET, POST | `/internal/v1/workload-versions/{id}`, `/internal/v1/workloads/{id}:receipt`, `/internal/v1/workloads/{id}:current-receipt`, `/internal/v1/services/{id}:receipt`, `/internal/v1/services/{id}/revisions/{version}` | Delegated bearer JWT; purpose/deadline receipt request where applicable | Workload/service owner receipt or exact immutable revision snapshot; S05-purpose receipts require the delegated principal to equal the workload's current catalog owner |
| Connections (S03) | GET, POST | `/v1/connections/**` including source bindings, observations, paginated collection outcome reports, read-only inventory projection, and quarantine | `ConnectionsRequests` records, query/path parameters, idempotency key for registration | `ConnectionsResponses` pages, records, and ingestion result; observation submission returns 202 |
| Evidence (S04) | GET, POST | `/v1/evidence/**`, `/internal/v1/observations`, `/internal/v1/qualifications/{id}:receipt`, `:measurement`, `:topology-summary`, `/internal/v1/inbox/events` | `EvidenceRequests` records and delegated bearer JWT | Evidence, qualification/measurement/topology-summary receipts, and inbox result; intake/inbox return 202 |
| Objectives (S05) | GET, POST, PUT | `/v1/objectives/**`, `/v1/organization-constraints/{metric}`, `/v1/objective-imports:preview`, `/v1/objective-imports/{id}`, `/internal/v1/workloads/{id}/objectives:receipt` | `ObjectivesRequests` records and query/path parameters; preview admission requires `Idempotency-Key` | Objective, constraint, active-objective receipt, and pending import-command receipt; preview admission is partial and remains `PENDING`; proposal and activation optionally require a current signed S02 owner receipt |
| Topology (S06) | GET, POST | `/v1/dependency-proposals`, `/v1/dependency-proposals:evidence-backed`, `/v1/dependency-proposals/{id}`, `/v1/dependency-proposals/{id}:review`, `/v1/graphs:accept`, `/v1/graphs/{id}/...`, `/internal/v1/graphs/{id}:receipt` | `TopologyRequests` records and query/path parameters | `TopologyResponses` proposals, graph views, impact, and receipt |
| Topology (S06) | POST, GET | `/v1/graphs/subgraphs:select`, `/v1/graphs/differences:classify`, `/v1/commands/{id}` | `TopologyRequests.SelectSubgraph` / `ClassifyDifferences` and idempotency key; command ID path | 202 admission receipt (`PENDING`, `domain_completion=false`); status projection (`command_id`, `status`, `terminal`, `domain_completion`) and, only on `SUCCEEDED`, a bounded advisory result |
| Assessment (S07) | GET, POST | `/v1/cases/**`, `/v1/cases:idempotent-create`, `/v1/evaluations/{id}`, `/v1/recommendations` | `AssessmentRequests` records and query/path parameters; `Idempotency-Key` required on the dedicated creation and `/v1/cases/{id}:idempotent-evaluate` routes | Case, evaluation, recommendation, and receipt records |
| Assessment (S07) | GET, POST | `/v1/model-versions/**`, `/internal/v1/model-evaluation-declarations/**` | Model-version and evaluation-declaration request records | Model version or declaration/page |
| Assessment (S07) | GET, POST | `/v1/incidents/**`, `/v1/learning-records/**`, `/internal/v1/learning-records/{id}:review-receipt`, `/internal/v1/adaptation-proposals/**` | Incident, learning, review, and adaptation request records | Incident, learning/review receipt, or adaptation proposal/page |
| Knowledge (S08) | GET, POST | `/v1/knowledge-items/**`, `/v1/retrievals/**`, `/internal/v1/retrievals/{id}:receipt` | `KnowledgeRequests` records and query/path parameters | Versioned item, retrieval, or retrieval receipt |

Creation routes generally return 201; ordinary reads and synchronous mutations return 200. The asynchronous S06 command endpoints and accepted observation/inbox operations return 202. Validation and authorization failures use service error responses; gateways preserve downstream status except when an upstream connection is unavailable.

S01's `/v1/me/access` includes `capability_expiry_horizon`: the earliest
expiration among the latest active membership for each returned capability
in the selected scope, or `null` when the capability set is empty. Duplicate
grants extend a capability to their latest expiration. This is snapshot
metadata, not a validity guarantee: revocations, additions, principal or
context changes, and token expiry can take effect sooner. It is not a
reservation or a commit fence.
The same response includes `session_expires_at`, the authenticated JWT's
expiration, or null if absent, and `membership_snapshot_digest`, a SHA-256
fingerprint of active memberships in the selected scope. The digest includes
tenant, environment, principal, scope, and ordered membership IDs,
capabilities, scopes, expirations and revisions, using versioned,
length-prefixed UTF-8 fields. S09 requires both expirations to be parseable
and future and the digest to be valid; it rejects changes between its two
S01 reads. The fingerprint is not monotonic and cannot detect a change that
is fully reversed between reads. `membership_generation` supplements the
digest with transactional per-scope mutation counters: a global response
contains a decimal counter, while a service-scoped response contains the
global and selected-service counters separated by `:`. A missing scope starts
at zero; mutation rows remain after membership deletion. The counter and
membership snapshot are read in the same repeatable-read transaction. S09
requires and compares the global counter, detecting committed changes even
when the active snapshot returns to its former state. Natural expiry does not
increment the counter, so the digest and expiry checks still matter. These
checks do not fence changes after the last read; the journal rejects expired
snapshots at method entry, not atomically at commit. Deploy S01's migration
and response changes before the S09 client; older S01 responses fail closed.

S02's active workload-version receipt accepts `profile_id` to identify a
version within its workload. Without it, a version ID matching multiple
visible active workloads returns `AMBIGUOUS_WORKLOAD_VERSION` (409).
Service-scoped callers see only matching service-bound workloads; an
out-of-scope or absent target returns 404. S09 sends `profile_id` and checks
the returned identity. Deploy the S02 change before relying on unambiguous
multi-workload selection by the S09 client.

For `/v1/owner-candidates`, `object_kind=CONNECTION` requires both global
`identity.owner.select` and global `connections.connection.write` in the
selected tenant and environment. Supplying an `object_id` does not permit a
service-scoped connection grant. Results contain eligible human owners and are
paginated like other owner-candidate requests; this lookup does not grant
permissions or register a connection.

`identity.owner.select` may be granted only to an enabled, trusted human
principal at global scope (`*`). An independently authorized approver must
record a matching, time-limited grant decision, and a different membership
manager must apply it; neither may grant the capability to themselves.
The existing decision expiry, single-use, and membership expiry rules apply.
No automatic membership is created.

The browser's explicit sample-import form requires a current eligible owner and
global S03 connection and observation write grants. It imports accepted
synthetic inventory and metric observations, reports quarantine or uncertain
partial results, and replays identical operations for the same user, context,
and owner. No provider is contacted, and no evidence is qualified.

## Management & Observability Endpoints

| Service | Endpoint | Custom Metrics (if any) |
|---|---|---|
| All 11 Spring applications | `/actuator/health` | No custom metric registrations were found; actuator exposure is limited to health. |

## DTOs & Contracts

The owner services define request and response types in service-specific `*Requests` and `*Responses` classes, predominantly as Java records. These records are immutable API carriers; domain persistence models are service-owned and are not gateway DTOs. S01 uses `IdentityRequests`/`IdentityResponses`; S02 uses `CatalogRequests`/`CatalogResponses`; S03 uses `ConnectionsRequests`/`ConnectionsResponses`; S04 uses `EvidenceRequests`/`EvidenceResponses`; S05 uses `ObjectivesRequests`/`ObjectivesResponses`; S06 uses `TopologyRequests`/`TopologyResponses`; S07 uses `AssessmentRequests`/`AssessmentResponses` and related model/learning records; S08 uses `KnowledgeRequests`/`KnowledgeResponses`.

S03's [inventory-observation projection](../services/connections/README.md#read-only-inventory-projection)
is an authorized, on-read view of retained observations, not qualified evidence or a
deduplicated resource inventory. EDGE01 forwards its internal route; the browser application
uses a context-bound S03 read delegation to list connections, inventory observations, and the
paginated collection outcome report. Paginated raw observation payloads are shown only for
`SAMPLE_LOCAL` connections. The report's browser cursor is independent of inventory pagination,
and its status and timestamp semantics are documented in the
[S03 collection outcome report](../services/connections/README.md#collection-outcome-report).

For S06 command status, `TopologyController.commandStatus` returns the compact map produced by `TopologyGraphCommandService.status`: `command_id`, `status`, `terminal`, `domain_completion`, and conditionally `advisory_result`. The declared `TopologyResponses.CommandStatus` record has a different field set and is not used by this handler. The public advisory result exposes bounded selection nodes/edges or bounded classification changes; the internal `s04_summary_coverage` map and evidence summaries are not part of the status response.

The persisted S06 selection coverage map records `graph_edge_count`, `verified_summary_count`, `unverified_summary_count`, up to ten verified `summaries`, `truncated`, `complete`, and `reason_codes` drawn from `SUMMARY_INVALIDATED`, `SUMMARY_STALE`, `SUMMARY_UNAVAILABLE`, `SUMMARY_LIMIT_REACHED`, and `SUMMARY_COVERAGE_GAP`. Difference-classification snapshots retain the corresponding authorized and current evidence-coverage maps; none of these internal coverage fields are returned by `GET /v1/commands/{id}`.

The API Edge and Experience API generally forward JSON as text and preserve owner-service response bodies rather than composing gateway-level DTOs. The browser application uses form inputs and Thymeleaf views. Backend JSON uses snake_case naming where configured. S07's dedicated keyed case-creation route scopes the key to tenant, environment and actor; matching retries return the same case's current state and differing content conflicts. The legacy `/v1/cases` POST is unkeyed. Neither creation route verifies objective or graph references. The MS01 OpenAPI source is [openapi.json](../design/ms01/contracts/source/api_reference/openapi.json); no protobuf or GraphQL schemas were found.

## Communication Patterns

- **Synchronous service calls:** The browser application calls the Experience API for identity operations and the API Edge for catalog operations. The Experience API forwards to S01. The API Edge routes to S01, S02, S03, S04, S05, S06, S07, or S08 without response aggregation.
- **Versioning:** HTTP APIs use path-based `/v1` versioning; service-only routes are placed beneath `/internal`. No header- or query-based API versioning was found.
- **Internal proof and receipt calls:** S06 verifies identity delegation, S02 owner receipts and exact catalog revisions, S04 bounded evidence summaries, and S07 case receipts. When enabled, S05 exchanges the caller's S05 JWT with S01 for an exact purpose/resource S02 delegation, requests the current workload receipt, and verifies its signature and context before proposal or activation. S02 also verifies the caller principal matches the workload's current catalog owner for these S05 purposes. The S05 receipt is point-in-time and is not an atomic fence or persisted by S05. The S07 receipt's `purpose` is the case's business purpose (for example, `incident`); the topology operation purpose is bound and authorized by the S01 delegation exchanged for that exact case ID and is not required to equal the receipt's purpose. S07 also has optional receipt clients for S04/S05/S06; S08 has an optional S07 case-receipt client. These are direct synchronous REST calls, controlled by environment-configurable base URLs.
- **Outbox processing:** S03 can poll its database outbox every 5 seconds (publisher disabled by default), obtain a scoped S01 delegation, then POST events to the S04 inbox with `aggregate_type` from the retained outbox row. S04 scopes sequence and uniqueness by tenant, environment, source service, aggregate type, aggregate ID, and sequence. For older S03 clients that omit the new field, S04 maps the three existing S03 event types to `CONNECTION`, `SOURCE_BINDING`, or `OBSERVATION`; an unrecognized event type without `aggregate_type` is rejected. Failed deliveries remain pending for later cycles; a response with the matching `event_id` and status `RECEIVED`, `DUPLICATE`, or `QUARANTINED` is acknowledged, while a missing or mismatched ID leaves the row pending. S06 uses its own database outbox and scheduled worker for graph commands; no message broker is used for these flows. A 202 receipt reports admission as `PENDING`, while the persisted command starts at `ACCEPTED`. The current worker follows `ACCEPTED` → `RUNNING` → `SUCCEEDED`, with `RUNNING` → `RETRY_SCHEDULED` → `RUNNING` retries and `FAILED` after five attempts or a non-retryable graph-binding failure. The persisted lifecycle schema also permits `WAITING_FOR_DEPENDENCY`, `REJECTED`, `CANCELLED`, `EXPIRED` and `OUTCOME_UNKNOWN`, but the current worker does not transition to these states. `domain_completion` is true only for `SUCCEEDED`; `OUTCOME_UNKNOWN` is nonterminal.
- **S06 command revision identity:** Selection revisions are keyed by `service_id` and `query_id`; graph, catalog, case and evidence bindings retain the changing context, so a new context advances `expected_revision` on that target. Difference-classification revisions are keyed by `service_id` and `authorized_graph_revision_id`; the current graph, retained selection, case and evidence bindings are retained separately, and subsequent comparisons advance that target's `expected_revision`.
- **Timeouts and resilience:** API Edge REST clients use a 2-second connect timeout. The browser REST clients use 2-second connect and 3-second read timeouts; receipt clients use bounded 2-second requests. S05's S01/S02 owner check is constrained by a five-second operation deadline and does not retry. No circuit-breaker or general-purpose retry library/configuration was found. Edge proxy controllers translate upstream connection failures to 502 responses; durable outbox work is retried by later polling cycles.
- **Discovery and gateway composition:** Services use configurable base URLs with localhost defaults; no service-discovery client is configured. API Edge and Experience API are routing/proxy layers, not aggregators; browser pages combine separate calls at the workflow level.
- **Security posture:** Browser routes use OAuth2 login and session cookies. Backend APIs use bearer JWT/resource-server authentication and service/resource-scoped capability checks; S01 integrates with OIDC and issues narrowly scoped delegations. API Edge forwards the caller’s bearer token rather than granting downstream authority. The source config does not enable server-side TLS; local inter-service defaults are HTTP, so production TLS must be provided by deployment/network configuration.

## Service Technology Matrix

| Service | Web | Data Access | Discovery | Gateway | Actuator | Cache | Metrics |
|---|---|---|---|---|---|---|---|
| API Edge | Spring MVC | None | None | Yes | Health | None found | None found |
| Experience API | Spring MVC | None | None | Identity proxy | Health | None found | None found |
| Browser Experience | Spring MVC, Thymeleaf | None | None | Browser UI | Health | None found | None found |
| Identity (S01) | Spring MVC | JDBC, PostgreSQL | None | No | Health | None found | None found |
| Estate Catalog (S02) | Spring MVC | JDBC, PostgreSQL | None | No | Health | None found | None found |
| Connections (S03) | Spring MVC | JDBC, PostgreSQL | None | No | Health | None found | None found |
| Evidence (S04) | Spring MVC | JDBC, PostgreSQL | None | No | Health | None found | None found |
| Objectives (S05) | Spring MVC | JDBC, PostgreSQL | None | No | Health | None found | None found |
| Topology (S06) | Spring MVC | JDBC, PostgreSQL, Neo4j driver | None | No | Health | None found | None found |
| Assessment (S07) | Spring MVC | JDBC, PostgreSQL | None | No | Health | None found | None found |
| Knowledge (S08) | Spring MVC | JDBC, PostgreSQL | None | No | Health | None found | None found |

## Service Communication Sequence

<!-- mermaid-checked: every participant uses `participant Id as "Label"`, no \n in aliases/messages/notes, every alt/opt/loop closed by end, no `:` inside any alias -->
~~~mermaid
sequenceDiagram
    participant Client as "Browser or API Client"
    participant Edge as "API Edge"
    participant S06 as "S06 Topology API"
    participant S01 as "S01 Identity"
    participant S02 as "S02 Catalog"
    participant S07 as "S07 Assessment"
    participant S04 as "S04 Evidence"
    participant DB as "S06 Command Database"
    participant Graph as "Accepted Graph Store"
    participant Worker as "S06 Command Worker"

    Client->>Edge: POST subgraph selection request
    Edge->>S06: Forward bearer token and JSON
    S06->>S01: Exchange scoped proof delegations
    S01-->>S06: Return purpose-bound delegations
    S06->>S02: Verify owner receipt and read exact service revision
    S02-->>S06: Return signed receipt and snapshot digest
    S06->>S07: Verify current sealed case receipt
    S07-->>S06: Return case receipt
    S06->>S04: Read bounded qualified evidence summaries
    S04-->>S06: Return summary metadata and digest
    alt All proofs are valid
        S06->>DB: Commit command and outbox record
        DB-->>S06: Return durable command identifier
        S06-->>Edge: Return 202 command receipt
        Edge-->>Client: Return 202 and status location
    else A required proof is unavailable or stale
        S06-->>Edge: Reject admission without a command receipt
        Edge-->>Client: Return service error
    end
    DB-)Worker: Scheduled outbox polling finds pending command
    Worker->>DB: Claim command under a bounded lease
    Worker->>Graph: Read and verify accepted graph revision
    Graph-->>Worker: Return bounded graph snapshot
    Worker->>DB: Persist advisory result and terminal status
    Client->>Edge: GET command status
    Edge->>S06: Forward status request
    S06->>DB: Read command status and result
    DB-->>S06: Return current status and advisory result
    S06-->>Edge: Return command status
    Edge-->>Client: Return status and result
~~~

~~~mermaid
sequenceDiagram
    participant Client as "Authorized S05 Caller"
    participant S05 as "S05 Objectives"
    participant S01 as "S01 Identity"
    participant S02 as "S02 Catalog"
    participant DB as "S05 PostgreSQL"

    Client->>S05: Propose or activate objective
    S05->>S01: Exchange caller JWT for purpose-bound S02 delegation
    S01->>S01: Check caller capability, global scope and current membership
    S01-->>S05: Return workload-scoped owner-receipt delegation
    S05->>S02: Request current receipt for workload and operation purpose
    S02->>S02: Match caller principal to catalog owner and verify active version/digest
    S02-->>S05: Return signed receipt for audience S05
    S05->>S05: Verify signature, actor, context, resource, purpose and validity
    alt Receipt confirms the caller owns the current eligible workload
        S05->>DB: Persist proposal or activate after proof succeeds
        DB-->>S05: Commit objective transition and outbox event
        S05-->>Client: Return objective result
    else Denied, stale, invalid or unavailable proof
        S05-->>Client: Return error without objective mutation
    end
~~~
