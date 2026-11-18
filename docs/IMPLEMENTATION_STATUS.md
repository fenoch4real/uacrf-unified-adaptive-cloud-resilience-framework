# Implementation status

The target is the complete M0-M8 roadmap in
[`implementation-roadmap.md`](implementation-roadmap.md). No milestone is
complete yet. The repository now contains tested slices across several
services, but they do not yet constitute an accepted end-to-end microservices
system.

The [comprehensive UACRF cloud-ecosystem architecture diagram](../.github/modernize/assessment/engines/facts/architecture-diagram.md)
shows the current services, agents, cloud-provider integrations, and target
infrastructure; dotted connections identify planned or unaccepted paths.

## Milestone and capability tracker

**Status snapshot: 2026-11-17.** This roll-up summarizes the evidence and gaps
recorded in the detailed sections below; it is not a percentage-complete
estimate.

| Milestone | Services and capabilities | Progress | Remaining milestone-level work |
| --- | --- | --- | --- |
| M0 — Implementation baseline | Contracts and ownership, Maven/CI, security and dependency controls, Docker images, bounded recovery-action selection | In progress | Reconcile the full inherited baseline; close remaining threat-model and release-control gaps. |
| M1 — First service workflow | EDGE01, UX01, S01 identity, S02 catalog, browser customer journeys | In progress | Complete authorized catalog journeys without bypassing owner-selection policy; verify deployment identity and end-to-end acceptance. Ownership transfer for journeys and services is implemented and tested, but the live fixture verifies denial only; re-enabling principals remains unavailable pending verification and independent review. |
| M2 — Evidence and topology | S03 connections and ingestion, S04 evidence, shared-schema validation, qualification-policy and gap-waiver governance, S05 objectives, S06 topology and projection | In progress | The focused S01/S05/S06 API runtime gate now passes; complete live conformance for the AWS Config inventory collector (AWS connections remain unaccepted), implement other cloud collectors and telemetry, broaden freshness and ontology coverage, complete full live multi-service acceptance, and implement the first reporting capabilities. |
| M3 — Assessment and advisory | S07 assessment, S08 knowledge and retrieval, S09 advisory orchestration | In progress | Complete connected assessment/knowledge/advisory workflows. S09 currently has contracts and library groundwork, not connected run admission, authorized dispatch, or reconciliation. |
| M4 — Policy and authorization | S10 policy-bundle proposal, review, and read; policy lifecycle and authorization | Partial | Implement policy evaluation, action authorization, current-state fences, and read-only decision replay. The existing S10 slice is governance metadata, not operational authority. |
| M5 — Execution and recovery validation | Bounded action adapter, execution, cancellation, compensation, probes, recovery certification | Not started | Build and independently verify the local adapter and execution lifecycle; qualify any real provider action separately. |
| M6 — Commercial and operational administration | Usage admission, rating, invoicing, payment reconciliation, notifications, evidence export | Not started | Implement and test the commercial and operational workflows, including duplicate/reordered events and interrupted reconciliation. |
| M7 — Governed prediction and provider breadth | Model evaluation and learning; AWS, Azure, and Google Cloud capabilities | Not started | Implement temporal evaluation, uncertainty/calibration and shadow criteria; qualify providers individually. Existing model metadata and provider stubs do not meet this milestone. |
| M8 — Migration and release acceptance | Single-writer cutovers, inherited scenarios, distributed and browser acceptance, deployment/restore/provider/billing evidence | Not started | Complete migration and the release-scope acceptance matrix; retain the required operational evidence. |

**Status meanings:** *In progress* means implementation and tests exist, but
the milestone acceptance criteria remain open. *Partial* means a limited
capability exists while major milestone workflows are not implemented.
*Not started* means no milestone-level implementation is recorded; design
artifacts or preparatory work alone do not count as delivery.

As implementation advances, update this table with the milestone's services,
progress label, evidence, and remaining gaps. Change a milestone to complete
only when its roadmap and acceptance criteria are met; keep detailed test
results and scope limits in the corresponding sections below.

## S10 policy-bundle proposal, review, and read

S10 now has a database-backed initial policy-bundle slice: propose immutable
revisions, list the current revision for a service, and read current or
historical revisions. S01 issues the `authorization.policy.write` and
`authorization.policy.read` capabilities only through its existing explicit
grant flow; no automatic grants were added. S01 now also makes
`authorization.policy.review` grantable only to HUMAN principals and only at
an exact service scope. S10 records immutable reviewer votes through
`POST /v1/authorization-policy-bundles/{id}:decide`: the reviewer must have
that exact-service capability, be listed in `eligible_approver_ids`, and differ
from the proposer. Approval requires an RS256 compact JWS with RFC 8785
canonical claims, verified against an operator-provisioned, versioned
public-key trust set scoped to tenant, environment, service, and reviewer.
Distinct approvals must reach the configured quorum; rejection is unsigned and
terminal. Trust rollback or a contradiction makes approvals ineligible on
reads and blocks new approvals. EDGE01 forwards the fixed internal decide
route. This remains governance metadata only: it creates no action permit,
reservation, or operational authorization, and policy evaluation is not
implemented. Focused S01/S10/EDGE01 and dependency tests passed in the selected
reactor: 222 tests, with no failures, errors, or skips. The MS01 design check
passed 24 checks; these results do not establish live deployment acceptance.

The EDGE01 policy-get route now forwards revision selectors to S10 instead of
silently returning the latest revision. Four PostgreSQL-backed concurrency tests
exercise same-key replay and conflicts and competing first or later revisions;
they cover controlled interleavings on one service instance, not multi-instance
load. The focused edge and S10 suites passed 11 tests.

The S10 image is now included in CI image build/port checks, SBOM and
vulnerability scanning, and release signing. The local all-image smoke test
starts S10 with a distinct database and runtime role, verifies readiness and
database isolation, and confirms EDGE01 forwards its internal route while the
public policy route remains absent. All eleven local application images passed
readiness and route/isolation checks. This is local container evidence; GitHub
Actions were not exercised. Authenticated policy-review operations are covered
by the selected reactor tests with generated RSA keys and test delegations, not
by a live S01/EDGE01/S10 deployment or operator trust configuration. The
selected Maven reactor covering S03/S04/S07 and dependencies passed 225 tests
with no failures, errors or skips.

The MS01 API ownership inventory's `runtime_registered` and
`current_recorded_status` fields describe the inherited source runtime, not
the current microservice implementation. S10 now implements the policy review
decision route described above; the inherited inventory does not imply that
policy evaluation, permits, or operational authorization are implemented.

## S03 observation replay integrity

S03 now compares the complete retained observation, including the original
nanosecond instant, before treating a replay as a duplicate. Conflicting IDs or
reused source sequences are quarantined; submissions for the same observation
ID are serialized across source bindings. Observations created before migration
V3 lack recoverable original timestamp precision and their replays fail closed
as `REPLAY_CONFLICT`. Avoid mixed old/new ingestion instances and rollback
without accounting for the weaker old behavior. The focused connections and
delegation suites passed 20 tests; the MS01 architecture check passed 24
checks. This does not qualify cloud collectors or replace S04 qualification.

## S03 leased collection and AWS Config inventory

The opt-in collection runner claims active source bindings through persisted
per-source leases and checkpoints. Before submission it durably saves each
observation batch, so retries after partial or uncertain intake replay the
same IDs, payloads, timestamps, and sequence values instead of recollecting.
It uses a dedicated OAuth client identity and a short-lived S01 delegation
limited to `connections.observation.write`; it stores no signing key and
remains disabled by default.

AWS connection registration validates an IAM role ARN and creates a unique
per-connection ExternalId, which authorized connection reads expose for
configuring the target role trust policy. The implemented first source is
`aws/<region>/config/inventory`: the collector assumes that role through STS
and queries one AWS Config `SelectResourceConfig` page (up to 100 results) per
run, retaining `NextToken` in the checkpoint. Observations use the Config
capture time and stable IDs derived from source and sequence. Mocked pagination
tests verify that the next page resumes from its returned token with continuous
sequence numbers and distinct IDs. AWS connections remain `UNACCEPTED` until
live read-only conformance; the runner therefore does not schedule them.
CloudWatch metrics have no source/metric-definition
contract yet, and Azure and GCP collectors remain unimplemented. No live AWS
credentials were provided, so real provider conformance is outstanding.
The focused connections/delegation reactor passed 29 tests (21 connections
tests and 8 delegation-auth tests), with no failures, errors, or skips; this is
local test evidence, not live AWS conformance.

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
  until expiry; cross-service owner-gap reporting is also pending. Trusted-
  provider principal admission and approval-gated membership creation are
  implemented in S01. UX01 now provides a CSRF-protected admission form that
  offers only S01-trusted providers and forwards the context-scoped,
  idempotent request through EDGE01. S01 remains authoritative for directory
  subject verification; admission creates no memberships or capabilities.
  UX01 tests cover provider filtering, validation, CSRF, request forwarding,
  and receipt handling. The isolated browser suite verifies S01 admission
  against the local Keycloak directory, confirms the admission receipt reports
  no runtime effect, and verifies that a directory reader without
  `identity.principal.manage` sees no form and receives an S01 denial on a
  CSRF-valid direct submission. The browser now exposes the separate
  approver-decision and membership-manager steps for approval-gated membership
  creation; live coverage verifies both cross-role denials.
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
  neither token is rendered. Existing web tests exercise these views with
  mocked service clients, and the smoke topology verifies EDGE01 reaches both
  S01 and S02, retains each owner's
  unauthenticated response, and does not expose S01's internal delegation
  route. A repeatable Playwright suite under [`e2e/`](../e2e/) now exercises
  the Keycloak OIDC authorization-code flow with PKCE and separate
  browser-facing issuer/internal back-channel endpoints, then context
  selection and current-access evaluation through UX01, EDGE01, and S01. Its
  isolated Compose runner creates disposable PostgreSQL and Keycloak services,
  generates credentials and signing keys outside the repository, and removes
  only its uniquely named containers, network, and volume. All ten browser
  tests pass across three files: administrator and approver access with
  reloads, trusted-directory admission by the authorized administrator,
  read-only principal-directory access and S01 denial of a CSRF-valid direct
  admission submission by the approver, invalid-password rejection for both
  users, unauthenticated-route redirection, local-session logout, and denial
  of journey owner selection plus direct journey-creation and ownership-
  transfer submissions without catalog-write authority. The newly admitted
  principal is absent from the context-scoped principal list because
  admission adds no membership. The
  suite also verifies missing and invalid CSRF tokens cannot select a context
  or end the session, while valid context selection and logout still succeed.
  These checks use local HTTP and do not constitute a complete cross-origin
  or HTTPS review. S01 returned only the
  administrator's seven
  intended identity-management capabilities or the approver's two separately
  seeded capabilities; catalog write access remains denied. S02 is not running
  in this browser fixture, so the denied creation test is not a catalog-side
  persistence check. The checked-in
  membership-create contract requires S01 to verify an
  `authorization_ref` for an independent grant decision. S01 now implements
  that verifier (see "Independent grant authority" below). Do not seed catalog
  memberships or bypass the gate. The initial approver exists, but an
  authorized browser journey is still blocked: the initial administrator
  cannot grant catalog authority to themselves, and `identity.owner.select`
  is not delegable to another catalog user. The user chose to keep that policy
  unchanged; a separate authorization decision is needed to resolve this
  path. The browser also supports draft-journey
  registration: S01 supplies
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
  independently authorized. S02 now also retains immutable workload-version
  manifests, activates them behind a workload revision fence, atomically
  supersedes the prior active version, and exposes only the current active
  version through a `catalog.agent.read`-protected internal receipt. The
  activation check requires an actor different from the version author, but
  the current delegation principal does not establish that the actor is human
  or provide a separate review record.
  Ownership transfer for JOURNEY and SERVICE is implemented through UX01,
  EDGE01's fixed internal catalog route, and S02. At submission, UX01
  revalidates the selected human owner with S01 and obtains a fresh,
  short-lived delegation containing `ownership.transfer` and the target's
  catalog-write capability; S02 checks the eligible-owner claim and expected
  revision, then atomically updates ownership and revision with immutable
  audit evidence, an idempotency response, and an outbox event. SERVICE
  transfers also append a service revision snapshot. Same-owner transfers are
  rejected, and a service referenced as an agent workload's internal anchor
  cannot be transferred; AGENT transfer remains out of scope. S02's
  PostgreSQL-backed tests, EDGE01 route tests, and UX01 tests cover the
  implementation; the targeted suites pass 103 tests (20 S02, 7 EDGE01,
  76 UX01). The live browser fixture verifies that an administrator
  without catalog-write authority cannot open the transfer page or bypass
  authorization with a CSRF-valid direct submission. This is an S01 denial,
  not a successful live transfer or an S02 persistence check; the fixture
  intentionally does not alter the existing authorization policy.
  The selected-context membership page reads S01's active membership pages and
  submits revision-checked, idempotent revocations with an audit reason; S01
  remains authoritative and prevents removal of the final identity
  administrator. The principal directory displays S01's paginated records in
  the selected context and conditionally offers principal admission from
  trusted providers. Admission creates no memberships or capabilities.
  Approval-gated membership creation is exposed through distinct approver and
  membership-manager forms. Identity managers can disable an enabled
  principal with a required, audited reason and the current revision; S01
  remains authoritative and preserves memberships. The browser hides this
  action from readers without `identity.principal.manage`, and S01 denies
  direct unauthorized submissions. Re-enabling remains unavailable pending
  trusted-source verification and independent grant review.
  The rest of the customer workflow remains unimplemented.
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
  started. The latest full verification passed 334 tests with zero failures,
  errors, or skips.
- Java 25 and Docker are available in the development environment. The root
  Maven wrapper pins Maven 3.10.0 and verifies its download; the Docker builder
  image also replaces Maven's vulnerable bundled Guava with checksum-verified
  33.7.2. Maven's local dependency cache is removed from the final builder
  image. The image Trivy gate scans vulnerabilities only, blocking HIGH and
  CRITICAL findings in all five runtime images and the builder image; this has
  passed locally but has not yet run in GitHub Actions.
  The root Docker build context now excludes `private-docs/`. A real Docker
  regression test verifies that private files cannot be copied while ordinary
  source files remain available. Existing builder caches from before this
  exclusion may contain those files if they were present during a build;
  review shared or remote builder access and retention separately.
  Maven is not installed globally. Node.js v26.13.1 is available locally.

## Fresh-build delivery tracking

Delivery is measured only by implemented microservices behavior and tests
executed against this target. The latest full reactor verification passed 334
tests with zero failures, errors, or skips. CI is configured to run the MS01
architecture checker, which verifies checked-in inventory allocation,
contract ownership, and package-manifest hashes for frozen design artifacts.
The checker excludes the two QA files it regenerates and does not assert
runtime acceptance. CI is configured to run an offline Trivy filesystem secret
scan before the Maven build; the identical local command found no secrets. The
scan rejects a generated private-key canary locally and reports finding
locations and rules without printing secret values. The GitHub Actions build
for commit `08ebd73` passed, including the secret scan and Docker-context
checks; it does not cover subsequent uncommitted changes. The 107-route,
153-table and 131-requirement counts describe
inherited planning inventories, not target implementation coverage.
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

- The CI workflow is configured to check the allocations for 144 operations,
  107 registered routes, 153 tables, 131 requirements, and the original 47
  operations, including route operation/contract mapping and duplicate/orphan
  detection. It also checks the design-package manifest's byte counts and
  SHA-256 hashes, except for the two generated QA outputs. This establishes
  consistency only for available artifacts; exhaustive discovery from the
  absent legacy source and live database introspection remain unavailable.
- UX01 now exposes trusted-provider principal admission through the existing
  S01 `POST /v1/principals` route via EDGE01; this does not add the route to
  the canonical operation inventory. Keep the route outside that inventory
  until source and release-boundary evidence supports the decision. The
  approval-gated membership browser flow is implemented as a separate
  approver-issued decision and membership-manager application; it uses the
  existing S01 contracts and does not create a persisted request inbox. The
  existing principal-status route supports disable, and UX01 now exposes the
  action from the selected-context directory with revision and idempotency
  checks. Re-enabling and distributed revocation remain gated by fresh source
  verification and current-authority propagation.
- Threat model and tenant/event compatibility review written
  (`docs/THREAT_MODEL.md`); its open items (key custody, deploy-time signature
  verification, CSRF review, runtime log/event leakage tests, rate limits, and
  event inbox) are tracked there.
- The complementary service-ownership gate is also configured in CI. It checks
  S01/S02 database configuration, Compose admin/runtime credential separation,
  application-role restrictions, service-module dependencies, and qualified
  schema references. Run locally with
  `python3 -m unittest tools.test_check_service_ownership` and
  `python3 tools/check_service_ownership.py`. The image smoke test now also
  proves each runtime database credential is rejected by the other database;
  broader deployment-level isolation evidence remains open.
- Image signing and SBOM attestation: `release.yml` scans before push, then
  pushes, cosign-signs and verifies each image by digest on `v*` tags. It now
  generates an SPDX JSON SBOM from each pushed digest and attaches and verifies
  a cosign attestation against the release workflow identity. Still open:
  first real tag run and signature/attestation enforcement at deployment.
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

UX01 now exposes this contract through two capability-gated browser forms:
`identity.grant.approve` records a decision and displays its 15-minute
authorization reference; a separate `identity.membership.manage` principal
applies the grant using that reference and the same principal, capability,
scope, and expiry. The reference is handed off through the organization's
trusted channel; there is no persisted request inbox or browser-side approval
queue. S01 remains authoritative on every mutation and atomically consumes the
reference with membership creation. The live browser suite verifies the
approver cannot create memberships, the administrator cannot record grant
decisions, and the separately approved membership appears only after the
manager applies it. The focused UX01/experience-api reactor passed 84 tests;
the isolated Playwright suite passed 10 tests. This does not resolve the
catalog owner-selection gate or complete M1 acceptance.

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

The opt-in collection runner claims active source bindings through persisted
per-source checkpoints and expiring leases, invokes only registered
`ProviderCollector`s, and submits bounded batches through the existing S03
intake. It uses a dedicated OAuth client-credentials identity and obtains a
narrow S01 delegation for `connections.observation.write`; a checkpoint
advances only after all intake results are durably accepted, duplicated, or
quarantined. Failed and unimplemented collections retain the prior checkpoint
for retry, and provider-supplied retry times are honored. The runner is disabled
by default. AWS, Azure, and GCP remain
UNACCEPTED, and synthetic sample fixtures are never scheduled as provider
data. The full connections module suite passes 17 tests, including runner,
PostgreSQL lease, and outbox publisher tests. Real provider collection and
deployment identity configuration remain unverified.

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

Still to do around them: credential resolution (no secrets in the repo);
lifting a provider from `UNACCEPTED` only after the live read-only conformance
suite passes for pagination, permission denial, source outage, rate limits,
checkpoint recovery and out-of-order delivery. The sample-data fixture shows
each case. S05 and S06 now have initial service slices; see their sections
below for implemented scope and remaining gaps.

## M2 / S04 evidence, qualification, and waiver workflows

`services/evidence` (port 8086, audience `S04`, schema and database `uacrf_evidence`)
retains evidence events and decides qualification.

- `POST /internal/v1/observations` retains submitted payload text. Admission
  computes its UTF-8 bytes once to verify SHA-256 and byte length. Accepted rows
  retain the byte length alongside the validated payload digest; quarantined rows
  retain the actual digest, claimed digest and byte length. Submit responses expose
  both digests and the length, while evidence reads expose the retained length.
  Migration V7 backfills lengths from the stored UTF-8 text and reconstructs a
  historical quarantine's actual digest only where the reason guarantees the
  claimed digest was previously validated; historical `DIGEST_MISMATCH` rows
  remain unknown. No separate binary payload copy is stored.
  Digest mismatch, replay conflict and stale per-source sequence are quarantined,
  never dropped.
  Exact replays must match source, class, sequence, kind, timestamp at PostgreSQL
  precision, payload and digest. Transaction-scoped ID and source locks serialize
  concurrent submissions. Migration V4 retains source class, timestamp, kind and
  submitter for new quarantine rows; historical metadata remains unknown.
- Sample data (`SAMPLE_LOCAL`) is always `SAMPLE_ONLY` and can never be qualified;
  the database also enforces this and the independent-reviewer rule by CHECK.
- Provider evidence starts `UNVERIFIED`; `POST /v1/evidence/{id}/reviews` needs
  `evidence.qualification.review`, a reviewer who is not the submitter, and the
  expected revision.
- Provider observations are assembled into the canonical versioned observation
  envelope and checked against the shared Draft 2020-12 contract before
  retention. Accepted and quarantined provider records retain `service_id` and
  `schema_version`; schema failures are quarantined as `SOURCE_SCHEMA_INVALID`.
  The payload digest remains over the exact stored payload string. This is
  contract validation, not live provider conformance; sample-local evidence
  remains sample-only.
- Qualification-policy proposals and decisions are validated against the
  proposed OpenAPI request schemas and stored as immutable, tenant- and
  environment-scoped revisions with idempotent writes, authorized reads and
  history. S01 exposes explicit read/write/review grants; review grants are
  restricted to HUMAN principals at an exact service scope. Policy approval and
  `current_eligible` are governance metadata only and never qualify evidence or
  authorize an action.
- Qualification-gap waivers now support proposal, scoped list/read, independent
  review, and revocation. A proposal must name an explicitly waivable requirement
  on the current, approved, unexpired policy revision and carries a bounded
  observation window, gap types, expiry and reason. Approval requires a compact
  RS256 assertion bound to the retained waiver digest and a reviewer-specific,
  operator-provisioned S04 public-key trust set; trust revisions, key identity
  and assertion digests are retained. Waiver records, decisions, revocations and
  idempotency receipts are immutable. S01 exposes separate waiver read/write/
  review/revoke grants, all at exact service scope, with review restricted to
  HUMAN principals. `current_eligible` reflects current policy, waiver expiry,
  revocation and trust state only; waivers do not qualify evidence or authorize
  actions. Configure public trust material with
  `UACRF_QUALIFICATION_WAIVER_TRUST_SET_JSON`; signer private keys remain outside
  S04 and source control.
- `POST /internal/v1/qualifications/{id}:receipt` returns whether evidence is usable
  for a purpose given an expected digest and cutoff.
- `POST /internal/v1/qualifications/{id}:measurement` internally attests parsed
  measurement fields from the qualified, digest-checked owner payload at the cutoff;
  it does not return the raw payload.
- Both receipt types require the observation and its independent qualification
  to be no later than the requested cutoff (the boundary is inclusive). A
  qualified observation timestamped after the cutoff is unusable, with an
  explicit reason; PostgreSQL-backed tests cover both sides of the boundary.
- Both receipt types fail closed with a conflict reason when a retained replay
  conflicts with the accepted ID, or a retained replay or stale sequence conflicts
  with its source and sequence. Unrelated stale sequences and digest mismatches
  alone do not invalidate it. The original qualification state is preserved.
  These checks use the same database statement snapshot as the receipt: previously
  misclassified and unretained conflicts cannot be recovered, and a conflicting
  submission may arrive after a receipt has been issued. Consumers still need
  safeguards for decisions made later.
  PostgreSQL integration tests cover concurrency, timestamp precision, tenant
  isolation, unaffected submissions and migration of legacy quarantines.
- Measurement receipts now require the retained evidence kind to start with
  `measurement.` and have a nonempty suffix, in addition to the existing
  qualification, cutoff, digest and payload checks. Measurement-shaped payloads
  under other evidence kinds remain unusable. This prevents a configuration
  observation from being treated as a measurement; it does not define a broader
  source-kind registry or provider attestation contract.
- `POST /internal/v1/inbox/events` is the consumer-side inbox (digest, duplicate,
  per-aggregate sequence checks, quarantine). A duplicate must match the retained
  source service, event type, aggregate, sequence, payload and digest; reusing an
  ID with changed metadata is quarantined. Transaction-scoped locks serialize
  concurrent deliveries for both event ID and aggregate. New quarantine rows
  retain the event type; rows predating migration V2 have an unknown (`NULL`)
  type rather than a fabricated value. PostgreSQL-backed HTTP tests cover
  changed envelopes, simultaneous conflicting IDs and a V1-to-V2 upgrade with
  existing quarantine data. Migration V3 adds a local `inbox_event_integrity`
  view that identifies accepted events with retained conflicting IDs or
  aggregate sequences, including old quarantine rows. It does not expose a
  service API, establish aggregate-wide eligibility, or detect conflicts that
  were previously misclassified and never retained. `NO_KNOWN_CONFLICT` is a
  point-in-time observation, not proof of future consistency. The S03 publisher
  below delivers to the inbox; retaining an event is not a downstream domain
  effect or broker crash-recovery acceptance. No consumer uses this view yet;
  future consumers must block ambiguous context and address conflicts arriving
  after a check before making decisions.
- S01 registers `evidence.event.read|write`, `evidence.inbox.write` and
  `evidence.qualification.review`, plus
  `evidence.qualification.policy.read|write|review`.
- Wired into Dockerfile, compose, CI, release, ownership check and image smoke test.
- EDGE01 routes only `GET /v1/evidence`, `GET /v1/evidence/{id}` and
  `POST /v1/evidence/{id}/reviews` under `/internal/evidence`; the `/internal/v1`
  intake/qualification endpoints and all qualification-policy routes are
  deliberately not routed through the edge.
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
`UACRF_S03_CLIENT_SECRET` for S03. Operator trust provisioning, live
S01-to-S04 grant/delegation conformance, and live provider conformance remain
outstanding. The focused evidence reactor passed 24 tests (20 evidence HTTP
integration tests and four topology-summary tests); the identity HTTP suite
passed 23 tests, with no failures, errors or skips. This is focused local test
evidence, not full-reactor or live multi-service acceptance; operator trust
provisioning and live S01-to-S04 grant/delegation conformance remain outstanding.

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
- S01 now restricts new `objectives.objective.review` grants to human principals
  and global scope, and refuses new review delegations to non-humans. S05 requires
  global scope and a reviewer distinct from the proposer. Existing memberships
  are not removed: legacy scoped human memberships can still yield scoped
  delegations, which S05 rejects. Previously issued non-human delegations remain
  valid until expiry; see the optional activation recheck below.
- S01 exposes a narrow internal `POST /internal/v1/objective-review-authority:check`
  for a specified reviewer and tenant/environment. A directly authenticated,
  currently admitted SYSTEM caller needs a global `objectives.review-authority.check`
  membership; new grants of that capability require independent approval, global
  scope and a SYSTEM principal. It cannot be delegated. S01 checks in one
  database statement that the caller remains authorized and the reviewer is an
  enabled human with a trusted provider and an unexpired, unrevoked global
  objective-review grant. A successful response is an unsigned point-in-time
  snapshot, not an activation permit or an atomic fence; later revocation can
  change the answer. EDGE01 does not route it.
- If `UACRF_OBJECTIVES_REVIEWER_CHECK_ENABLED=true`, S05 checks the retained
  reviewer's current S01 authority before activating an approved revision. The
  gate defaults to false; when disabled, activation keeps its existing behavior
  and does not perform this check. Configure the `UACRF_OBJECTIVES_REVIEWER_CHECK_*`
  token URL, S01 base URL, client ID and secret, admit the S05 service account
  as a SYSTEM principal, and independently grant it
  `objectives.review-authority.check` in each relevant context.
  The local Keycloak realm now defines the `uacrf-s05-objective-reviewer`
  confidential service-account client with the S01 context/audience scope. Set
  `KEYCLOAK_S05_OBJECTIVE_REVIEWER_CLIENT_SECRET` to the same value as
  `UACRF_OBJECTIVES_REVIEWER_CHECK_CLIENT_SECRET`; the client is not admitted
  or granted S01 authority automatically.
  The token must have S01's audience. URLs require HTTPS; HTTP is accepted only
  for numeric loopback addresses when the explicit loopback flag is true. Both
  calls have two-second timeouts and responses are size-limited. S05 validates
  the returned context, reviewer, status and check time (allowing five seconds
  of clock skew); failure returns `REVIEWER_AUTHORITY_UNAVAILABLE` without
  activating or superseding. The test uses real S05/PostgreSQL and simulated
  S01/OIDC. The check is unsigned and point-in-time: no atomic fence prevents
  revocation after the response, and it does not recheck the activator or run
  at decision time. S05 does not retain the check result as a signed receipt.
- Not yet built: import preview and conflict resolution, workload ownership checks
  through S02, complete current-authority/fencing checks, retired templates,
  material change invalidation of pending actions, and the outbox publisher.

## M2 / S06 topology (first slice)

- `services/topology` (port 8088, audience S06, schema/database `uacrf_topology`) is the sole authority, backed by PostgreSQL. Neo4j is provisioned in Compose for the S06 projection; only S06 writes it. The off-by-default projection worker is lease-based, idempotent, reads back projected content and verifies the accepted revision digest before a graph can be eligible. Reconciliation reports missing/extra topology and makes drifted receipts ineligible.
- Public operations are routed by EDGE01 under `/internal/topology`: `POST/GET /v1/dependency-proposals`, `POST /v1/dependency-proposals:evidence-backed`, `GET /v1/dependency-proposals/{id}`, `POST /v1/dependency-proposals/{id}:review`, `POST /v1/graphs:accept`, `GET /v1/graphs/{id}`, `GET /v1/graphs/{id}/subgraph`, `GET /v1/graphs/{id}/impact`, `GET /v1/graphs/{id}/reconciliation`, `POST /v1/graphs/subgraphs:select`, `POST /v1/graphs/differences:classify`, and `GET /v1/commands/{id}`. The private `POST /internal/v1/graphs/{id}:receipt` endpoint is not routed.
- The evidence-backed path currently accepts only human-authored `agent_workload DEPENDS_ON agent_workload` proposals. S06 obtains short-lived, resource/purpose-bound delegations from S01, signed active-version receipts from S02, and a bounded qualified-evidence summary from S04. It rechecks owner state during proposal, review, and current-use receipt preparation; stale or unavailable evidence fails closed without rewriting historical accepted revisions. Separate S02 RSA signing-key and S06 public-verification-key settings are required before enabling this path. Focused service tests cover each boundary and the EDGE01 route, but a live S01/S02/S04/S06 deployment integration test has not yet been run.
- PostgreSQL owns `permitted_relationships`, `dependency_proposals`, `graph_revisions`, `graph_nodes`, `graph_edges`, `graph_edge_conflicts`, `graph_projections`, and `outbox_event`. New revisions receive a `PENDING` projection row transactionally; existing revisions are backfilled. Projection status is `PENDING`, `PROJECTING`, `PROJECTED`, `FAILED`, or `DRIFTED`; only `PROJECTED` with a matching verified digest is eligible. Proposals are tenant/environment scoped and constrained by a relationship allowlist. Observed proposals require source system, source revision, and verification time; authored proposals forbid those observed-source fields. Reviews use expected revisions and require a reviewer distinct from the proposer.
- Accepting a graph requires the expected current revision for its scope. Accepted proposals are merged deterministically into an immutable graph revision with a canonical digest; new digest version 2 binds edge provenance and proposal IDs, while historical version 1 revisions retain their prior verification semantics. Repeating the same graph content does not create another revision. Conflicting provenance for duplicate accepted relationships is recorded in `graph_edge_conflicts`. Graph acceptance/conflict events are written transactionally to the outbox. The internal receipt reports the projection row's current status and is eligible only after digest verification.
- S02 now retains immutable service-revision snapshots with deterministically ordered `owned_agent_workload_ids`; membership changes append a new revision, and no historical membership is fabricated. S06 verifies the exact signed S02 receipt, retained snapshot and digest, then uses that membership only as a bounded set of selection roots; it never infers dependency edges from catalog membership. Async selection and difference-classification commands are persisted with an outbox event, idempotency, status lookup, and stable expected-revision targets. Selection binds the accepted graph revision, S02 snapshot, purpose-scoped current S07 case receipt and bounded S04 summaries, then retains a digest and bounded typed node/edge references. `action-relevant-dependencies.v1` preserves the bounded dependency/failure-domain selection; `.v2` additionally selects accepted reachable `READS_FROM` edges from represented `service`/`agent_workload` nodes to `shared_resource` nodes. New `.v3` preserves v2 and adds the point-in-time current S05 active-objective set for the S07 case workload as owner-derived context only; it creates no SLO graph node or `GOVERNED_BY_SLO` edge. S06 requires the case workload in the exact S02 service membership and uses a resource-scoped S01 delegation to read and digest-check S05's bounded active-objective receipt. Classification re-reads that set for the same case workload; each owner response is a point-in-time snapshot, and a changed set digest forces `INDETERMINATE` without treating objective changes as graph widening, narrowing or target changes. The retained query ID binds classification to the same selection semantics. Classification emits advisory dependency, failure-domain and v2/v3 data-read deltas; exact same-source read-target replacements are target-changing, while mixed or unpaired read deltas are indeterminate. The public selection result also carries a digest-bound, count-only reconciliation between the exact S02 membership snapshot and `agent_workload` nodes in the exact accepted graph. Unknown S02 membership is `INDETERMINATE`; nodes outside the service membership are counts, not inventory defects. This is not a provider-inventory, freshness or completeness claim. S06 now accepts canonical `MEMBER_OF_FAILURE_DOMAIN` relationships, normalizes legacy `IN_FAILURE_DOMAIN` proposals, and interprets retained legacy edges equivalently. Read-edge properties do not establish freshness. SLO topology relationships and measurement evaluation, broader ontology, and freshness coverage remain incomplete or indeterminate; these operations do not write graph edges. The focused S06/S01 reactor passed 40 tests (31 topology and 9 identity; no failures, errors or skips), and the generated-contract suite passed 6 tests. The new API-only Testcontainers gate then passed three live journeys with separate S01, S05 and S06 HTTP processes and isolated PostgreSQL databases; the full Maven reactor passed 509 tests with no failures, errors or skips. S02, S04 and S07 were test-only owner boundaries; S06 Neo4j projection, Kafka, artifact storage, browser UX, live provider conformance and full M2 acceptance remain unverified. See [the M2 runtime-validation report](../integration-tests/m2-live/runtime-validation-report.md).
- Still needed: SLO graph relationships and objective-to-measurement evaluation,
  broader classify/select ontology and freshness coverage, and
  S10 consumption pinned to an exact graph revision. S06 produces its own
  domain events; they are not routed through S03.

## M3 / S07 assessment (first slice)

- `services/assessment` (port 8089, audience S07, database/schema `uacrf_assessment`) provides fixed assessment cases, deterministic evaluation, and non-executable recommendations. EDGE01 routes the public operations under `/internal/assessment`; the private `/internal/v1/cases/{id}:receipt` endpoint is not routed.
- Case, evaluation and recommendation reads, including internal case receipts and
  material, now require the relevant capability and global (`*`) service scope.
  Previously a service-scoped delegation could read records outside its scope.
  This is deliberately fail-closed: even a scope matching a workload ID is
  denied until S07 can verify workload-to-service ownership, including
  service-bound agents. Global-scoped, tenant/environment-isolated reads remain
  available; no existing grants are automatically widened.
- When `UACRF_ASSESSMENT_RECEIPTS_ENABLED=true`, every evaluation obtains short-lived S01 delegations as the S07 service account and verifies each measurement against the qualified S04 owner payload, current S05 active objectives, and S06 projected graph eligibility. Verification failures force `INSUFFICIENT_EVIDENCE`, retain a reason code and received owner receipt facts in the dependency vector, and cannot produce a recommendation. Verification is off by default; disabled evaluations retain `receipt_verification: SKIPPED` and caller-supplied measurements are not verified. Before enabling it, grant the S07 SYSTEM principal `evidence.event.read`, `objectives.objective.read`, and `topology.graph.read` through normal S01 membership administration.
- S07 now retains structurally valid, explicitly unusable S04 measurement receipts
  for the requested evidence ID and purpose, including the owner's reason and
  nullable measurement fields. They remain `SOURCE_UNVERIFIED` and cannot produce
  a recommendation. Mismatched IDs or purposes are not retained as matching
  rejected receipts. An opt-in integration test runs real S04 and S07 processes
  with separate PostgreSQL databases, test-signed delegations and simulated S01,
  S05 and S06: qualification initially yields a verified evaluation, and a
  later S04 replay conflict makes a new evaluation insufficient while preserving
  the owner reason and the earlier snapshot. It also checks S04 audience and
  tenant denial. Run it with
  `./mvnw -Padvisory-cross-service-tests -pl services/evidence,services/assessment,integration-tests/advisory -am -Dtest=EvidenceAssessmentCrossServiceTests -Dsurefire.failIfNoSpecifiedTests=false package`.
  This does not test real S01 grant issuance or real S05/S06, refresh prior
  evaluations after a conflict, or eliminate conflicts arriving after a receipt.
  Retained receipts remain point-in-time evidence.
- Immutable model-version governance is implemented at `/v1/model-versions`: candidate metadata is immutable and tenant/environment scoped; independent reviews require verified S04/S05/S06 evaluation receipts and a passing workload result for approval. Outbox events are transactional. Normal S01 grants control the distinct `assessment.model.read`, `assessment.model.write`, and `assessment.model.review` capabilities; there are no automatic grants. Commands are disabled by default and can be enabled with `UACRF_ASSESSMENT_MODELS_ENABLED=true`.
- Model review and promotion now reverify each referenced evaluation against current
  S04/S05/S06 owner receipts and require the retained objective set, graph and
  measurement digests/revisions to match. Disabled verification, stale or
  unavailable owners, and incomplete snapshots fail closed; an unsuccessful
  promotion does not supersede the active version. A real S04–S07 process test
  confirms a later evidence conflict blocks both review and promotion, with
  simulated S01/S05/S06. These remote checks are sequential point-in-time reads,
  not atomic authorization; later changes do not automatically deactivate an
  active version or change historical evaluations.
- Before model-review revalidation, S07 now recomputes each retained evaluation's
  result digest and fails closed with `EVALUATION_DIGEST_MISMATCH` when it does
  not match. A PostgreSQL-backed regression test confirms a mismatch cannot
  approve the model or emit the review decision event. The digest still depends
  on current Jackson serialization and is not a versioned canonical format.
- **Model promotion is blocked:** workload evaluations do not bind results to a
  model artifact or prove model accuracy, calibration, or safe operation. Even
  with both feature flags enabled and fresh owner receipts, SHADOW and ADVISORY
  promotion returns `MODEL_EVALUATION_REQUIRED` until model-specific evaluation
  and acceptance are implemented. Existing active rows are not changed or
  automatically deactivated. `APPROVED` and any pre-existing `ACTIVE` status are
  governance metadata, not evidence of model readiness or permission to execute.
  PostgreSQL and separate S04–S07 process tests cover the gate; S01/S05/S06 are
  simulated in the process test.
- Internal model-evaluation declarations retain immutable, tenant/environment-scoped
  lineage bound to the stored model-version digest, with attribution and a
  transactional outbox event. Creation requires `assessment.model.write`,
  global service scope and the model feature flag; reads require
  `assessment.model.read` and remain available when the flag is disabled.
  Declared SHA-256 digests and time windows are validated syntactically, with
  nanosecond precision retained, but artifacts are not fetched or verified.
  `DECLARED_UNVERIFIED` does not prove model performance, data separation or
  readiness and cannot unblock promotion. These endpoints are internal only.
  `GET /internal/v1/model-evaluation-declarations?model_version_id=...`
  lists declarations for one model version in ID order, with `limit` (default
  50, maximum 100) and `after` set to the previous `next_cursor`. Each page is
  a separate read, not a snapshot: declarations inserted before the cursor can
  be missed until the listing is restarted.
- Incident retention is implemented at `/v1/incidents`: creation requires a sealed case for the same workload and a VERIFIED evaluation no later than `observed_from`, with usable qualified S04 measurement receipts and retained S05/S06 receipts. Before insertion, S07 revalidates the current owner receipts for evaluations covering every requested evidence reference. It chooses the newest qualifying evaluations first and fails closed on a selected evaluation's stale, incomplete or unavailable owner evidence, without falling back to older evaluations. Revalidation covers all measurements retained in each selected evaluation, including ones not requested by the incident. It is a sequence of point-in-time owner checks, not an atomic guarantee against later changes. New incidents atomically retain the selected evaluation IDs in `source_evaluation_ids`, in selection order; older incidents report `null` because their exact lineage is unknown. A database insert guard requires every non-null ID to be unique and to reference an evaluation in the same tenant, environment and case; migration fails on existing invalid lineage rather than modifying immutable rows. The guard checks referential integrity, not receipt freshness or evidence coverage, which remain application checks; the nullable column also permits legacy unknown lineage. Existing incidents are not revalidated or rewritten. A real S04–S07 process test verifies that a later S04 replay conflict blocks new incident creation while preserving an earlier incident; S01, S05 and S06 are simulated. Rows are tenant/environment scoped, attributable, revisioned, and immutable. `GET /v1/cases/{id}/incidents`, also routed through EDGE01, lists a case's incidents with `assessment.case.read` and `assessment.incident.read`, global scope and tenant/environment isolation. It returns `items` and `next_cursor` with an ID-ordered keyset cursor, default limit 50 and maximum 100. Pages are independent reads: concurrent inserts before the cursor require restarting to discover them. S01-managed `assessment.incident.read`, `assessment.incident.write`, and `assessment.learning.write` grants are not automatic.
- Learning records are immutable proposals at `/v1/learning-records`, linked to an incident with a revision fence and evidence limited to that incident. Their transactional `uacrf.s07.learning.proposed.v1` outbox event records attribution and PROPOSED state. `GET /v1/learning-records/{id}` reads a retained proposal with an S01-granted `assessment.learning.read` capability and global service scope; it is tenant/environment scoped and is also routed through EDGE01. `GET /v1/incidents/{id}/learning-records`, also routed through EDGE01, lists that incident's proposals with both `assessment.learning.read` and `assessment.incident.read`, global scope and tenant/environment isolation. It returns `items` and `next_cursor` with an ID-ordered keyset cursor, default limit 50 and maximum 100. Pages are independent reads: concurrent inserts before the cursor require restarting to discover them. The read capabilities are not automatically granted.
- An independent human can record one immutable learning review with `POST /v1/learning-records/{id}:review` when `UACRF_ASSESSMENT_LEARNING_REVIEW_ENABLED=true`; the flag defaults to false. S01 restricts `assessment.learning.review` grants to humans with global scope and delegates it only to humans; S07 requires global scope and rejects the proposer, stale revisions and duplicate decisions. Approving requires retained incident provenance, verifies that qualifying evaluations cover the proposal evidence, and rechecks every selected evaluation against current owner receipts. Unknown legacy provenance cannot be approved but can be rejected. Checks are sequential point-in-time reads; later owner changes do not revoke an existing decision. New incidents retain nanosecond observation cutoffs for these comparisons; older rows use their existing database timestamp precision and may fail closed at a sub-microsecond boundary. `GET /v1/learning-records/{id}/review` remains readable with `assessment.learning.read` even when review commands are disabled. The decision and its outbox event are transactional; the proposal remains `PROPOSED`, and approval does not adopt, execute or change any policy, model or workload. A real S04–S07 replay-conflict process test covers the freshness gate with simulated S01, S05 and S06.
- Before approval or review-receipt issuance, S07 checks the retained proposal's typed content against its separately stored incident and evidence references, recomputes its digest and compares that digest with exactly one retained proposal outbox event. Mismatches and missing events fail closed with `LEARNING_INTEGRITY_MISMATCH`; rejection and historical reads remain available. This detects inconsistencies, not malicious coordinated changes to all copies, and does not verify the full event payload. The digest uses the current Jackson serialization rather than a versioned canonical scheme, so serialization changes can cause existing proposals to fail verification.
- Approval and receipt issuance also recheck that the proposal's expected incident revision matches the retained incident and that every proposal evidence reference belongs to that incident. Evidence appearing in a supporting evaluation alone is insufficient. An inconsistent legacy proposal or retained approval fails closed with `INCIDENT_PROVENANCE_INVALID`; historical decisions remain readable.
- Internal `POST /internal/v1/learning-records/{id}:review-receipt` requires `assessment.learning.read`, global scope and the enabled learning-review flag. It returns an unsigned point-in-time verification of an approved review, its proposal and review digests, contributing evaluation IDs and a local check time, with `authorizes_adoption=false`; it is not the target signed owner-receipt protocol. It rechecks the retained review digest against its outbox event and revalidates the evidence with current owners; missing, rejected, changed or unavailable evidence fails closed. It does not check the entire outbox payload or create an authorization. The review digest uses S07's existing Jackson serialization, not a versioned canonical format; serialization changes or removal of the retained event can make receipt verification fail. Checks are sequential and do not prevent subsequent changes. The endpoint is not exposed through EDGE01 or consumed by S10.
- S07 can retain an internal adaptation draft with `POST /internal/v1/adaptation-proposals` when `UACRF_ASSESSMENT_ADAPTATION_ENABLED=true` and learning review is enabled; both default off. Creation requires global-scope `assessment.learning.write` and `assessment.learning.read`, and a fresh successful learning-review verification. It stores that point-in-time check and both learning digests alongside a bounded proposed target type, owner, controlled-version reference, reason, tests, proposed reviewer and rollback criteria. These caller declarations are **unverified**; `DRAFT_UNVERIFIED` does not verify owner, version, reviewer independence, tests or rollback feasibility and does not submit, approve, adopt or execute a change. The immutable tenant/environment-scoped draft and its local `uacrf.s07.adaptation.proposed.v1` outbox event commit together. The event is not published or a permission; its content digest uses current Jackson serialization. `GET /internal/v1/adaptation-proposals/{id}` and paginated `GET /internal/v1/adaptation-proposals?learning_record_id=...` require global-scope `assessment.learning.read` and remain available with either flag disabled. Listing uses ID order, default limit 50, maximum 100 and an `after` cursor; independent pages can miss concurrent inserts before the cursor. Creation requires an `Idempotency-Key` token (1–200 characters, no whitespace), scoped to tenant, environment and actor. Concurrent identical requests return the same retained draft; reuse with different content returns `IDEMPOTENCY_CONFLICT`, and failed creation leaves the key reusable. Replay requires both flags and capabilities but returns historical data without rechecking owners; it is not current approval. Request matching hashes the current Jackson serialization, so serialization changes can cause a replay conflict. No route is exposed via EDGE01, no target-owner workflow is invoked, and later evidence changes do not alter historical drafts; a future submission must revalidate current authority, source evidence and the target owner's own requirements.
- Remaining work: implement forecasts and forecast execution, learning adoption and the outbox publisher, and integrate S10 consumption. Model governance, incident retention, proposal retention and review decisions do not enable model execution or production policy changes.

## M3 / S08 knowledge and retrieval (conservative first slice)

- `services/knowledge` (port 8090, audience S08, database/schema
  `uacrf_knowledge`) owns its own retained material, version lifecycle,
  retrieval manifests, and transactional outbox. Its PostgreSQL database has a
  distinct runtime role and Compose service. S01 issues the six
  `knowledge.*` capabilities; review capability membership is restricted to
  human principals, and S08 review additionally checks capability, global
  scope, independence, expected revision, and deadline.
- `POST /v1/knowledge-items` accepts bounded UTF-8 plaintext only (maximum
  256 KiB), stores a source SHA-256 digest computed by S08, and creates a
  CANDIDATE. `source_ref` is inert metadata and is never fetched. Per-item
  version bodies are immutable; independent review moves candidate versions to
  APPROVED or REJECTED, and a newer candidate does not shadow the previous
  approved version. Classification access derives from explicit S01
  `knowledge.classification.internal.read` and
  `knowledge.classification.restricted.read` grants; callers cannot elevate
  their own clearance by setting `max_classification`. Metadata reads are
  tenant/environment scoped, filtered by classification, and can filter by
  allowed purpose.
- `POST /v1/retrievals` implements exact-term tokenized search only. It
  produces deterministic, non-overlapping chunks of at most 1,000 characters,
  ranks exact query-term matches deterministically, and retains offsets,
  source/excerpt digests, purpose, classification, and a manifest digest.
  Workload-targeted material remains excluded until an owner-verified workload
  scope is implemented; a caller-supplied workload ID is rejected and cannot
  authorize access.
  `GET /v1/retrievals/{id}` rechecks the retained source and excerpt digests
  and only returns excerpt text while its source version remains APPROVED,
  unwithdrawn, before its review deadline, and purpose/classification eligible
  for the current reader. Expired reviews are also excluded from new searches
  and cause S09 eligibility receipts to fail closed.
  Withdrawal blocks new
  retrievals immediately and conceals prior excerpts while retaining
  manifest metadata. Database triggers prevent body, lifecycle, and manifest
  substitution/deletion.
- The internal `POST /internal/v1/retrievals/{id}:receipt` is not routed by
  EDGE01. EDGE01 routes the public metadata, staging, review, withdrawal, and
  retrieval operations under `/internal/knowledge`; the receipt remains
  private. `UACRF_KNOWLEDGE_CASE_RECEIPTS_ENABLED` defaults to `false`; in
  that mode retrievals remain `NOT_VERIFIED`, omit trusted workload binding,
  exclude targeted material, and are never S09-eligible. When explicitly
  enabled, S08 uses the `uacrf-s08-retriever` service account to request the
  narrow S01 S08→S07 `assessment.case.read` delegation and verify the live,
  sealed S07 receipt before creating a manifest. A normal S01 administrator
  must admit the service account as a SYSTEM principal and independently
  grant that capability; no capability is auto-granted. Verified manifests
  bind the S07 case digest and workload ID, can include targeted material only
  for that trusted workload, and become S09-eligible only after the internal
  receipt rechecks the case and every current source/version/excerpt. Mismatches
  and owner outages fail closed. Content and eligible receipt reads reload source
  state after the S07 case check, so a withdrawal committed during that check is
  observed. These remain point-in-time checks; a later withdrawal can still race
  with disclosure. The caller-supplied case identifier is not
  treated as proof. `uacrf.s08.knowledge.review.changed.v1`
  and `uacrf.s08.retrieval.retained.v1` are written transactionally; publishing
  is not implemented.
- Not implemented: PDF conversion, embeddings/external indexes, current
  requester-authority refresh, protected replay, and outbox publishing. The
  receipt binding does not independently establish external requester
  authority or authorize protected inference/recovery use.

## M3 / S09 advisory gateway foundation

- The development API contract now specifies durable asynchronous admission:
  `POST /v1/orchestration-runs` returns 202 with a `Location` only after the run,
  idempotency record, and dispatch-scheduling outbox entry commit atomically.
  Idempotency is scoped to the authenticated principal, tenant, environment,
  operation and key; a same-request replay returns the retained run's current
  state, while changed content conflicts. Source verification remains outside
  locks, and admission requires the context to remain unchanged. This is
  contract-only: no connected handler, run/idempotency/outbox repository or
  current-context commit guard is wired; provider dispatch remains gated and the
  operation remains
  `PENDING_CONNECTED_VERIFICATION`. The MS01 design check passes 24 consistency
  checks; target runtime acceptance remains not evaluated.
- `services/advisory` contains an internal provider-neutral invocation contract
  and a disabled gateway. This is a library foundation, not a deployable service;
  no provider adapter, runtime journal, or orchestration is wired. An unknown
  model outcome is represented separately from a response with usage.
- An unwired OpenAI-compatible chat-completions adapter is restricted to a
  literal-loopback HTTP test endpoint; it has no runtime wiring. Loopback alone
  does not prevent a local endpoint from forwarding to a live provider, so only
  controlled fake endpoints may be used for these tests. The shared simulated
  transport bypasses configured proxies. It sends
  a single bounded text request with `max_completion_tokens`, without streaming
  or tools. It accepts only a complete text response with consistent token usage;
  errors, refusals, truncation, missing usage, and unsupported responses yield
  unknown outcomes. Local simulated-endpoint tests cover request mapping, size
  limits, redirects, failures, and timeouts. This is a narrow protocol slice,
  not verification of any provider or model, runtime budget enforcement, durable
  admission, idempotency, or reconciliation. The adapter has no runtime
  configuration or wiring; the default gateway remains disabled.
- An unwired Microsoft Foundry v1 chat adapter reuses that protocol handling,
  sends the deployment name as `model`, and uses a supplied bearer token.
  When filter results are present it rejects filtered or malformed results,
  but missing results do not prove filtering occurred. It does not acquire
  Entra credentials or support the separate Responses API. Its tests use a
  controlled fake endpoint.
- An unwired Anthropic Messages adapter uses the same simulated-only transport,
  with the `2026-10-21` API-version header and a bounded single-message request.
  It accepts one completed text block and reported token usage; unsupported
  content, refusal or truncation, nonzero cached-token or server-tool usage, and
  malformed responses produce unknown outcomes. Tests use only a local fake
  endpoint.
- An unwired Gemini `generateContent` adapter uses the same simulated-only
  transport, sends a bounded text request with its API key in a header, and
  validates the model path. It accepts a single unblocked text response with
  consistent usage; reported thinking tokens count toward output usage, while
  cached or tool-use tokens and unsupported content fail closed. Local fake
  endpoint tests cover mapping, safety responses, usage and path injection.
- An unwired Ollama native chat adapter sends an unauthenticated, non-streaming
  bounded request with thinking disabled and requires a matching model name,
  completed text and reported usage. It rejects thinking traces, tools, images,
  cached-token use and incomplete responses. Its tests use only a fake endpoint;
  a real local Ollama server can itself forward cloud-model requests and must
  not be used until the dispatch gates are complete.
- An unwired Bedrock Converse adapter uses a controlled fake loopback endpoint,
  a restricted model ID in the path, a bounded text request, and consistent
  reported token usage. It rejects incomplete responses, unsupported content,
  guardrail traces and nonzero cache usage. It does not sign requests, obtain
  AWS credentials, configure guardrails or support ARN model identifiers.
  None of these adapters is qualified against a live provider.
- A standalone budget value validates schema bounds, distinct role/profile
  allocations, and aggregate call, token and cost limits. Journal registration
  now atomically persists and seals those allocations; admission requires the
  matching role/profile, allows one attempt per role, constrains requested
  output ceilings against the allocation and run token budget, and accounts
  reported usage and outstanding output reservations. New admission stops after
  an unknown outcome. It does not verify profile identities, reserve input
  tokens for in-flight calls, verify provider accounting, enforce provider-cost
  usage, or authorize the requester at the run boundary.
- An unwired typed run-request parser accepts bounded, strict UTF-8 JSON with
  the contract's required fields and integer encodings. It checks identifiers,
  workflow names, reason and proposal bounds, and requires each artifact
  reference to match its digest. Deadlines require a known offset, seconds,
  and at most nanosecond precision; unsupported date-time forms fail closed.
  It snapshots proposal IDs and reuses the
  budget value. It requires the exact role set for each workflow version:
  analyst only, analyst plus reviewer, or analyst plus reviewer plus critic.
  It is not an HTTP endpoint, does not verify artifact access, proposal
  eligibility or deadline freshness, and does not authorize or admit a run.
- A separate unwired preparation check binds that request's case and retrieval
  IDs to expected owner receipts, parses caller-supplied model and prompt
  artifacts against its references, digests and scope, and performs the
  requester/full-source check. This composition is also exercised against real
  S01, S07 and S08 with caller-supplied configuration and source bytes. Its
  successful result is only a point-in-time
  consistency snapshot: artifact authorization, independent human review of
  profile activation, proposal eligibility, run admission and provider dispatch
  remain unimplemented.
  A profile-aware variant requires exactly one profile pin and gateway request
  per allocation, obtains the current active-version receipt from S02, and
  binds profile/version identity, manifest digest, model and instruction pins,
  and request limits to that receipt before the final S08 and S01 refreshes.
  It retains immutable copies of the checked requests and selections. The
  cross-service test exercises this composition with real S01, S02, S07 and
  S08 services; this remains a point-in-time check, not an atomic authorization
  decision. Preparation now constructs a distinct,
  bounded JSON input packet per role from the verified sealed case material,
  selected prompt text, and receipt-verified excerpts. It generates each
  gateway request from the checked model and limits rather than forwarding
  caller-supplied free-form context. The prepared snapshot can derive journal
  intents only when caller-supplied run, generation and assignment identifiers
  match its tenant/environment and allocation profiles; each intent uses the
  generated attempt ID and exact packet bytes, with context digest equal to the
  packet-byte digest. This rejects mismatched gateway attempts, models, limits
  or contexts, but only derives intents: it does not persist or admit them.
  Future profile changes, run-boundary authorization, complete graph/evidence
  inputs, and current eligibility beyond point-in-time owner checks remain
  unestablished; provider dispatch remains disabled. Preparation rejects a
  deadline that has already expired and checks again after owner reads and
  packet construction so a request that expires during preparation is not
  returned as ready.
- A standalone consistency check compares S07 case and S08 retrieval receipt
  fields, requiring the same case, workload, purpose and digests, verified
  binding, current eligibility, and a nonempty bounded source list. An unwired,
  disabled-unless-enabled owner client can fetch the receipts using separate
  injected S07/S08 delegations, with bounded responses, timeouts, no redirects,
  and HTTPS (or explicitly enabled literal-loopback HTTP). It also fetches the
  S08 retrieval excerpts and checks the response metadata and ordered results
  against the receipt, including query, creation time, and ranking scores, then
  verifies each excerpt's Unicode code-point length and UTF-8 SHA-256 digest.
  S08 checks its retained manifest digest. S09's unwired owner client and
  full-source verifier independently reconstruct S08's current ordered Jackson
  representation and check that digest; the owner client rejects a mismatch
  before requesting excerpts. Tests exercise it against real S08 responses,
  including Unicode and escaped query text. This is not RFC 8785, and a
  serializer change can make verification fail until the existing digest
  format is explicitly versioned.
  The client has also been tested against a real S08 HTTP instance backed by
  PostgreSQL, including classification and tenant denial, unavailable or changed
  case verification, and source withdrawal. That test uses test-signed S08
  delegations and simulated S07 receipts and case verification; it does not
  exercise live S01 or S07. A separate test uses a real S07 HTTP instance and
  PostgreSQL with test-signed S07 delegations and simulated S08 responses,
  checking sealed-case access, tenant and audience isolation, and mismatched
  case digests. These individual tests do not combine real S01, S07, and S08. The owner
  services' advisory dependencies are test-only. The client now refreshes the
  S08 receipt after reading excerpts and rejects changed, ineligible or
  unavailable results. This refresh has been tested with a real S08 instance
  and a withdrawal after its content response. These separate reads are not
  an atomic authorization or a guarantee of future eligibility. An unwired,
  disabled-unless-enabled S01 delegation client
  can request those narrow S07/S08 capabilities using an injected actor token;
  it does not cache tokens. It has been exercised against a local fake S01 and
  against a real S01 test instance for requester delegations.
  An unwired, separately disabled client-credentials token provider can obtain
  a service actor token using a configured OAuth endpoint and client secret,
  with the same HTTPS/loopback, timeout, response-size, strict JSON and
  no-redirect protections. The provider does not cache tokens; its tests use
  a fake identity provider. Neither client establishes the requester's
  authority or validates that the service principal has been admitted.
  A separate unwired, disabled-unless-enabled requester check asks S01 for
  current global grants using the requester's own token. It requires a human
  principal with `advisory.run.create`, case/retrieval read and the appropriate
  classification capability. S01 permits the new run-creation grant only for
  humans and global scope, through the existing independent grant process; no
  grant is assigned automatically. The check does not cache access and cannot
  itself admit a run, prove access to specific source objects, or guarantee
  authority remains current after the response. S01's grant and revocation
  behavior is tested against a real S01 instance using the advisory requester
  client, including current-grant checks and revocation. S01's tests use a
  test-only advisory dependency; the runtime artifact remains separate.
  An unwired requester-source check combines that snapshot with S07 case
  receipt and material and S08 receipt and excerpt reads using fresh S01
  delegations obtained with the same requester token, rather than a service
  actor. It validates case material before fetching the retrieval, then
  refreshes S08 eligibility after the excerpts. After those reads it checks
  requester grants again and rejects a changed principal; this narrows but does
  not eliminate revocation races. It requests a restricted
  classification delegation for an internal retrieval when that is the
  requester's applicable grant. The combined flow has also been tested against
  a real S01 instance and simulated owners that validate its signed requester
  delegations. The same combined check is also tested against real S07 with
  simulated S01 and S08. It does not authorize access to referenced artifacts
  or guarantee continuing requester authority or source
  eligibility. It does not authorize journal admission or provider dispatch.
  The post-read requester refresh has also been tested against real S01 with
  revocation during a simulated owner content read.
  An optional variant validates caller-supplied full knowledge bytes against
  every excerpt and the manifest before the final S08 eligibility and S01
  requester refreshes. It has been exercised against real S01, S07 and S08,
  including altered bytes, withdrawal and grant revocation. Supplied bytes are
  not proof of artifact ownership or disclosure authorization.
  The combined check shares a five-second monotonic preparation budget across
  requester access, delegation and owner HTTP calls, in addition to the
  two-second per-call bound. Timeout fails closed; it is not a run deadline or
  a guarantee that a remote owner stopped processing the cancelled request.
  An opt-in integration test now runs S01, S07, and S08 as separate local
  processes with separate PostgreSQL databases. It exercises real S01
  delegations, S08's S07 case verification, the requester-source check,
  and caller-supplied full knowledge bytes. A controlled local proxy triggers
  grant revocation or source withdrawal after S08 returns content but before
  the requester receives it; the final S01 or S08 refresh rejects each case.
  These checks do not eliminate later races. The
  OIDC issuer and tokens are test fixtures and identity participants and
  memberships are seeded directly; it does not test their admission or grant
  approval. Run it with
  `./mvnw -Padvisory-cross-service-tests -pl services/identity,services/estate-catalog,services/assessment,services/knowledge,integration-tests/advisory -am clean verify`.
  Operation authorization at the run boundary, runtime identity verification,
  proposal eligibility and provider dispatch remain unimplemented. S02 version
  digests cover the ordered Jackson serialization of the typed manifest, not a
  separately versioned canonical JSON format; registration retains the
  `object_ref` but does not retrieve or verify the referenced artifact bytes.
  Journal admission is a separate fail-closed primitive, not an authorization
  decision.
- S07 now provides an internal, `assessment.case.read`-protected material
  endpoint for sealed cases. It reconstructs the canonical JSON and rejects a
  digest mismatch before returning it. A separate, disabled-unless-enabled S09
  client verifies the UTF-8 SHA-256 digest and case, purpose, workload and
  source-kind consistency with an S07 receipt. Tests exercise it against a
  real S07 instance with test-signed delegations, including tenant isolation.
  S07 reconstructs rather than retaining the original serialized bytes, so a
  serialization change may make existing material unavailable; the mismatch
  fails closed. The client is used by the unwired requester-source check, whose
  resulting case material is included in prepared packets. It does not authorize
  journal admission, verify referenced graph, objective or evidence material,
  or guarantee continuing authorization.
- A standalone content-addressed artifact value checks that an
  `artifact:sha256:` reference, declared digest and retained bytes agree, and
  copies the bytes defensively. An unwired model-configuration parser binds
  requested reference and digest to bounded UTF-8 JSON, checks tenant and
  environment, and validates model entries against the V1 contract. It rejects
  blank or duplicate model references to avoid ambiguity. The parser does not
  load artifacts, authorize models, or enable dispatch. A separate unwired
  request check matches a configured model and checks requested output, timeout
  and declared maximum cost against configuration and allocation limits. It
  does not estimate input tokens, verify prices, enforce provider cost or bind
  the request to a journal intent. An unwired
  prompt parser performs the same artifact, scope, UTF-8 and JSON checks and
  validates the V1 entry shape. It rejects blank text and duplicate instruction
  digests. The contract does not define how `instructions_digest` is calculated
  from prompt text, so that relationship is not verified; neither parser
  establishes approval, provenance or authorization.
  A separate unwired consistency check matches caller-supplied profile version
  model and instruction pins to those configurations and compares allocation
  and request limits with supplied version limits. By itself it does not
  retrieve an activated S02 version, prove the prompt text matches its declared
  digest, or authorize dispatch. The profile-aware preparation variant binds
  these pins to S02's active receipt, but neither path proves prompt provenance
  or authorizes dispatch.
  A batch variant requires exactly one caller-supplied pin and gateway request
  per allocated profile, rejects duplicate attempt IDs and checks each against
  its allocation. These remain caller assertions, not owner authorization;
  only the internally consistent prepared snapshot can derive an intent
  carrying its checked request and exact packet.
  Digest equality does not establish artifact ownership, disclosure permission,
  approval, provenance or current eligibility. An unwired knowledge-source
  verifier checks bounded UTF-8 artifact bytes against an S08 source digest,
  verifies the S08 code-point chunk locator and matches the exact excerpt and
  its digest. A batch check requires matching source bytes for every receipt
  result and has been tested with a real S08 instance and caller-supplied
  source bytes; the S07 receipt and case verification are simulated. It does
  not obtain source bytes or verify their authorization, and it does not verify
  case graph, policy or evidence artifacts. Authorized
  artifact storage and end-to-end full-source verification remain unimplemented.
- An unwired PostgreSQL dispatch-journal primitive commits an intent before
  returning success, prevents duplicate attempts per assignment, and retains
  immutable responses or unknown outcomes without usage. It retains up to 1 MiB
  of exact packet bytes, verifies their SHA-256 on read, and requires the
  context digest to equal the packet-byte digest for new intents. The additive
  intent also retains the checked model, output limit and timeout so a journal
  read can reconstruct the exact gateway request. The additive constraints are
  not validated against legacy rows; old entries without request identity fail
  closed on reconstruction. These digests do not verify the delivery protocol
  or owner receipts. Admission also requires the requested output-token ceiling
  to fit the sealed allocation and the request timeout to finish before the
  run deadline at the database admission timestamp. It also accounts reported
  response tokens and reserved output ceilings against the run token budget,
  and blocks new intents once an attempt has an unknown outcome. This does not
  reserve future input usage or stop already-admitted calls. An intent without
  a result must be reconciled, not automatically dispatched again. A bounded,
  run-scoped snapshot lists retained intents and unknown outcomes for
  reconciliation. A separate snapshot sums known provider-reported token
  counts against the run budget and exposes unknown and unresolved counts;
  usage is unavailable unless known counts already exceed the budget. This is
  diagnostic only: it does not establish actual provider usage or authorize
  retries. Journal reads also
  compare reported input-plus-output tokens against the sealed allocation,
  retaining over-limit responses and reporting unavailable usage for unknown
  outcomes. The journal does not enforce aggregate input-token or provider-cost
  use, verify provider accounting, or determine result eligibility. A
  revision- and generation-checked cancellation fence serializes against journal
  admission;
  run registration and admission also require an unexpired retained deadline,
  checked against the database clock after acquiring the run lock. Outcomes
  retain whether the fence was active, cancelled or expired when recorded;
  concurrent cancellation is serialized. This snapshot is not a current
  eligibility decision: later cancellation and other required checks still
  matter. The journal does not stop a provider call following an already-admitted
  intent or enforce late-result ineligibility. Authorization, protected packet
  handling, verified identity, runtime budget enforcement, result delivery, and
  reconciliation are not implemented.
  It is not a complete runtime journal.
- Planned adapters include Azure AI Foundry, OpenAI, Anthropic, Gemini,
  OpenAI-compatible endpoints, Ollama, and Amazon Bedrock. AgentCore is a
  separate potential agent-hosting integration, not the Bedrock inference API.
  Provider-specific capabilities and credentials must be handled explicitly;
  no live invocation should be enabled before source authorization, bounded
  budgets, and durable dispatch/reconciliation are implemented.

## Next implementation step

M1 now has tested journey and service ownership-transfer paths, but authorized
live catalog mutation acceptance remains blocked by the unchanged
owner-selection grant policy. Do not bypass the independent grant or gated
membership workflow. The next unblocked M2 slice is a real read-only provider
collector connected to S03's opt-in leased runner. Before enabling collection,
provision a dedicated S03 system client with only
`connections.observation.write` and configure its secret outside the
repository. Keep each cloud provider UNACCEPTED until its live conformance
suite verifies pagination, permission denial, outages, rate limits, checkpoint
recovery and out-of-order delivery. Preserve one-owner-per-domain and
no-cross-database-access boundaries.
