# S01 Identity, Ownership & Delegation Specification (Milestone 1)

## Responsibilities
- Owns `foundation.tenant`, `foundation.environment`, `foundation.principal` (`HUMAN`/`SYSTEM`/`AGENT`), `foundation.grant_decision`, `foundation.membership`, `foundation.audit_event`, and `integration.outbox_event`.
- Enforces **Separation of Duties**:
  1. Principal admission (`POST /v1/principals`) grants zero runtime memberships (`runtimeEffect: NONE`).
  2. Approver (`POST /v1/grants/decisions`) must be an enabled `HUMAN` principal distinct from the target recipient.
  3. Membership manager (`POST /v1/memberships`) must be distinct from both the approver and the recipient, and consumes a single-use unexpired `grant_decision_id` atomically.
  4. `owner.eligible`, `identity.owner.select`, `advisory.run.create`, `objectives.objective.review`, and `assessment.learning.review` are restricted to `HUMAN` principals at `GLOBAL` scope.
  5. Disabling a principal protects the last active `identity.membership.manage` administrator in the context.
- Issues 30-second audience-bound RS256 delegation JWTs via `POST /v1/delegations/exchange` (internal only, never routed publicly by `EDGE01`) and publishes its public key at `/.well-known/jwks.json`.
