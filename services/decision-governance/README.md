# S10 decision governance

S10 stores immutable authorization-policy revisions and records independent reviewer
votes. Approval is policy configuration review only: it creates no action permit,
reservation or operational authorization (`authority` is always
`SIGNED_POLICY_CONFIGURATION_NOT_ACTION_AUTHORIZATION`).

## Properties

| Property | Environment variable | Purpose |
|---|---|---|
| `uacrf.delegation.public-key-base64`, `uacrf.delegation.key-id`, `uacrf.delegation.issuer` | `UACRF_DELEGATION_PUBLIC_KEY_BASE64`, `UACRF_DELEGATION_KEY_ID`, `UACRF_DELEGATION_ISSUER` | Verifies the S01 delegation JWT. |
| `uacrf.policy-trust.set-json` | `UACRF_POLICY_TRUST_SET_JSON` | Operator-provisioned reviewer trust set. Empty (default) disables approvals with `503 TRUST_SET_UNAVAILABLE`; reads and `REJECT` still work. |

## Trust-set format

The value is one strict JSON document (no unknown fields, no duplicate JSON keys, malformed
content fails startup). Public keys only; never place private keys here.

```json
{
  "revision": 3,
  "keys": [
    {
      "kid": "reviewer-key-1",
      "algorithm": "RS256",
      "public_key_base64": "<base64 X.509 SubjectPublicKeyInfo DER>",
      "tenant_id": "tenant-1",
      "environment_id": "environment-1",
      "service_id": "service-1",
      "reviewer_id": "reviewer-1"
    }
  ]
}
```

* `revision` is a positive integer; `keys` holds up to 1000 entries (may be empty to revoke all).
* Each key is RSA 2048-8192 bits. `kid` values are unique and a public key may appear once.
* A key is trusted only for exactly its tenant, environment, service and reviewer.
* `trust_digest` is the SHA-256 of the RFC 8785 canonical form of the normalized
  document (keys ordered by `kid`), so it is independent of whitespace and entry order.

### Rotation

Add the new key and increase `revision`; deploy the same document to every S10 instance.
Any content change requires a new, higher revision. S10 records each revision and digest on
the first approval it handles and then refuses (`503`) the same revision with different
content (`TRUST_SET_CONFLICT`) or a revision lower than one already recorded
(`TRUST_SET_STALE`). Removing or replacing a key makes `kid` unknown or changes its fingerprint:
new assertions with it are rejected, existing votes stay in the immutable history with
`currently_trusted=false`, and `current_eligible` becomes false until enough approvals are
re-established under trusted keys on a new revision.

## Approval assertion

`APPROVE` sends `signed_assertion`, a compact JWS. Header: exactly `alg=RS256`, `kid`,
`typ=uacrf.policy-review+jws`. Payload: RFC 8785 canonical JSON of exactly `tenant_id`,
`environment_id`, `policy_id`, `policy_revision` (integer), `service_id`, `policy_digest`,
`reviewer_id`, `decision` (`APPROVE`) and `reason`; these must equal the authenticated principal,
the policy revision and the request `reason`. The signature covers `header.payload`
(RS256/PKCS#1 v1.5). The contract is
`design/ms01/contracts/source/authorization_policy_design/openapi.proposed.json`.

## Review semantics

Reviewers need the human-only `authorization.policy.review` capability scoped to the policy's
exact service, must appear in `eligible_approver_ids`, and must differ from `proposed_by`. Each
reviewer has one immutable vote per revision. Quorum is `max(1, required_approver_count)`
distinct approving reviewers; below quorum the revision stays `DRAFT`, at quorum it is `APPROVED`,
and a rejection (unsigned, `signed_assertion=null`) is terminal. Votes are bounded by the
100-entry `eligible_approver_ids` limit and returned in `review.votes`.
