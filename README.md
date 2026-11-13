# Unified Adaptive Cloud Resilience Framework (UACRF)

UACRF is in design and prototyping for assessing the resilience of services
and AI-agent workloads, governing bounded operational actions, and reviewing
recovery evidence. Customer journeys connect those capabilities to outcomes;
services and agents remain separately managed workloads.

This repository records the current product and architecture baseline and the
work planned to implement it. It is not a release of a completed UACRF
microservices system.

## Published paper

The framework is introduced in the following publication:

Fabiyi, O. O., & Yeboah, M. M. (2026). [*UACRF: A Unified Adaptive Framework
for Resilient AI-Cloud Systems Supporting Critical Digital Infrastructure*](https://nahdapublications.org/nid/nid/njbei.2026.0001).
NJBEI Review, 1.0, 1–29. Published September 18, 2026. NID:
nid/njbei.2026.0001.

This repository describes the product and implementation design that builds
on the paper's framework.

## Project status

The repository contains a detailed microservices architecture, supporting
contracts and inventories, a static UI prototype, and an incrementally
implemented Maven multi-module system. The first S01 identity and S02 catalog
APIs have database-backed integration coverage; the target distributed
services have not yet been accepted as a complete system.

The architecture uses Java with Spring Boot, PostgreSQL for authoritative
transactional data, and Neo4j as a verified graph projection. The browser
experience is designed around the existing Bootstrap-based UI. Kafka and
object storage are also part of the target design. See the [architecture
overview](docs/architecture-overview.md) for boundaries and important
constraints.

This is a fresh microservices implementation. The existing Java application
is not a build, runtime, regression, or acceptance dependency. Delivery status
is based on the target services and their executed tests.

## Explore the project

- [Microservices architecture](docs/ARCHITECTURE.md) —
  service boundaries, data ownership, and system behavior.
- [Architecture implementation roadmap](docs/implementation-roadmap.md) —
  staged implementation and migration milestones.
- [UI prototype](design/ui-prototype/ui_workloads/index.html) and [setup guide](design/ui-prototype/ui_workloads/START_HERE.html) —
  illustrative screens and local-only flows.
- [Acceptance criteria and risks](docs/ACCEPTANCE_AND_RISKS.md) —
  planned system-level acceptance tests and principal risks.

The [implementation roadmap](docs/implementation-roadmap.md) summarizes the
planned sequence.

## Important limitations

- The UI prototype uses illustrative local state. Its actions do not call the
  target microservices, persist production data, or perform cloud operations.
- Architecture checks demonstrate allocation and consistency of design
  artifacts, not runtime behavior, production readiness, or completion of
  acceptance tests.
- Cloud-provider integrations, live identity configuration, production
capacity, retention, and commercial policies require implementation decisions
and validation before use.
- This repository does not grant rights to any third-party material. No
software license has been selected; until one is added, do not assume that
the code or documents may be reused or redistributed.

## Implementation starting point

Implementation is planned to begin with milestone M0: preserve and reconcile
the migration baseline, establish the build and CI foundation, and record
security and contract policies. Milestone M1 then establishes the first real
multi-service workflow for identity, catalog, and customer journeys.

See the [implementation plan](docs/IMPLEMENTATION_PLAN.md)
for milestone entry and completion criteria.

See the [implementation status](docs/IMPLEMENTATION_STATUS.md) for the current
handoff and unresolved baseline items.

## Build the initial scaffold

Use Java 25 and run `./mvnw --batch-mode verify` to build and test all five
Spring Boot modules. The checked-in launcher verifies its Maven download.

For the local S01/S02 PostgreSQL instances, copy `.env.example` to `.env`,
replace all placeholder passwords, and configure a local RSA delegation key
pair as described in the file. Keep the private key in the S01 process
environment only; S01 and S02 use the public key and key ID. Then run
`docker compose up -d`. This starts the two
loopback-bound databases and a development-only Keycloak realm at
`http://localhost:8180`. The realm includes a local demo user and fixed
`local-dev` / `local` scope claims. Its `uacrf-s01-context` scope places the
Keycloak user ID in the access-token `sub` claim so S01 can resolve the
principal. This is not suitable for production or multi-tenant use. S01/S02
APIs are run separately and are not started by Compose.

To initialize the local S01 admin, verify the imported realm's demo-user subject
matches `IDENTITY_BOOTSTRAP_SUBJECT`, enable `IDENTITY_BOOTSTRAP_ENABLED`, and
set a future grant expiry plus an operator reason in `.env`. Run S01 once with
those settings and the delegation private key, then disable bootstrap. Run S02
with only the matching delegation public key and key ID. The initial grant is
audited and cannot be used to add more identities.

The PostgreSQL `*_DB_ADMIN_*` credentials are separate superuser accounts;
S01 and S02 connect only with the restricted `*_DB_USER` runtime accounts.
Fresh database volumes create the runtime roles automatically. Existing
volumes are not changed automatically. Before migrating an existing local
volume, back it up and configure distinct admin and runtime credentials in
`.env`. Start only the databases with
`docker compose up -d identity-db catalog-db`, then run
`bash tools/upgrade_local_database_roles.sh <old-identity-bootstrap-user> <old-catalog-bootstrap-user>`,
using the exact PostgreSQL bootstrap usernames that initialized those volumes.
The helper creates/configures the new roles, transfers ownership of existing
application schemas and tables/sequences/views to the runtime roles, and
disables login for each old bootstrap account unless it is also configured as
the new admin. It does not delete database data or volumes. Do not run it
against production databases.

## Building independent application images

The root `Dockerfile` builds each application into a separate image using
digest-pinned Java 25 builder and runtime images. The images run as UID/GID
`10001:10001`; CI builds all targets and verifies the non-root user and exposed
port. The CI workflow is configured to generate SPDX SBOMs and scan each
runtime image and the shared Maven/JDK builder image for HIGH and CRITICAL
vulnerabilities. The blocking Trivy scan is vulnerability-only; it does not
perform a secrets scan. SBOM and scan reports are retained as workflow
artifacts; images are not published or signed.

| Target | Image | Port |
| --- | --- | ---: |
| `api-edge` | `uacrf/api-edge:local` | 8080 |
| `experience-api` | `uacrf/experience-api:local` | 8081 |
| `identity` | `uacrf/identity-service:local` | 8082 |
| `estate-catalog` | `uacrf/estate-catalog-service:local` | 8083 |
| `web` | `uacrf/web:local` | 8084 |

Build an image with
`docker build --build-arg BUILD_REVISION=local --target api-edge --tag uacrf/api-edge:local .`,
replacing the target and tag from the table as needed. Runtime settings and
secrets must be supplied by the deployment environment; they are not copied
into the image. Each application exposes Actuator liveness and readiness
health groups. Compose still starts only the databases and Keycloak, not the
application images.

Run `bash tools/smoke_application_images.sh local` to start all five local
images against disposable PostgreSQL containers and generated test signing
keys, then probe their readiness endpoints. It puts S01 and S02 on separate
owner-data networks and verifies each can reach its own database but not the
other's; EDGE01, UX01, and the browser app cannot reach either database. The
script cleans up its containers, networks, and temporary keys. This verifies
startup, migrations, and the smoke topology's database network boundary, not
OIDC login or authorization in the deployment configuration.

Start EDGE01 with
`./mvnw --batch-mode -pl services/api-edge spring-boot:run` for the initial
S01 identity-read routes (`/v1/me/access`, `/v1/me/contexts`,
`/v1/identity-providers`, `/v1/principals`, `/v1/memberships`, and
`/v1/owner-candidates`) and membership revocation
(`POST /v1/memberships/{id}:revoke`) plus principal disabling
(`POST /v1/principals/{id}:set-status`). Responses are non-cacheable. EDGE01
also forwards the browser's delegated S02 catalog reads and writes under the
local-only `/internal/catalog/v1/...` path; this is not the canonical public
catalog contract. Principal re-enabling fails closed until trusted-source
verification and grant-review verification are integrated; other identity
write routes and edge traffic-limit policies are not implemented yet. With
Keycloak running, start UX01 using
`./mvnw --batch-mode -pl services/experience-api spring-boot:run`, then start
the browser experience using
`./mvnw --batch-mode -pl web spring-boot:run` and open
`http://localhost:8084`. The local sign-in uses OIDC authorization code with
PKCE and a server-side session. Signing out clears the UACRF session but may
leave the Keycloak session active. Sign-in establishes identity only; it does
not grant service permissions. The browser can select an active tenant and
environment returned by S01; S01 rechecks current membership when it evaluates
access. This is not a production identity-admission or tenant-provisioning flow.
The development session cookie is HttpOnly and SameSite=Lax. Set
`UACRF_SESSION_COOKIE_SECURE=true` when serving the browser app over HTTPS.
The current-access view and context selector call UX01 at
`http://localhost:8081`, which forwards the session-held bearer token and
context request through EDGE01 at `http://localhost:8080` to S01; S01 remains
the authority for identity and access decisions. Principal, membership, and
owner-candidate reads, membership revocation, principal disabling, and
delegation requests also pass through UX01. Public identity reads and writes
use EDGE01; delegation issuance remains a server-to-server request from UX01
to S01's internal endpoint rather than exposing that endpoint at the edge.
S01 remains the owner and validates the user token, selected context, and
mutation authority. Start S01 with the local identity database, RSA delegation
key pair, and bootstrap configured. Contexts are paginated, and
browser-submitted context IDs are revalidated against S01 before being stored
in the server session. If S01 is unavailable, the page reports the failure and
does not display prior access.
S01 delegation can also take the selected context as a selector and revalidates
membership before issuing a token. Implemented directory, owner-candidate, and
membership-revocation endpoints accept the same optional selector pair.
The customer-journey page requests a short-lived `catalog.journey.read`
delegation from S01 and passes it through EDGE01 at
`http://localhost:8080/internal/catalog` to S02 at `http://localhost:8083`;
the selected context needs that current membership. S02 remains the authority
for catalog reads and mutations. Browser tests mock both services, so a live
OIDC/S01/EDGE01/S02 round trip still needs validation.
