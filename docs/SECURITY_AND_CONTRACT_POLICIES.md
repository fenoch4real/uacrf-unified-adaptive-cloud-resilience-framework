# Initial security and contract policies

These are M0 implementation guardrails, not a completed threat model or a
production security assessment. The detailed owner contracts in
[`design/ms01/`](../design/ms01/) remain authoritative where this summary is
silent.

## Trust boundaries

- The browser and public network are untrusted. EDGE01 validates transport
  concerns; it does not replace authorization in the owning service.
- Each service authenticates its caller and checks tenant, environment,
  operation, and current owner state. Caller-supplied tenant or role headers
  never establish authority.
- A service delegation is audience-restricted, short-lived, and bound to the
  original actor, calling service, tenant, environment, and permitted
  operation. A downstream hop may narrow but never expand that authority.
- Each domain writes only its own database using its own runtime credentials.
  Cross-domain references use owner APIs and scoped receipts, not cross-service
  database access.
- Events communicate committed facts. An event is not an execution permit or
  a substitute for a current owner check.

## First identity administrator

S01's bootstrap is disabled by default. For a deliberate first initialization,
an operator enables `IDENTITY_BOOTSTRAP_ENABLED` and supplies the trusted
Keycloak issuer, tenant/environment IDs, principal ID, exact verified subject,
and display names in the deployment environment. Production bootstrap issuers
must use HTTPS. The local development realm uses HTTP only on loopback; that
exception is accepted by bootstrap solely for `localhost` or `127.0.0.1` and
must never be used outside local development.
The operator must also set a future `IDENTITY_BOOTSTRAP_ADMIN_EXPIRES_AT` and a
human-readable `IDENTITY_BOOTSTRAP_REASON`. Bootstrap grants only the fixed
identity-management capabilities, records the grant set and reason, and does
not grant `owner.eligible`. It serializes concurrent initialization and refuses
to add a first administrator to a populated identity store. After successful
startup, disable bootstrap and remove its subject-specific configuration. This
is an initialization mechanism, not a general administrator recovery API.

Bootstrap may also create one separate first approver by setting
`IDENTITY_BOOTSTRAP_APPROVER_PRINCIPAL_ID`, `_SUBJECT` and `_DISPLAY_NAME`
together. The approver must differ from the administrator in both principal id
and subject, holds only `identity.directory.read` and `identity.grant.approve`
(same expiry and audit record as the administrator), and cannot create
memberships. The administrator never holds `identity.grant.approve`, so no
single principal can both approve and execute a grant. The approver settings
are part of the one-time bootstrap configuration; adding them to an already
completed bootstrap is refused as a configuration change.

## Local OIDC and service delegation

S01 validates Keycloak access tokens against the configured issuer and JWK set,
and requires the configured audience (default `uacrf-s01`). Current access can
use a signed tenant/environment pair or accept both IDs as selectors; S01
rechecks the trusted identity's active membership before returning access.
Context-list cursors and IDs do not grant authority. Implemented context-bound
S01 endpoints accept an optional `tenant_id` / `environment_id` selector pair
and revalidate membership; absent a pair, they use signed token claims.
Delegation performs this recheck before issuing a token. The imported local
realm adds fixed context claims and the S01 audience only to the local UX01
client's access tokens; the `local-dev` tenant and `local` environment are
demonstration scope, not a production tenant-admission mechanism. Do not accept
browser-supplied scope as authority or reuse this realm configuration outside
local development.

The local principal-status route can disable an admitted principal after
checking `identity.principal.manage`, the current context, the expected
revision, and final-administrator protection. It advances `token_valid_after`
and records audit/outbox state without deleting the principal or its grants.
Re-enabling is unavailable until S01 can verify the source identity and a
current independent grant review. The outbox has no downstream revocation
consumer yet, so this local route does not immediately invalidate delegations
already issued to S02; those remain bounded by their short expiry.

The local Keycloak container runs in development mode, is bound to loopback,
has self-registration disabled, and uses separate operator and demo-user
passwords configured outside source control. It does not configure production
TLS or provider-verified identity admission.

S01 signs S02 delegation tokens with RS256 and a configured key ID. S01 receives
the Base64-encoded PKCS#8 private key; S02 receives only the matching
Base64-encoded X.509 public key. Both services require the configured key ID.
The verifier enforces the RSA signature, issuer, audience, key ID, and short
maximum lifetime. This removes the shared signing secret, but local environment
variables and a single configured key do not provide managed key distribution
or rotation. Production still requires an approved key-management and rotation
design.

## Tenant and event compatibility

- Tenant and environment scope must be derived from authenticated context and
  verified against the owning aggregate before read or write.
- Event envelopes retain stable event identity, owner, aggregate sequence,
  schema version, and payload digest. Consumers must tolerate duplicate
  delivery and detect conflicting payloads or sequence gaps.
- Producers persist domain changes and outbox records atomically. Consumers
  use an inbox or equivalent idempotency record with their local transaction.
- Compatible additions are preferred. A change that alters meaning, removes a
  field, changes an enum's security meaning, or breaks an older consumer
  requires a new version and an explicit compatibility test.
- Unknown, stale, contradictory, or unavailable authority/evidence cannot be
  converted to approval, qualification, or successful recovery.

## Initial threat model

| Threat | Required control |
|---|---|
| Forged tenant, environment, actor, or role | Derive scope from verified identity and validate it in every owner |
| Confused-deputy service call | Audience-restricted delegation and owner-side operation authorization |
| Cross-tenant record disclosure | Tenant-scoped queries and indistinguishable denial for foreign aggregates |
| Duplicate, delayed, or conflicting event | Transactional outbox/inbox, sequence checks, digest comparison, and visible quarantine |
| Stale graph or policy used for an action | Bind decisions to exact revisions and perform current authoritative checks |
| Secret or sensitive payload leakage | Keep credentials out of source and events; redact logs and minimize exported data |
| Service database privilege expansion | Separate credentials and verify denied cross-database access in integration tests |
| Ambiguous external action result | Record unknown outcomes and reconcile status before any retry |

Threat likelihood, deployment exposure, key management, retention, privacy,
and provider-specific controls still require an implementation threat-model
review before any externally reachable deployment.
