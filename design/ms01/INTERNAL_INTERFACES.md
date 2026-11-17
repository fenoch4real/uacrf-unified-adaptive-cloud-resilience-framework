# Internal contracts and state machines

Version 1.0 | Architecture revision MS 01

These are target contracts for implementation. They supplement the existing public wire contracts and are not counted as completed handlers. Calls require authenticated service identity, a receiving audience and the original actor's narrowed scope. Owner responses are authoritative only for their named resource, revision, purpose and validity interval.

## Owner receipt protocol

An owner receipt contains `receipt_id`, `issuer_service`, `audience_service`, `tenant_id`, `environment_id`, `actor_id`, `resource_type`, `resource_id`, `resource_revision`, `content_digest`, `authority_epoch`, `eligible`, `purpose`, `issued_at`, `valid_until` and a detached signature. Its schema is in `contracts/owner-receipt.schema.json`. The body is signed under the owner's registered key and verified against an allowlisted issuer/audience/algorithm and current trust configuration. A client-provided verified/eligible flag is never accepted instead of an owner-issued response.

Current access and content permission are separate from historical authenticity. A valid old signature proves retained attribution, not continued approval. Mutating decisions ask for fresh receipts and compare the control fence. No receipt extends beyond the source owner's eligibility deadline or the requesting operation's deadline.

| Owner | Internal method and path | Request and retained result |
|---|---|---|
| S01 | POST /internal/v1/access:check | Original actor, receiver, operation, workload/resource scope, expected epoch; current access receipt or concealed denial |
| S01 | POST /internal/v1/delegations | Current service plus original actor and narrowed audience/scope; short-lived delegation token |
| S01 | POST /internal/v1/owners/{id}:check | Target domain/scope and owner ID; eligible-human owner receipt |
| S01 | POST /internal/v1/objective-review-authority:check | Directly authenticated system caller and scoped reviewer ID; unsigned point-in-time reviewer-authority snapshot, not an owner receipt |
| S02 | POST /internal/v1/workloads/{id}:receipt | Purpose, requested version and known revision; exact current workload/profile receipt |
| S02 | GET /internal/v1/workload-versions/{id} | Current disclosure; immutable manifest and artifact metadata |
| S03 | POST /internal/v1/source-batches | Bound source/checkpoint/lease and exact observation references; intake receipt with accepted/duplicate/rejected identities |
| S03 | GET /internal/v1/connections/{id}/capabilities | Provider/connection identity; declared supported source/action features, not inferred equivalence |
| S04 | POST /internal/v1/qualifications/{id}:receipt | Scope, purpose, expected observation/policy digest and cutoff; current source/quality receipt |
| S04 | POST /internal/v1/observations | Attributable typed source bytes; retained observation or pending owner command |
| S05 | POST /internal/v1/workloads/{id}/objectives:receipt | Workload, purpose, expected effective revision set; exact active objective-set receipt |
| S05 | POST /internal/v1/import-material | Source verification/checkpoint and retained definition artifact; immutable preview command |
| S06 | POST /internal/v1/graphs/{id}:receipt | Exact accepted revision/digest and query scope; accepted/projected status and eligibility receipt |
| S06 | POST /internal/v1/dependency-proposals | Catalog/source revisions and typed endpoints; accepted proposal command, not direct Neo4j mutation |
| S07 | POST /internal/v1/cases/{id}:receipt | Purpose, case digest and current source requirements; fixed-case receipt |
| S07 | POST /internal/v1/learning-records/{id}:review-receipt | Point-in-time review and evidence verification, proposal/review digests and evaluation lineage; explicitly not adoption authorization |
| S07 | POST /internal/v1/adaptation-proposals | Feature-gated immutable unverified draft with learning-review lineage and declared target, tests, reviewer and rollback criteria; no submission or authorization |
| S07 | GET /internal/v1/adaptation-proposals/{id} and GET /internal/v1/adaptation-proposals | Scoped historical draft read and paginated discovery by learning record |
| S07 | POST /internal/v1/evaluation-triggers | Workload, observation cutoff and deduplication trigger; coalesced evaluation command |
| S08 | POST /internal/v1/retrievals/{id}:receipt | Case, purpose/classification and manifest digest; current retained-guidance receipt |
| S09 | POST /internal/v1/runs/{id}:cancel | Expected revision/generation and reason; retained cancellation with late-output policy |
| S10 | POST /internal/v1/controls/{workload}:fence | Critical change identity, expected control epoch and reason; new fence and invalidated permit set |
| S10 | POST /internal/v1/authorizations/{id}:reserve | Exact plan/context/executor/attempt and deadline; one bounded reservation |
| S10 | POST /internal/v1/reservations/{id}:revalidate | Bound executor, attempt, generation and current owner receipts; dispatch eligibility or revocation |
| S11 | POST /internal/v1/workflows/{id}/attempt-results | Executor identity, stable provider request ID, generation and observed result; retained outcome, including Unknown |
| S12 | POST /internal/v1/validation-plans/{id}:receipt | Workload/version/action context and independent probe policy; approved plan receipt |
| S12 | POST /internal/v1/validation-requests | Execution outcome references and plan/version; durable independent validation command |
| S13 | POST /internal/v1/usage-receipts | Allowlisted producer, metric/unit, quantity, time/scope and source identity; admitted/duplicate/rejected usage identity |
| S14 | POST /internal/v1/export-requests | Requested source references, purpose and actor scope; durable export command after owner disclosure |

The implemented S07 learning-review receipt is an exception to the target protocol above: it is an internal, unsigned point-in-time verification response, not a signed owner receipt or adoption authorization. It requires global-scope `assessment.learning.read` and `UACRF_ASSESSMENT_LEARNING_REVIEW_ENABLED=true`, and is not routed through EDGE01 or consumed by S10. S07 verifies an approved review for the proposal revision, compares its SHA-256 digest with `owned_digest` in exactly one retained review outbox event, and revalidates supporting incident evaluation evidence with current owners. The digest uses the current Jackson serialization rather than RFC 8785, and the check does not verify the whole outbox payload digest; serialization changes or removal of the event can fail closed. Owner checks are sequential, and later changes can invalidate the result.

The implemented S01 objective-review authority check takes `tenant_id`, `environment_id`
and `reviewer_id`. A directly authenticated SYSTEM caller requires a current
global `objectives.review-authority.check` membership; that capability cannot be
delegated. In one database statement S01 checks current caller and reviewer
status, trusted identity providers and global memberships. An authorized result
contains the scope, reviewer, status and database check time; denials do not
disclose whether the reviewer exists. It is unsigned, has no authority epoch or
validity interval, and does not bind an objective, decision or operation. It is
not the target access receipt or an activation authorization, and subsequent
revocation may invalidate it. It is not routed by EDGE01. With the optional
`UACRF_OBJECTIVES_REVIEWER_CHECK_ENABLED` gate enabled, S05 verifies the
retained reviewer's current authority through this response before activation,
using direct SYSTEM credentials. It rejects unavailable, malformed, mismatched
or stale responses, but does not check the activator, bind the response to an
objective or prevent revocation between the check and commit. With the gate
disabled, activation performs no reviewer recheck.

For both review approval and receipt issuance, S07 additionally checks typed proposal content against the separately stored incident and evidence references, recomputes its digest and compares it with `owned_digest` in exactly one retained proposal event. Inconsistency or a missing event fails closed. This proposal digest also uses current Jackson serialization and is not a versioned canonical scheme; the check does not authenticate the event or verify its full payload.

S07 also rechecks the proposal's expected incident revision and requires its evidence to be a subset of the incident's evidence, even when the supporting evaluations contain additional verified measurements. A previously retained approval remains historical and readable, but does not produce a current verification response if this binding is inconsistent.

Adaptation draft creation requires global-scope `assessment.learning.write` and `assessment.learning.read`, both the adaptation and learning-review feature flags, and a fresh successful learning-review check. The retained learning digests and check time are historical evidence only. Target owner, controlled version, proposed reviewer, tests and rollback criteria are caller declarations, not owner-verified or independently approved; no owner workflow is contacted. Reads require global-scope `assessment.learning.read`, even when creation is disabled. Listing requires `learning_record_id`, uses an ID cursor and `limit` 1–100 (default 50); separate pages are not a snapshot. Creation requires `Idempotency-Key`, scoped to tenant, environment and actor; same-content retries return the retained draft without rechecking owners, while changed content conflicts. Replays still require the feature flags and capabilities and are not current approval. Matching uses a hash of the current Jackson serialization and may conflict after serialization changes. The transactional local outbox event is unpublished and is not an execution permit. No EDGE01 route or target-owner submission exists.

Owner checks have a proposed two-second per-call timeout and a five-second overall current-context preparation budget under the nominal profile. Slow actual artifact verification can use durable asynchronous commands instead of holding transactions open. These are engineering targets, not measured service performance. Safe read retries must stay within the original operation deadline. Permission denied, stale revision, expired source and unavailable owner remain distinct internal outcomes.

## Event protocol and topics

`contracts/event-envelope.schema.json` defines a closed envelope. Payload schemas are registered by event type/version and validated locally; the consumer must not fetch an arbitrary schema URL supplied by an event. `EVENT_CATALOG.json` assigns producer, intended consumers, partition key, meaning and payload requirements.

For new cross-service messages, use RFC 8785 canonical JSON and SHA-256 over the canonical payload bytes. Restrict numeric payload values to bounded integers; exact decimal money, quantities and rates use validated strings. Do not pass arbitrary-precision source numbers through a JavaScript double. Preserve Unicode strings and original signed artifact bytes. Existing PostgreSQL canonical text and source-file hashes retain their original protocol and are wrapped with an explicit legacy digest scheme rather than silently recanonicalized.

Each topic partition key is `tenant_id|environment_id|aggregate_type|aggregate_id`, with reserved-delimiter escaping defined by the transport library. Topic ACLs allow the owning service to produce its facts. A consumer cannot publish another owner's event name. Inbox identity is producer/event ID; aggregate sequence and payload digest detect ordering gaps and conflicting duplicates.

## Common durable command lifecycle

A command begins ACCEPTED after local commit. A leased worker can move it to RUNNING, WAITING_FOR_DEPENDENCY or RETRY_SCHEDULED. Terminal states are SUCCEEDED, REJECTED, FAILED, CANCELLED and EXPIRED. OUTCOME_UNKNOWN is a retained non-success state for external ambiguity and requires reconciliation before a justified terminal transition. Existing public status vocabulary is mapped through an explicit compatibility serializer; do not silently change a closed legacy enum.

A lease has owner, token, generation and expiry. Prepare/finish calls include the lease token and expected aggregate revision. Source work occurs outside database locks. Completion compares current source receipts, authority, cancellation generation and lease before committing result, audit and outbox atomically. An old worker can retain a permitted late observation, but cannot overwrite the current decision.

## Execution and authorization lifecycle

An authorization is ISSUED, RESERVED, CONSUMED, REVOKED or EXPIRED. Reservation binds one attempt. A DISPATCHING execution attempt is written before a provider request. Provider observation can establish SUCCEEDED, FAILED, CANCEL_REQUESTED or OUTCOME_UNKNOWN; no implicit retry follows Unknown. A completed provider effect may lead to VALIDATION_PENDING and then VALIDATED, VALIDATION_FAILED or VALIDATION_UNKNOWN. Only an approved independent validation can establish the configured recovered outcome.

A compensation workflow is separately authorized and retains its own attempts. It can succeed, fail or remain unknown. Do not model compensation success as deletion of earlier effects. Human intervention and a reconciliation record are legitimate outcomes when an external action cannot be safely reversed.

## Target additions for prediction and learning

S07 will add versioned feature-set, model-card, model-evaluation, forecast and incident-learning records. Proposed internal endpoints are `POST /internal/v1/model-evaluations`, `POST /internal/v1/forecasts`, `GET /internal/v1/forecasts/{id}`, `POST /internal/v1/learning-records` and `POST /internal/v1/adaptation-proposals`. Their public exposure, if needed, receives separately published contracts; these are not secretly appended to the canonical count.

A forecast pins model/version, feature-set digest, training/evaluation lineage, forecast time/horizon, uncertainty and mode. An adaptation proposal identifies owner, affected controlled version, reason, required tests, independent reviewer and rollback criteria. No endpoint can directly promote an unreviewed model or alter production action policy from a model response.

## Provider and artifact ports

Collection ports expose capability discovery, inventory pages, typed observation batches, configuration snapshots and source-definition verification. Execution ports expose non-mutating translation, current precondition read, bounded dispatch, operation-status reconciliation, cancellation and separately authorized compensation. An Unsupported result names the unavailable capability and cannot satisfy a success acceptance test.

Artifacts use owner-local `stage`, `commit`, `verify` and `open-authorized-stream` ports. Immutable metadata includes digest, byte length, media type, classification, purpose and retention policy. A model or user reference never becomes an arbitrary outbound fetch. Native provider adapters declare their concrete credential scope, network targets, request-id and conditional-update behavior in their conformance record.
