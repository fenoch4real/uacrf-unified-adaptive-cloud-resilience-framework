# Implement UACRF with Java and Spring Boot

Version 1.0 · Runtime revision 3 · Guide revision 18

The UACRF backend now uses Java 25 and Spring Boot 4.1.2. This module implements 27 routes: ten canonical catalogue operations plus canonical graph metadata, nine identity and ownership operations, four relationship maintenance operations and three graph diagnostics. PostgreSQL retains the guarded transaction and authorization rules. The Python server, installer, bootstrap utility and runtime tests have been removed from this working module. Historical release archives remain preserved.

This is the implemented backend slice, not the entire product. The other 63 canonical and 16 proposed operations, cloud collection and execution workers, connected prototype UI and live identity-provider acceptance remain open. The server binds to loopback. It supplies neither a browser sign-in redirect nor production TLS ingress. No readiness score increases merely because the implementation language changed.

## Start with the build

Use JDK 25, Maven 3.9.11 and Docker with the pinned PostgreSQL and Neo4j images already installed. The included macOS/Linux launcher downloads Maven from Maven Central and checks its pinned SHA-512 checksum. Maven then resolves the versions in pom.xml. On Windows, install the same JDK and Maven versions and use mvn in place of ./mvnw. Network access is required for the first dependency download. Record and review the resolved dependency tree for your deployment; this package does not claim a vulnerability audit.

Keep the extracted release folder intact. Open a terminal in its runtime directory:

```sh
java -version
export UACRF_NODE="$(command -v node)"
./mvnw test
./mvnw -DskipTests package
```

The tests need Node.js only to verify that Java and Node calculate the same hash over PostgreSQL's canonical UTF-8 text. It is not required by the running API. The tests start their own uniquely named PostgreSQL and Neo4j containers, install a fresh schema, run a real Spring Boot HTTP server connected to both stores, and remove both owned containers afterward. They do not reuse or reset your application containers. Install the exact image deliberately before testing:

```sh
docker pull postgres@sha256:3a4264520e121176619f54ff867d12774bc81e95e7b84db58d63bccdb6144b81
docker pull neo4j@sha256:adaa077a3ab023015143b8f73f7076a7d053f12985f5b06f4c6a128c3a2f7240
```

The pinned test images report PostgreSQL 18.6 and Neo4j 5.26.32. RuntimeTest contains twenty-seven ordered integration methods because the scenario develops a journey and its ownership over time. They verify token rejection, source admission, independently reviewed grants, persistent records, retries, pagination, concealment, concurrent transfer, revocation, disable/re-enable, agent-anchor updates, rollback and bootstrap recovery. Signed identity/source fixtures are synthetic. The source transport is replaced in tests; cryptographic verification is real. Passing these tests does not establish acceptance of your actual identity provider or source adapter.

The executable file is target/uacrf-runtime-1.0.0.jar. A tested copy is included in dist. Rebuild after modifying source. The JAR bundles Java dependencies, but the contracts and SQL stay in the release folder so their existing traceability remains readable.

## Install a fresh isolated database

The Java admin utility installs all 18 baseline migrations, the relationship and identity patches, runtime/001_runtime.sql and runtime/002_graph_runtime.sql. It refuses an existing UACRF schema, existing application relations or conflicting runtime roles. It performs the installation in a transaction and does not reset or drop a database. This command is not an upgrade manager.

Set UACRF_RELEASE_ROOT to the absolute path of the extracted UACRF_v1_0_agent_core folder. Supply administrator connection details through your controlled environment or secret injection. Do not put real passwords in this document, a command history or source control. UACRF_ADMIN_JDBC_URL is a PostgreSQL JDBC URL without embedded credentials. The other two variables are UACRF_ADMIN_USER and UACRF_ADMIN_PASSWORD.

```sh
export UACRF_RELEASE_ROOT="/absolute/path/UACRF_v1_0_agent_core"
java -jar dist/uacrf-runtime-1.0.0.jar admin install \
  --acknowledge-empty-isolated-database uacrf_api_login
```

The utility prompts privately for the new API login password and requires an interactive console. The login must be distinct and its password at least 24 characters. Use that login for normal server connections. Only offline administration receives DBA credentials. The API login cannot read tables directly or become the procedure owner.

## Configure trusted identities and sources

Copy config.example.json to a private configuration directory and replace every placeholder. Set UACRF_CONFIG to that file's absolute path. It names the fixed identity issuer, audience, provider, approved RSA public key IDs and public-key file paths. Public-key paths resolve beside the configuration file. Use RSA keys of at least 2048 bits. Only RS256 tokens with issuer, audience, subject, integer issue/expiry/not-before times and a token ID are accepted. User tokens have a maximum lifetime of fifteen minutes. Approved key changes currently require a reviewed configuration update and restart; automatic discovery or key rotation is not implemented.

The directory_url points to a trusted HTTPS adapter that you must implement for your selected provider. The supplied Java client implements the signed assertion protocol below; it is not a Microsoft, Google or other provider-native connector. Redirects are rejected, responses are bounded and the request has a five-second timeout. An optional adapter credential comes from UACRF_DIRECTORY_TOKEN, not the request or configuration examples.

The adapter serves application/jwt at identity/REFERENCE, grant/REFERENCE and review/REFERENCE beneath directory_url. Every signed assertion includes iss, aud, sub, iat, nbf, exp and jti, plus tenant_id, environment_id, purpose and reference. The assertion lifetime is at most five minutes. Its tenant, environment, purpose and reference must match the server's request exactly. The configured directory_issuer, directory_audience and directory_keys verify these assertions independently of browser identity tokens.

An identity assertion adds identity_issuer, identity_subject, display_name and identity_kind, which must be HUMAN, SYSTEM or AGENT. A grant assertion binds authorization_ref, reviewed_by, principal_id, capability, service_scope and expires_at to the exact proposed grant. The reviewer must currently hold identity.grant.approve and be distinct from the acting administrator and recipient. The authorization reference is single-use. A review assertion for re-enabling a user adds issuer, subject, grant_review_ref, reviewed_by and grant_digest for the exact current grant set. The database checks the grant ceiling, source expiry, reviewer authority and retained identity state again at commit.

Neither a display name nor an email address proves an identity. UACRF stores the verified issuer and immutable subject. The configured tenant and environment determine request scope; caller-provided role or tenant claims cannot enlarge it.

## Configure Neo4j and the projection worker

Neo4j is a required dependency of the serving application. Startup requires its connection settings and verifies connectivity and the two projection uniqueness constraints. A graph outage after startup yields a restrictive graph response; PostgreSQL catalogue writes do not pretend that a projection completed.

For an isolated local environment, compose.local.yml defines both stores with loopback-only ports and separate named volumes. Set private UACRF_LOCAL_POSTGRES_PASSWORD and UACRF_LOCAL_NEO4J_PASSWORD values, review the default ports 55432 and 57687, then run docker compose -f compose.local.yml up -d. Do not point this example at existing production volumes. The integration suite uses different uniquely named disposable containers and removes them; the Compose example intentionally keeps your local development data. No reset or volume-deletion command is part of setup.

Use these variables in the API process:

| Variable | Value |
|---|---|
| UACRF_NEO4J_URI | Verified TLS Bolt/routing URI; bolt://127.0.0.1:57687 is permitted only for local development. |
| UACRF_NEO4J_DATABASE | Explicit Neo4j database, normally neo4j in the local example. |
| UACRF_NEO4J_USER | Database account used by graph reads. |
| UACRF_NEO4J_PASSWORD | Private password injected by your secret store. |

The separate worker uses UACRF_GRAPH_WORKER_NEO4J_URI, UACRF_GRAPH_WORKER_NEO4J_DATABASE, UACRF_GRAPH_WORKER_NEO4J_USER and UACRF_GRAPH_WORKER_NEO4J_PASSWORD. Its PostgreSQL connection comes from UACRF_GRAPH_WORKER_JDBC_URL, UACRF_GRAPH_WORKER_DB_USER and UACRF_GRAPH_WORKER_DB_PASSWORD. Do not supply worker or administrator credentials to the API process.

Create Neo4j constraints with a separately authorized graph administrator using the worker-prefixed Neo4j settings for this offline command. Remove that privileged credential afterward and configure the actual projection writer:

```sh
java -jar dist/uacrf-runtime-1.0.0.jar admin graph-constraints
```

The API reader and projection writer should have separate database-enforced privileges where the Neo4j edition supports them. Neo4j Community tests use one isolated test account. Read-mode transactions do not prove database role separation. Review the deployment edition and database isolation before exposure; no application user receives database credentials.

A fresh Java installation includes the SQL graph patch automatically. If upgrading an existing runtime revision 2 database, back it up and review runtime/002_graph_runtime.sql first. Then run the following offline command with DBA settings. It refuses to reapply an installed graph extension and does not modify old accepted rows:

```sh
java -jar dist/uacrf-runtime-1.0.0.jar admin graph-install
```

Legacy sealed graph rows without UACRF_GRAPH_V1 acceptance metadata are not silently promoted. Import and accept a new attributable revision. After the identity configuration and verified-human bootstrap are established, create an environment-bound graph worker login:

```sh
java -jar dist/uacrf-runtime-1.0.0.jar admin graph-worker-login uacrf_graph_login
```

The utility privately prompts for a password. Configure this login in the worker's PostgreSQL variables. It can claim, acknowledge and requeue graph projection work only for the tenant/environment bound by the administrator. It cannot accept graph sources or read underlying tables directly.

## Bootstrap or recover administration

Complete the trusted source adapter before bootstrapping. The initial signed user token must match an independently source-verified HUMAN identity. First-time bootstrap is allowed once per environment. Its seventeen capabilities last one hour; use this period to establish reviewed, bounded ongoing grants. Bootstrap is an offline DBA action and is never an HTTP endpoint.

Supply the private configuration, cursor key and API connection variables described below, as well as the separate administrator variables needed by the offline tool:

```sh
java -jar dist/uacrf-runtime-1.0.0.jar admin bootstrap \
  initial-admin "Initial accountable administrator"
```

The token is read privately from the console. For controlled recovery, append --recover; the tool also prompts for a distinct source-verified human witness token. Recovery retains the reason, witness, command, audit and outbox record. It does not silently delete historical grants. Protect DBA authority and define a real organizational recovery process before deployment.

## Run the API

Use separate API credentials in UACRF_JDBC_URL, UACRF_DB_USER and UACRF_DB_PASSWORD. Supply a stable, server-only random cursor signing key of at least 32 bytes, encoded in base64, in UACRF_CURSOR_KEY. Generate it through your secret manager. Changing the key invalidates earlier continuation cursors. No credentials or private signing keys are shipped.

Remove UACRF_ADMIN_JDBC_URL, UACRF_ADMIN_USER and UACRF_ADMIN_PASSWORD from the serving process environment. With the API variables, private configuration and release root in place:

```sh
java -jar dist/uacrf-runtime-1.0.0.jar
```

The server listens on 127.0.0.1:8080; UACRF_PORT can change the port. Use an authorized API client with a verified Bearer token. Writes require application/json and a bounded Idempotency-Key. The runtime checks the retained request and response schemas, rejects duplicate JSON keys, unknown fields and query parameters, and never returns a fabricated success for an unimplemented route. Raw credentials, tokens and payloads are not logged by the application.

## Understand the transaction boundary

Database.call uses JDBC with explicit commit and rollback. It sets statement and lock timeouts, then invokes uacrf_app.request using prepared parameters. PostgreSQL rechecks current identity, permissions, scope and revision preconditions. Metadata, command, audit and outbox commit together. The API login has only guarded procedure access; startup refuses a superuser, a role that bypasses row security, procedure-owner membership or direct principal-table privileges.

The database currently serializes requests per environment using an advisory transaction lock. This is a deliberate correctness choice for the first slice and requires throughput review before expansion. External source fetches occur outside the database transaction after authorization preflight. Exact committed retries can return their retained result without fetching the source again. Changed requests with a retained key and stale revisions fail. The four relationship maintenance operations return synchronous 200 metadata receipts after commit. There is no provider-side effect worker in this slice.

Signed continuation cursors bind to the configured scope, verified caller, route, target, filters, page size and current authority digest. A Java cutover intentionally invalidates old Python cursor bindings. They expire after five minutes and reveal no hidden-row counts. Identity disable, expiry and revocation remain separate from object ownership; transferring an agent updates both its profile and internal anchor atomically.

## Review the evidence and continue

IMPLEMENTATION_MAP.json links all 27 routes to contracts, Java authentication, the SQL transaction and executed evidence. TEST_RESULTS.json records the Java suite; evidence/surefire-summary.xml retains its test names without credentials or host properties. GAP_CLOSURE.md distinguishes local closure from remaining work. The current guides and diagram references have been updated to Java. Historical archives still contain their original Python implementation and evidence.

Verify the packaged route inventory after extraction:

```sh
java -jar dist/uacrf-runtime-1.0.0.jar verify "$UACRF_RELEASE_ROOT"
```

To verify an authorized fingerprint export, pipe the bounded JSON material described in objective_design/FINGERPRINT_CONTRACT_v1_0.md to the JAR's fingerprint subcommand. Java hashes the database-produced canonical_text as UTF-8; it must not parse and reserialize that text. Existing digests remain unchanged.

Implement the remaining handlers, objective lifecycle and collection workers after reproducing this local slice and accepting the real identity integration. Connect the product UI to accepted routes. Complete browser, accessibility, live cloud, provider execution and all 39 full-system scenarios before making a full acceptance claim.

## Accept a graph and run projection

Use the graph manifest schema in graph_design/manifest.schema.json. Every node and edge names an existing evidence qualification; no file can assign itself a QUALIFIED verdict. Service nodes require workload_kind SERVICE with a registered service ID, or AGENT with a workload profile ID. Resource nodes retain their supported labels. The source file must come from your trusted ingestion/review process. The fictional example in graph_design/manifest.example.json illustrates structure and intentionally needs real qualified records before it can be accepted.

With a verified human, trusted source adapter and separate offline DBA credentials, import an attributable file and its independently checked SHA-256 hash:

```sh
java -jar dist/uacrf-runtime-1.0.0.jar admin graph-load \
  /private/path/accepted-graph.json EXPECTED_SHA256 "Reviewed source and qualifications"
```

The importer prompts for a verified token. It rejects a changed file, a malformed manifest, missing qualifications, invalid endpoints or stale evidence. Source insertion, sealing, acceptance audit, outbox notice and projection work commit together in PostgreSQL. On failure they roll back. The returned PENDING projection state does not mean Neo4j is ready. For an already staged BUILDING revision, admin graph-seal REVISION EXPECTED_REVISION REASON uses the same controlled acceptance boundary. This remains an offline DBA operation, not a public graph-editing API.

Run the worker with its own credentials and release root:

```sh
java -jar dist/uacrf-runtime-1.0.0.jar graph-worker once
java -jar dist/uacrf-runtime-1.0.0.jar graph-worker run
```

The first command attempts one work item; the second runs the polling loop. Use a supervised process for ongoing operation and stop it normally when required. PostgreSQL work leases last sixty seconds. Neo4j writes are bounded managed transactions; failures retain delayed work for retry. The worker acknowledges SQL only after the Neo4j transaction commits. The third runtime table, graph_projection_event, retains append-only lease, completion, retry and requeue events with the actual worker login and digest. A crash between commits is recovered as an exact duplicate without exposing a partially acknowledged graph.

After both stores agree, use the four graph routes documented in graph_design/README.md with a verified Bearer token. Dependency reads show typed services, agents and resources. Impact is a bounded diagnostic with witnesses. Shared-dependency review always reports independence NOT_CERTIFIED. A usable graph is not a recovery authorization or proof of discovery completeness.

For an intentionally rebuilt Neo4j database, reinstall constraints and queue a previously acknowledged revision for verification and projection:

```sh
java -jar dist/uacrf-runtime-1.0.0.jar graph-worker requeue REVISION
java -jar dist/uacrf-runtime-1.0.0.jar graph-worker once
```

A tampered existing graph is detected rather than overwritten automatically. Investigate and restore/rebuild the isolated materialized view under reviewed operational authority. Read graph_design/DIAGRAMS.md for the numbered import, projection/retry and read sequences, and graph_design/README.md for the scope and failure rules.


## Primary technical references

The build pins its versions in pom.xml. Consult the official Spring Boot system requirements and release documentation before a future version change: https://docs.spring.io/spring-boot/system-requirements.html and https://spring.io/blog/2025/11/20/spring-boot-4-0-0-available-now/. The schema validator implements JSON Schema 2020-12: https://github.com/networknt/json-schema-validator. These references inform dependency choices; executed project evidence is recorded separately.


## Recovery readiness assurance update

Version 1.0 runtime revision 4 adds a locally implemented recovery-assurance diagnostic workflow. Thirty routes comprise eleven canonical and nineteen proposed operations; 63 canonical and sixteen proposed operations remain unimplemented, with 35 proposed operations total. An immutable baseline, explicit reassessment, current evidence checks, pending accepted revisions, append-only reviews, audit and outbox support services and agents. No recovery certification, external action, live collection or unattended reassessment is claimed. Guide revision 19 and flow revision 9 identify the update. No score increase. Read [the implementation guide](../assurance_design/README.md), [the local walkthrough](../assurance_design/index.html) and [the step-labelled diagrams](../assurance_design/DIAGRAMS.md). Fresh installation includes runtime/003_assurance_runtime.sql; an existing runtime revision 3 deployment uses the offline Java admin assurance-install command after backup and migration review.


## Unified recovery assurance interface

The current 58-view application integrates recovery assurance into a local prototype. Assess contains the baseline and reassessment forms; journeys, service and agent dependencies, evidence, recovery and overview link to the same in-memory journey record. Scoped recent activities link back to that assessment. The old companion URL forwards to Assess. This is not an authenticated connection to the Java API. Runtime revision 4, thirty routes and the existing 32-test backend evidence are unchanged. Live collection, unattended reassessment, connected UI and recovery certification remain open. No score increase. Open [Assess](../ui_workloads/index.html#UI-03) and choose Place Order or Resolve Order. Establish a baseline, inspect changed context, record a local reassessment, then follow the evidence and recovery links. Changes disappear on reload. A draft journey cannot establish an assessment. Read [the UI integration map](../assurance_design/UI_INTEGRATION.md) for screen and API responsibilities.
