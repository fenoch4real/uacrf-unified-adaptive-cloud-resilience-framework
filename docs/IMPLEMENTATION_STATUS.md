# Implementation status

The target is the complete M0-M8 roadmap in
[`implementation-roadmap.md`](implementation-roadmap.md). No milestone is
complete yet. The initial code is a buildable module skeleton, not an accepted
microservices system.

## Current handoff

- The selected MS01 design, its contracts and inventories, the UI reference,
  and relevant graph/data-schema assets are under [`design/`](../design/).
- The five Spring Boot modules are EDGE01, UX01, S01, S02, and the separate
  Thymeleaf browser application. S02 now has database-backed service,
  agent-workload, and customer-journey registration/read endpoints, with
  tenant-scoped persistence, idempotency records, and outbox events.
- A root Dockerfile builds separate images for all five application modules
  from digest-pinned Java 25 base images. Each runs as UID/GID 10001 and
  exposes its configured application port; CI builds every target and checks
  the non-root user and port metadata, then smoke-tests all five images against
  disposable PostgreSQL instances using generated test keys. In that smoke
  topology, S01 can reach only its identity database and S02 only its catalog
  database; EDGE01, UX01, and the browser app cannot reach either database.
  Fresh database initialization creates distinct superuser admin and restricted
  runtime roles. The smoke test also migrates disposable legacy-role fixtures,
  verifies existing table data and ownership are preserved/transferred, and
  confirms the old bootstrap login is disabled. All five images returned `UP`
  from readiness after startup/migrations. An ephemeral dual-network probe
  confirmed each runtime database credential works only against its owning
  database. The CI workflow is configured to
  generate SPDX SBOMs and scan the five runtime images plus the shared Maven/JDK
  builder image for HIGH and CRITICAL vulnerabilities, retaining reports as
  workflow artifacts; this workflow change has not yet been exercised by
  GitHub Actions. The images are not yet wired into Compose or published or
  signed. This does not verify OIDC login, authorization, or database isolation
  in the deployment configuration.
- EDGE01 now routes the implemented S01 identity GET operations and membership
  revocation and principal disabling to a fixed S01 target, forwards bearer,
  idempotency, body, and scoped query data, and preserves owner status/body and
  authentication challenges without caching responses. UX01's public S01
  reads and writes now target EDGE01; delegation issuance remains a
  server-to-server call to S01's internal endpoint and is not exposed at the
  edge. EDGE01 also forwards the existing delegated S02 catalog reads and
  writes through the explicitly local-only `/internal/catalog/v1/...` route
  prefix; this is not the canonical
  public catalog API. UX01 now provides
  current access and paginated current-context reads as fixed-target BFF
  routes. Principal, membership, and owner-candidate reads, membership
  revocation, principal disabling, and delegation issuance also pass through
  UX01; S01 remains authoritative for validation and mutation. These are
  initial slices, not full public route coverage, edge traffic-limit policies,
  or live service-to-service acceptance.
- S02 authenticates short-lived RS256 delegation JWTs and checks claimed
  capabilities, owners, and runtime principals. S01 now issues narrowed
  delegations after checking the user's current membership authority. The
  local Keycloak realm and manually configured signing keys remain development
  fixtures, and downstream revocation propagation is incomplete; this is not
  production authorization.
- S01 now signs delegations with an RSA private key that is not configured in
  S02; S02 verifies with the matching public key and expected key ID. RSA keys
  shorter than 2048 bits and mismatched pairs are rejected. The delegation-token
  module has focused issuer, audience, key-ID, and maximum-lifetime tests. S02
  has PostgreSQL Testcontainers HTTP integration
  tests for migration, registration, idempotency, tenant separation,
  authorization checks, journey links, and invalid references.
- S01 now has an owner-local PostgreSQL schema for tenants, environments,
  trusted identity providers, principals, scoped memberships, and bootstrap
  audit state. Its opt-in first-admin bootstrap requires a future grant expiry,
  serializes initialization, and refuses an already-populated identity store;
  it accepts HTTPS or loopback HTTP for local development. S01 exposes
  tenant-scoped provider, principal, and current-membership reads alongside
  current-access and owner-candidate APIs. Its paginated current-context
  endpoint lists active contexts from current memberships; selected context IDs
  are selectors only, and S01 rechecks membership when evaluating access.
  Implemented directory reads, owner-candidate listing, membership revocation,
  and delegation accept the selected context pair instead of requiring token
  context claims. S01 issues narrowed, short-lived S02 delegations only after
  checking current membership authority. Membership revocation performs a
  revision-checked, idempotent mutation with audit and outbox records, and
  protects the final membership administrator. A revision-checked, idempotent
  principal-disable operation records a separate audit row and outbox event,
  preserves memberships, and protects the final membership administrator.
  Re-enabling returns 503 until fresh trusted-source verification and
  independent grant review are available. The outbox has no downstream
  revocation consumer yet, so already-issued S02 delegations may remain usable
  until expiry; cross-service owner-gap reporting is also pending. Principal
  admission, membership creation, and ownership-transfer handlers remain
  pending.
- Keycloak is now available as a loopback-only local Compose service with an
  imported development realm. Its UX01 client emits the S01 audience and fixed
  `local-dev` / `local` scope claims; the `uacrf-s01-context` scope also maps
  the Keycloak user ID to the access-token `sub` claim required by S01. The
  import defines the requested profile scope and a reserved `.invalid` demo
  email so first sign-in needs no profile completion. This is a one-tenant
  development fixture, not production identity configuration. The
  browser app now supports OIDC authorization-code sign-in with PKCE, a
  server-side session, and CSRF-protected local sign-out. The OIDC client can
  keep its issuer and browser authorization endpoint externally reachable
  while using service-network token, JWK, and user-info endpoints. Its
  current-access view sends the session-held OIDC access token to UX01, which
  forwards the request through EDGE01 to a fixed S01 target; S01 remains
  authoritative.
  EDGE01 reports S01 connection failures as 502. Catalog reads and writes use
  a short-lived S01 delegation routed through EDGE01 to S02; the browser never
  receives that delegation. UX01 rejects requests without a bearer-shaped
  authorization header and propagates EDGE01's 502 when S01 is unavailable.
  Tokens are not rendered to the browser. The context selector displays
  paginated contexts from S01, revalidates posted selections against S01, and
  stores the selected context only in the server session. Current access then
  passes the selected context back to S01 for membership revalidation.
  Customer-journey list and detail
  views request a short-lived `catalog.journey.read` delegation from S01 and
  pass it server-side through EDGE01's local-only catalog routes to S02;
  neither token is rendered. Browser tests mock these services, and the smoke
  topology verifies EDGE01 reaches both S01 and S02, retains each owner's
  unauthenticated response, and does not expose S01's internal delegation
  route. A   disposable browser test has verified the Keycloak OIDC code flow with PKCE
  and separate browser-facing issuer/internal back-channel endpoints, then
  context selection and current-access evaluation through UX01, EDGE01, and
  S01. The response contained only the bootstrap account's seven intended
  identity-management capabilities; catalog write access remains denied. The
  checked-in membership-create contract requires S01 to verify an
  `authorization_ref` for an independent grant decision. S01 now implements
  that verifier (see "Independent grant authority" below). Do not seed catalog
  memberships or bypass the gate; the authenticated OIDC-to-catalog flow stays
  pending until a second human approver is admitted. The
  browser also supports draft-journey registration: S01 supplies
  paged eligible human owners, the selected owner is revalidated before a
  write delegation is requested, and S02 receives a stable
  `cmd_<owner>_<uuid>` identifier with an idempotency key. The local bootstrap
  does not grant `catalog.journey.write`, so this flow remains denied unless
  that capability is independently authorized. Journey details also provide
  accessible step registration, service registration/linking, and agent-linking
  workflows. Service registration
  revalidates an S01 eligible human owner, requests `catalog.service.write`,
  and submits an idempotent S02 registration; a separate
  `catalog.journey.write` delegation links the created service to the journey
  or a currently listed step. The local bootstrap does not grant either
  catalog write capability, so these operations remain denied unless they are
  independently authorized. Agent workload registration now supports both
  standalone records and records anchored to an existing service. S01's
  service scope is carried in the short-lived delegation. S02 filters service
  and agent reads to that scope, requires matching scope for service/agent
  registration, and requires global scope for journey reads, writes, and
  capability links because journeys are not service-scoped. A V2
  migration preserves deployed V1 schemas while allowing standalone records.
  The browser now offers separate standalone and service-bound registration
  paths; S01 revalidates the selected human owner, and S02 validates the typed
  runtime-principal ID against the narrowed delegation before recording the
  workload. The local bootstrap still does not grant catalog write
  capabilities by default, so these operations remain denied unless they are
  independently authorized.
  The selected-context membership page reads S01's active membership pages and
  submits revision-checked, idempotent revocations with an audit reason; S01
  remains authoritative and prevents removal of the final identity
  administrator. A read-only principal directory now displays S01's paginated
  records in the selected context. The directory remains read-only in the UI;
  no principal-disable control is exposed. General identity admission,
  membership creation, re-enabling, and ownership-transfer handlers, plus the
  rest of the customer workflow, remain unimplemented.
- Local delegation configuration now uses RS256; place the private key only in
  S01 and configure the matching public key and key ID in S01 and S02, as
  described in [`.env.example`](../.env.example). Managed key storage and
  rotation remain unresolved. The S02 verifier requires the explicit
  `service_scope` claim and configured key ID, so deploy the S01 issuer and S02
  verifier as a coordinated change and allow pre-change 30-second tokens to
  expire before relying on service-bound agent registration.
- The local Compose file defines separate identity and catalog PostgreSQL
  instances with persistent volumes, separate superuser admin credentials, and
  restricted S01/S02 runtime credentials. Fresh volumes initialize the
  runtime roles; existing volumes require the explicit, non-data-deleting
  `tools/upgrade_local_database_roles.sh` migration after backup. Persistent
  Compose services have not been started.
- Full reactor verification passes with
  `./mvnw --batch-mode --no-transfer-progress verify`, including PostgreSQL
  Testcontainers HTTP integration tests for S01 and S02. Compose configuration
  validates, and the pinned Keycloak image successfully imported the realm in
  a temporary smoke test. The persistent Compose services have not been
  started. The latest full verification passed 101 tests with zero failures,
  errors, or skips.
- Java 25 and Docker are available in the development environment. The root
  Maven wrapper pins Maven 3.10.0 and verifies its download; the Docker builder
  image also replaces Maven's vulnerable bundled Guava with checksum-verified
  33.7.2. Maven's local dependency cache is removed from the final builder
  image. The CI Trivy gate scans vulnerabilities only, blocking HIGH and
  CRITICAL findings in all five runtime images and the builder image; this has
  passed locally but has not yet run in GitHub Actions.
  Maven is not installed globally. Node.js v26.13.1 is available locally.

## Fresh-build delivery tracking

Delivery is measured only by implemented microservices behavior and tests
executed against this target. The latest full reactor verification passed 101
tests with zero failures, errors, or skips. The architecture checker validates
the checked-in MS01 design artifacts; it does not assert runtime acceptance.
The 107-route, 153-table and 131-requirement counts describe inherited planning
inventories, not target implementation coverage.
The identity integration suite also verifies that revoking a membership blocks
subsequent issuance of an S02-scoped delegation for that membership.

## Patent-reference handling

The seven patent specifications were consulted locally for their
implementation mappings. They remain outside this repository. Their
synchronization sections point to an older Technical Approach alignment
revision than the current revision-22/MS01 design; before implementing any
mapped algorithm, reconcile its schema, state, security, and failure
requirements against the current contracts. The older mapping describes a
read-only initial commercial scope, while the current roadmap includes later,
separately gated execution work. Resolve the release boundary before connecting
provider credentials or making release claims.

## M0 still open

- Finish reconciling the checked-in route, table, requirement, and event
  inventories; this confirms coverage of the available artifacts, not
  exhaustive discovery from the absent legacy source.
- Resolve the remaining identity mutation release gate. The proposed identity
  OpenAPI still marks `POST /v1/principals` PROPOSED outside the canonical
  operation set. The separate proposed principal-status route supports disable
  only; re-enabling and distributed revocation remain gated by source
  verification and current-authority propagation.
- Threat model and tenant/event compatibility review written
  (`docs/THREAT_MODEL.md`); its open items (key custody, deploy-time signature
  verification, CSRF review, secret scanning, rate limits, event inbox) are
  tracked there.
- Add inventory/ownership architecture checks. CI now checks S01/S02 database
  configuration, Compose admin/runtime credential separation, application
  role restrictions, service-module dependencies, and qualified schema
  references; broader inventory ownership coverage remains open. Run locally
  with
  `python3 -m unittest tools.test_check_service_ownership` and
  `python3 tools/check_service_ownership.py`. The image smoke test now also
  proves each runtime database credential is rejected by the other database;
  broader deployment-level isolation evidence remains open.
- Image signing: `release.yml` scans, pushes, cosign-signs and verifies images on
  `v*` tags; scan findings block the release. Still open: first real tag run,
  SBOM attestation, and signature enforcement at deployment.
- First bounded recovery action selected: `workload.set-replicas` with a local
  stub adapter; see `docs/FIRST_RECOVERY_ACTION.md`. The adapter itself is
  built in M5.

## Independent grant authority

S01 exposes `POST /v1/grant-decisions` (requires `identity.grant.approve`) and
`POST /v1/memberships` (requires `identity.membership.manage`), both routed
through EDGE01. `authorization_ref` is the id of a grant decision. Membership
creation succeeds only when the decision exists in the same context, is
unexpired (15 minutes) and unconsumed, matches the exact principal, capability,
scope and expiry, was made by a different principal than the creator and the
target, and its approver still holds a current `identity.grant.approve` grant.
The decision is consumed atomically with the membership, audit event and outbox
event. Grants are limited to a delegable capability set, reject wildcards and
self-grants, and are bounded to 365 days. Admin capabilities are not
delegable through this route. Covered by one S01 integration test and one EDGE01
routing test.

## Trusted-provider principal admission

S01 exposes `POST /v1/principals` (requires `identity.principal.manage`),
routed through EDGE01. The handler verifies the provider is trusted, then reads
the subject from the provider's directory through a server-held connection
(`TrustedDirectory`; Keycloak admin API with a read-only service account,
`uacrf-s01-directory`). Subject, kind and display name come from the verified
directory entry, never from the caller. New principals hold no memberships.
Admission is idempotent, writes an audit row and an outbox event, returns 409
on an id or issuer/subject conflict, 404 for unknown provider or subject, 422
for disabled or inadmissible entries, and 503 `TRUSTED_SOURCE_UNAVAILABLE`
when the directory is unconfigured or unreachable. The directory is disabled
by default (`IDENTITY_DIRECTORY_ENABLED=false`); set the
`IDENTITY_DIRECTORY_*` variables and `KEYCLOAK_DIRECTORY_CLIENT_SECRET` to
enable it. The realm import was checked against Keycloak 26.8.2: the service
account can read users but is refused writes and client listing (it needs the
`kc-admin-api` scope for the admin API audience check). Compose now also passes
`KEYCLOAK_LOCAL_ADMIN_PASSWORD` to Keycloak, which the realm import already
referenced. Covered by one S01 integration test (stub directory), four
Keycloak client tests (mock server), and one EDGE01 routing test.

## First approver

The one-time bootstrap can create a separate first approver (settings in
`docs/SECURITY_AND_CONTRACT_POLICIES.md`). It holds only
`identity.directory.read` and `identity.grant.approve`, differs from the
administrator, and is recorded in `bootstrap_audit` (V6). The administrator
still cannot approve or self-grant. Covered by an S01 integration test showing
the approver can create a decision but cannot create memberships.

## M2 / S03 connections (in progress)

S03 (`services/connections`, port 8085, database `uacrf_connections`) owns
connections and source bindings: `POST/GET /v1/connections`, `GET
/v1/connections/{id}` and `POST/GET /v1/connections/{id}/source-bindings`.
Only provider `SAMPLE_LOCAL` becomes `ACTIVE`; AWS, AZURE and GCP are stored
`UNACCEPTED` with a visible reason and cannot be bound (409). Writes need the
global scope and an eligible owner. S01 issues S03 delegations (audience `S03`,
`connections.connection.read/write`), EDGE01 routes `/internal/connections/**`,
and S03's outbox carries `schema_version` and `payload_digest`. S03 is wired
into Docker, Compose, CI, release, the ownership checker and the image smoke
test (database isolation and the EDGE01 route verified locally).

Observation intake is built (`POST /v1/connections/{id}/observations`, needs
`connections.observation.write` and the global scope): always 202 with
ACCEPTED, DUPLICATE or QUARANTINED (`DIGEST_MISMATCH`, `REPLAY_CONFLICT`,
`STALE_SEQUENCE`); unbound sources get 409. Sequence gaps are accepted.

Sample data lives in `sample-data/` and is generated and validated by Java
(`./mvnw -pl sample-data test`, add `-Duacrf.sample.regenerate=true` to rewrite).
It holds a local estate, a multi-cloud (AWS, Azure, Google Cloud)
provider-shaped estate ingested through `SAMPLE_LOCAL`, and a graph fixture that
covers all 58 node labels and 55 relationship types of the data dictionary.
These are never provider inventory or accepted evidence.

### Where to implement real provider connections (S03)

Each stub lives in `services/connections/src/main/java/org/uacrf/connections/`,
implements `ProviderCollector`, carries `IMPLEMENT:` markers and placeholder
constants, and returns `NOT_IMPLEMENTED` (never fake data):

| Provider | Class | Placeholder constants to replace | Payload kinds to emit |
| --- | --- | --- | --- |
| AWS | `AwsCollector` | `ROLE_ARN`, `METRICS_API`, `INVENTORY_API`, `REGION` | `aws.cloudwatch.metrics`, `aws.config.resources` |
| Azure | `AzureCollector` | `TENANT_ID`, `SUBSCRIPTION_ID`, `METRICS_API`, `INVENTORY_API` | `azure.monitor.metrics`, `azure.resourcegraph.rows` |
| Google Cloud | `GcpCollector` | `PROJECT_ID`, `SERVICE_ACCOUNT`, `METRICS_API`, `INVENTORY_API` | `gcp.monitoring.timeseries`, `gcp.assetinventory.assets` |

Still to do around them: a scheduler that calls `collect` per bound source and
submits batches through the existing intake; credential handling (no secrets in
the repo); lifting a provider from `UNACCEPTED` only after the live read-only
conformance suite passes for pagination, permission denial, source outage, rate
limits, checkpoint recovery and out-of-order delivery. The sample-data fixture
shows each case. Not yet built: S05-S06.

## M2 / S04 evidence and qualification (first slice)

`services/evidence` (port 8086, audience `S04`, schema and database `uacrf_evidence`)
retains evidence events and decides qualification.

- `POST /internal/v1/observations` retains typed source bytes. Digest mismatch,
  replay conflict and stale per-source sequence are quarantined, never dropped.
- Sample data (`SAMPLE_LOCAL`) is always `SAMPLE_ONLY` and can never be qualified;
  the database also enforces this and the independent-reviewer rule by CHECK.
- Provider evidence starts `UNVERIFIED`; `POST /v1/evidence/{id}/reviews` needs
  `evidence.qualification.review`, a reviewer who is not the submitter, and the
  expected revision.
- `POST /internal/v1/qualifications/{id}:receipt` returns whether evidence is usable
  for a purpose given an expected digest and cutoff.
- `POST /internal/v1/inbox/events` is the consumer-side inbox (digest, duplicate,
  per-aggregate sequence checks, quarantine). The S03 publisher below delivers to it.
- S01 registers `evidence.event.read|write`, `evidence.inbox.write` and
  `evidence.qualification.review`.
- Wired into Dockerfile, compose, CI, release, ownership check and image smoke test.
- EDGE01 routes only `GET /v1/evidence`, `GET /v1/evidence/{id}` and
  `POST /v1/evidence/{id}/reviews` under `/internal/evidence`; the `/internal/v1`
  S04 endpoints are deliberately not routed publicly.
- S03 `OutboxPublisher` (off by default, `UACRF_OUTBOX_PUBLISHER_ENABLED`) delivers
  pending `outbox_event` rows to the S04 inbox. It signs in to Keycloak with the
  client-credentials grant, asks S01 for a 30-second S04 delegation limited to
  `evidence.inbox.write`, and marks rows published only on RECEIVED, DUPLICATE or
  QUARANTINED (retained by S04). Failures leave rows pending for retry. The digest is
  recomputed over the exact jsonb text sent. S01 now accepts audience `S04` and
  delegates `evidence.inbox.write` to SYSTEM principals only.
- The Keycloak realm now defines the confidential client `uacrf-s03-publisher`
  (service account id `...0d2`, `uacrf-s01-context` scope, secret from
  `KEYCLOAK_S03_PUBLISHER_CLIENT_SECRET`). Still needed before enabling the publisher:
  admit that service account as a SYSTEM principal through the S01 admission flow,
  grant it `evidence.inbox.write` through an approved grant, and set
  `UACRF_S03_CLIENT_SECRET` for S03. Not yet built: source schemas, policies, gap
  waivers, raw-byte metadata.

## M2 / S05 objectives (first slice)

- `services/objectives` (port 8087, audience S05, schema/database `uacrf_objectives`) owns
  workload objective revisions and mandatory organization constraints.
- Separate transitions with expected revisions: propose (DRAFT) -> submit -> decide
  (APPROVE/REJECT by a principal other than the proposer) -> activate (SUBMITTED,
  APPROVED, ACTIVE). Activation supersedes the active objective for the same
  workload and metric; a partial unique index allows only one ACTIVE per workload and
  metric.
- Targets are exact decimals with a comparator, unit and window. Organization
  constraints (`PUT /v1/organization-constraints/{metric}`) bound unit and target
  range and are enforced at submit and again at activation, so a later rule change
  blocks stale approvals.
- Imported objectives must carry source, definition id, observed revision and
  verification time (database CHECK plus API validation); local ones cannot.
- `POST /internal/v1/workloads/{id}/objectives:receipt` returns the exact active set
  and a set digest. Events (proposed, submitted, decided, activated, superseded) go
  through the transactional outbox; no publisher yet.
- S01 registers the `objectives.*` capabilities and issues S05 delegations. EDGE01
  routes only the public operations under `/internal/objectives`.
- Not yet built: import preview and conflict resolution, workload ownership checks
  through S02, a live S01 authority check on activation, retired templates, material
  change invalidation of pending actions, and the outbox publisher.

## Next implementation step

Public S01 identity reads and writes now pass through EDGE01, while delegation
issuance stays on UX01's private server-to-server path to S01. The local image
smoke test verifies EDGE01 owner reachability plus cross-database credential
rejection. An authenticated local OIDC-to-context-selection-to-S01 access
browser flow has passed; the identity integration test also verifies that
revocation prevents new S02 delegation issuance. A scripted live run over real Keycloak, S01, EDGE01 and S02 passed
(bootstrap approver, directory admission, independent grant decision, single-use
decision, delegated catalog write, revocation blocking delegation). It exposed
and fixed a Spring wiring failure in `KeycloakTrustedDirectory` that unit tests
could not see. Next, exercise the UX01/web browser leg live. Preserve one-owner-per-domain and no-cross-database-access
boundaries.
