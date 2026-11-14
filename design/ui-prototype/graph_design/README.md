# PostgreSQL and Neo4j integration

Version 1.0 · Runtime revision 3 · Guide revision 18 · Flow revision 8

The Java Spring Boot backend now connects to PostgreSQL and Neo4j. PostgreSQL remains the authority for accepted revisions, evidence qualifications, permissions and durable projection work. Neo4j stores a verified copy of each accepted dependency graph. A graph becomes readable through the API only when its PostgreSQL acknowledgement, Neo4j receipt, digest, watermark, counts and full projected content agree, and the caller and supporting evidence remain eligible.

This integration adds one implemented canonical operation and three implemented diagnostic operations. The runtime therefore has 27 routes: eleven canonical and sixteen proposed-extension operations. The canonical baseline remains 74 operations; 63 are still outside the implemented runtime. The three graph diagnostics bring the proposed total to 32, of which sixteen remain unimplemented. The diagnostic routes do not impersonate the existing asynchronous graph-selection or graph-difference APIs.

## Follow a graph from source to API

A trusted collector or accepted-source process first produces the evidence events and qualification records in PostgreSQL. The graph manifest refers to those records. A source file cannot declare itself QUALIFIED. Node and relationship qualifications must exist, belong to the graph's scope and service, and be current. The Java importer verifies the source file hash, the manifest schema and its stable references. It uses an offline, source-verified human and DBA authority; the ordinary API and graph worker cannot invoke graph acceptance.

The importer creates a BUILDING revision and append-only nodes and edges in one PostgreSQL transaction. Sealing locks that revision and validates its endpoints, workload identities, capacity, qualifications and time bounds. The optional runtime patch permits the controlled procedure owner to finalize the digest once, from the exact locked SQL snapshot. It does not grant a general digest-editing permission. Closed revisions remain immutable. The patch leaves the eighteen baseline migration files unchanged and must be reviewed as an explicit runtime extension.

The seal commits the revision, acceptance audit, general outbox notice and graph_projection work row together. These are PostgreSQL effects only. The dedicated Java graph worker claims work under an expiring, environment-bound lease and commits the claim before contacting Neo4j. It then projects all nodes, relationships and a sealed batch receipt in one Neo4j transaction. Projection labels and relationship types come from the existing allowlisted Cypher statements. Unknown types, wrong endpoints, conflicting digests and count mismatches roll back the Neo4j transaction.

After Neo4j commits, the worker acknowledges the exact lease and digest in PostgreSQL. There is no distributed transaction across the two databases. If the process stops between the Neo4j commit and the SQL acknowledgement, the graph stays unavailable to dependency reads. After the lease expires, another worker verifies the retained Neo4j content as an exact duplicate and acknowledges it. A stale worker cannot acknowledge a newer lease. Transient failures retain work for retry after a delay.

The graph API authenticates the caller and checks current PostgreSQL permissions before reading Neo4j. The caller must be allowed to read the root service and every represented service or agent anchor. A narrower grant does not reveal hidden nodes, counts or names. PostgreSQL locks serialize the request against runtime grant/status changes. The API verifies the full Neo4j projection against the authorized SQL snapshot, then checks evidence and acknowledgement again before finishing the read. An outage or mismatch returns a restrictive error; it does not produce an empty healthy graph.

## Illustrative UI topology fixture

The UI prototype includes a fictional Northstar Payments topology in
`sample-topologies.js`. Its API-shaped response illustrates service-only,
agent-only and combined views over the same graph revision, including AWS
managed resources and dependencies shared by a protected service and agent.
The fixture contains no account identifiers, credentials or provider-derived
evidence. Its qualification references and revision metadata are examples
only; the fixture is not accepted, must not be used to seed S03 or S06, and is
not a provider integration test.

The eventual UI integration reads an explicit authorized graph revision from
S06 using the dependencies operation below. S03 discovers provider resources,
S04 qualifies attributable observations and relationships, and S06 accepts
the graph revision and verifies its Neo4j projection. The visual scope toggle
filters a returned revision for presentation; it must not broaden authorization
or claim that all external dependencies have been discovered.

The fixture is the single source for every picture of the sample. After
changing it, run `python3 UI/verification/generate_graph_exports.py`. The script
regenerates `UI/ui_workloads/graphs/northstar-{service,agent,both}.svg` and
re-embeds the sample at the bottom of the `UI-18` and `UI-37` screen
illustrations. Re-render the matching PNGs afterwards so the gallery, the
illustrations and the interactive prototype stay identical.

## Services agents and shared dependencies

A service node names its registered service through workload_kind SERVICE and workload_id. An agent is represented by a Service-labelled graph node with workload_kind AGENT and its workload profile ID. SQL resolves that profile to its internal service anchor for permissions. The response retains AGENT as the customer-facing type. This follows the existing protected-agent schema without inventing an unrelated parent service.

Models, knowledge-related stores, databases, tools, identities, infrastructure and other supported resources retain their typed node labels and relationships. For example, a checkout service can DEPENDS_ON a data store while an order-support agent READS_FROM that same store. Both can therefore appear in the reverse impact witnesses for a failure of the store. The graph importer uses the existing endpoint-role table and Cypher allowlist to check permitted relationships.

Only name, provider and region from node metadata are included in the projected metadata_json field. Arbitrary payload fields are not disclosed. Qualification references and workload bindings remain explicit. There is no endpoint that accepts arbitrary Cypher.

Journey membership and shared-resource consumer links remain catalogue metadata. They are not automatically converted into qualified dependencies. Otherwise a local planning edit could manufacture evidence-backed operational topology. Discovery and qualification must establish the actual dependency before it enters an accepted revision.

## Implemented graph reads

| Route | Meaning |
|---|---|
| GET /v1/graphs/revisions/{revision} | Canonical metadata for the requested revision. AVAILABLE means its projection is usable; PARTIAL records missing acceptance, evidence or acknowledgement. |
| GET /v1/graphs/revisions/{revision}/dependencies | The complete authorized projected node and relationship set within the accepted runtime capacity. |
| GET /v1/graphs/revisions/{revision}/impact?affected_id=db | Reverse dependency reachability with node and relationship witnesses. |
| GET /v1/graphs/revisions/{revision}/recovery-independence?primary_id=checkout&recovery_id=backup | Shared dependency witnesses between the selected primary and recovery nodes. |

The last two routes accept max_depth from one to eight, defaulting to eight. They stop at a thousand visited nodes. A boundary that would omit another reachable node produces INCOMPLETE. Cycles do not cause unbounded traversal. These operations consider DEPENDS_ON, CALLS, READS_FROM, WRITES_TO, USES_MODEL, USES_DATASET, USES_IDENTITY, USES_KEY, USES_DNS and RUNS_ON in their documented direction. Other relationships remain visible in the dependency view but do not silently acquire failure-propagation semantics.

Completeness is limited to the requested accepted revision. The API does not prove that discovery captured the entire external environment, and it does not select the newest revision automatically. Use explicit revision identities and source watermarks in decision workflows. Current evidence checks do not make an older snapshot identical to a newer one.

Recovery independence always remains NOT_CERTIFIED. Finding a shared dependency is useful evidence; finding none is not proof of independent recovery. Scenario-specific fault domains, recovery paths, authority, freshness, policy, validation and remaining decision-engine controls still need acceptance. Impact output is DIAGNOSTIC_ONLY. These routes cannot authorize recovery or cloud mutation.

## Qualification expiry and revocation

Evidence qualifications are append-only. An appended REVOKED qualification for the same evidence event invalidates the old event for current graph use. The system does not edit the earlier qualification or revive it by adding another QUALIFIED row for that same revoked event. A later acceptable observation requires a new evidence event and a newly accepted graph revision.

Node expiry, edge expiry, edge validity intervals, qualification expiry and event expiry are checked in PostgreSQL. A retained historical projection may remain stored in Neo4j after expiry, but it cannot pass current dependency reads. Missing or expired evidence yields PARTIAL metadata or a restrictive diagnostic error.

## Roles failure recovery and remaining acceptance

The API PostgreSQL login has guarded read access to graph_context and no graph table, acceptance or worker privileges. The graph worker has only claim, acknowledge and requeue procedures and is bound by its login to one tenant/environment. It has no source acceptance authority. Each lease, completion, retry and requeue is retained in graph_projection_event with the actual worker login, digest and lease identity. These events are append-only. Three new runtime tables hold the lease/receipt, append-only worker events and worker binding; they are separate from the baseline business and technical table counts.

Use separate Neo4j reader, projection writer and constraint-administration credentials where the deployed edition supports the required database privileges. Read-mode driver transactions are not a database authorization boundary. The isolated tests use Neo4j Community with a single test account; those results do not prove production role separation. For Community deployment, isolate the graph database and credentials and review the consequences of its privilege model before exposure. Only the API can be reachable by application users; they must never receive Neo4j credentials.

To rebuild a lost graph store, reinstall its constraints, requeue the affected accepted revision through the environment-bound worker and run the worker. Reads remain restrictive until verification and acknowledgement complete. Same-count content tampering is detected; it is not silently repaired. An operator must investigate the unauthorized change and restore or rebuild an isolated projection deliberately. No public endpoint deletes graphs or bypasses acceptance.

The retained tests cover PostgreSQL and Neo4j together, including service/agent/resource topology, source import rollback, scope concealment, direction and traversal bounds, cycles, replay after a partial cross-store commit, stale lease rejection, malformed batch rollback, same-count tampering, an actual Neo4j outage/restart, reconciliation and concurrent workers. They use synthetic identity and source fixtures. They do not establish live cloud collection, browser integration, certified resilience decisions or complete execution of the 39 full-system scenarios.

Read [the setup instructions](../runtime/README.md), [the manifest contract](manifest.schema.json), [the graph diagnostic API](openapi.proposed.json), [the implementation diagrams](DIAGRAMS.md), [the graphical schema](schema.svg) and [the executed evidence](../runtime/TEST_RESULTS.json).


## Recovery readiness assurance update

Version 1.0 runtime revision 4 adds a locally implemented recovery-assurance diagnostic workflow. Thirty routes comprise eleven canonical and nineteen proposed operations; 63 canonical and sixteen proposed operations remain unimplemented, with 35 proposed operations total. An immutable baseline, explicit reassessment, current evidence checks, pending accepted revisions, append-only reviews, audit and outbox support services and agents. No recovery certification, external action, live collection or unattended reassessment is claimed. Guide revision 19 and flow revision 9 identify the update. No score increase. Read [the implementation guide](../assurance_design/README.md), [the local walkthrough](../assurance_design/index.html) and [the step-labelled diagrams](../assurance_design/DIAGRAMS.md). Fresh installation includes runtime/003_assurance_runtime.sql; an existing runtime revision 3 deployment uses the offline Java admin assurance-install command after backup and migration review.


## Unified recovery assurance interface

The current 58-view application integrates recovery assurance into a local prototype. Assess contains the baseline and reassessment forms; journeys, service and agent dependencies, evidence, recovery and overview link to the same in-memory journey record. Scoped recent activities link back to that assessment. The old companion URL forwards to Assess. This is not an authenticated connection to the Java API. Runtime revision 4, thirty routes and the existing 32-test backend evidence are unchanged. Live collection, unattended reassessment, connected UI and recovery certification remain open. No score increase. Open [Assess](../ui_workloads/index.html#UI-03) and choose Place Order or Resolve Order. Establish a baseline, inspect changed context, record a local reassessment, then follow the evidence and recovery links. Changes disappear on reload. A draft journey cannot establish an assessment. Read [the UI integration map](../assurance_design/UI_INTEGRATION.md) for screen and API responsibilities.
