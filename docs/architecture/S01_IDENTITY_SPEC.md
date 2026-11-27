# S01 Identity, Ownership & Separation-of-Duties Specification

## Overview
`identity-service` (`S01`, port `8082`, database `uacrf_identity`) serves as the root identity and delegation authority for UACRF. It keeps user, system, and AI agent identities distinct while preventing any actor from granting or approving its own elevated privileges.

## Simple Requirements & Invariants
1. **Explicit Identity Typing:** Admit `HUMAN`, `SYSTEM`, and `AGENT` principals within strict tenant and environment boundaries (`foundation.principal`), granting zero active memberships upon initial admission (`runtimeEffect: NONE`).
2. **Independent Two-Person Grants:** Require an enabled `HUMAN` approver (`POST /v1/grants/decisions`) who is distinct from the target recipient, followed by a distinct membership manager (`POST /v1/memberships`) who consumes the single-use `grant_decision_id` atomically.
3. **Human Accountability Guard:** Restrict ownership eligibility, advisory initiation, objective reviews, and learning closures exclusively to `HUMAN` principals, while preventing removal of the final active membership administrator in an environment.
4. **Short-Lived Delegation Exchange:** Issue `30`-second audience-bound RS256 delegation JWTs via internal `/v1/delegations/exchange` (shielded from public ingress by `EDGE01`) and publish public verification keys at `/.well-known/jwks.json`.
5. **Database-Level Isolation:** Enforce PostgreSQL `FORCE ROW LEVEL SECURITY` (`V2__enable_rls.sql`) on tenant and membership tables alongside `EDGE01` OAuth2 JWT enforcement.
