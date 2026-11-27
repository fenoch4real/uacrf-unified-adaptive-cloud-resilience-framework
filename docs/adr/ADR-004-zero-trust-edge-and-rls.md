# ADR-004: Zero-Trust Edge Gateway & Database Kernel Row-Level Security

- **Status:** Accepted
- **Summary Description:** Combines OAuth2 Bearer JWT validation and strict security response headers at `EDGE01` with PostgreSQL `FORCE ROW LEVEL SECURITY` across all twelve service databases for defense-in-depth multi-tenant isolation.

## Context
Relying solely on application `WHERE tenant_id = ?` query clauses exposes a single bug to cross-tenant leakage, while unauthenticated internal service-to-service endpoints risk privilege escalation.

## Decision
1. **Hardened Ingress (`EDGE01`):** `EdgeSecurityConfig` enforces OAuth2 JWT validation (`jwt-enabled`), injects strict Content Security Policy and `X-Frame-Options: DENY` headers, disables public caching (`no-store`), and blocks external callers from invoking internal delegation exchange endpoints.
2. **Kernel-Level Tenant Enforcement:** All twelve PostgreSQL services execute `V2__enable_rls.sql` enabling `FORCE ROW LEVEL SECURITY` keyed on `app.tenant_id` (`TenantRlsSession`), verifying cross-tenant denial even if application filters omit a predicate.

## Consequences
Multi-tenant isolation holds simultaneously at the edge network boundary, application logic layer, and relational storage engine, directly satisfying SOC 2 CC6 and ISO 27001 access isolation expectations.
