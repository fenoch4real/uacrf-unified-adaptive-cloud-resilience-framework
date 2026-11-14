# Threat model (implementation baseline)

Scope: the services that exist today (EDGE01, UX01, web, S01, S02, platform
delegation library) plus the contracts the next milestones must honor. This is
a baseline review for local and pre-production use, not a production security
assessment. It extends the summary table in
[SECURITY_AND_CONTRACT_POLICIES.md](./SECURITY_AND_CONTRACT_POLICIES.md).

## Assets

Tenant and environment boundaries, S01 identity and grant authority, the S02
estate catalog, delegation signing key, Keycloak directory credential,
database runtime credentials, release images and their signatures, and the
audit/outbox record of authority changes.

## Trust boundaries

Browser to web/UX01 (session cookie); UX01 to EDGE01 (bearer token, no
authority of its own); EDGE01 to S01/S02 (forwarding only); S01 to S02 via
short-lived, audience-restricted delegation; S01 to Keycloak admin API
(read-only service account); each service to its own database only.

## Threats and status

| # | Threat | Control | Evidence | Status |
|---|---|---|---|---|
| T1 | Forged tenant/environment/actor | Scope from verified token claims or S01-validated selector; membership rechecked | S01 context tests, EDGE01 route tests | Implemented |
| T2 | Confused deputy between services | Delegation audience `S02`, capability list, expiry; S02 verifies signature and operation | Platform and S02 tests; live run | Implemented |
| T3 | Self-granted or single-person privilege escalation | Separate approver; single-use 15 min decision; approver is neither creator nor target; admin lacks `identity.grant.approve` | S01 grant tests; live run showed reuse refused | Implemented |
| T4 | Admission of an unverified identity | Principal must exist in the trusted Keycloak directory and be enabled; no memberships on admission | S01 and directory tests; live run | Implemented |
| T5 | Revoked authority still usable | Delegation rechecks current membership | Live run: delegation refused after revoke | Implemented; downstream propagation to later services open |
| T6 | Cross-tenant disclosure | Tenant-scoped queries; foreign aggregate indistinguishable from absent | S01 and S02 tests | Implemented for S01/S02 |
| T7 | Database privilege expansion | Separate admin/runtime roles; restricted runtime role; cross-DB credential rejection | Ownership check, image smoke test | Implemented |
| T8 | Directory credential abuse | Service account `view-users` only, no full scope; writes and client listing return 403 | Live Keycloak verification | Implemented; secret rotation runbook open |
| T9 | Stolen or leaked delegation key | Private key only in S01; S02 holds public key and key id | Config and docs | Partial: rotation and storage in a secret manager open |
| T10 | Compromised dependency or image | SBOMs, Trivy HIGH/CRITICAL gate, pinned images and actions, keyless signing on release | CI, `release.yml` | Partial: release workflow not yet run; no deploy-time verification |
| T11 | Replay or duplicate command | `Idempotency-Key` with request digest and stored receipt | S01/S02 tests | Implemented |
| T12 | Duplicate, delayed or conflicting event | Transactional outbox in S01/S02 | Outbox tables and tests | Producer side only; inbox, sequence checks and quarantine open until the first consumer |
| T13 | Session theft or CSRF in the web app | HttpOnly SameSite=Lax session; PKCE; secure flag setting | Web tests | Partial: CSRF token review and HTTPS-only enforcement open |
| T14 | Secret leakage in logs, events or source | Secrets from environment; `.env` ignored; events carry no credentials | Review, CI | Partial: no automated secret scanning in CI |
| T15 | Ambiguous external action result | Unknown outcomes reconciled by status query before retry | Design only | Open until M5 (see FIRST_RECOVERY_ACTION.md) |
| T16 | Stale graph/policy driving an action | Bind decisions to exact revisions with current authoritative checks | Design only | Open until M4/M5 |
| T17 | Denial of service on edge or services | Edge traffic-limit policy | None | Open |

## Tenant and event compatibility review

- Every S01/S02 aggregate carries tenant and environment; there is no
  cross-tenant read path. New services must add the same scope columns and a
  denial test before exposing data.
- Current outbox events (`identity.*`, catalog) carry event id, tenant,
  environment, aggregate and sequence (unique per aggregate) but no explicit
  schema version or payload digest column. Before the first consumer, add both,
  plus an inbox table and a visible quarantine path; those are M2 entry
  criteria, not retrofits.
- Event meaning changes (including enum security meaning) require a new
  version and a consumer compatibility test, as already stated in the policies.

## Required before an externally reachable deployment

T9 key custody and rotation, T10 signature verification at deploy, T13 CSRF and
HTTPS enforcement, T14 secret scanning, T17 rate limits, and a production
identity provider replacing the local realm.
