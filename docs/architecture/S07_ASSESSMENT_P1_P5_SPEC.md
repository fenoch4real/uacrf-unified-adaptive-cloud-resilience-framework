# S07 Assessment, Path Independence Certification (P5) & Decision Envelopes (P1)

## 1. Purpose & Separation Guarantees
`assessment-service` (`S07`, port `8087`) implements the UACRF **Assess** pillar:
- Evaluates critical customer journey resilience under explicit failure scenarios without authorizing live recovery mutations (*Read-only assessment*).
- Enforces strict separation of duties between **Assessor**, **Service Owner / Technical Reviewer**, and **Independent Reviewer** (`403 SELF_REVIEW_FORBIDDEN`).

## 2. Core Engines
1. **Correlation-Domain Taxonomy (`P5` — `PathIndependenceCertifier`)**:
   - Inspects primary vs alternate recovery paths across `REGION`, `ZONE`, `CLUSTER`, `IDP`, `KMS_KEY`, `DNS`, `DATA_STORE`, `RELEASE`, and `VENDOR`.
   - Shared critical domains (`REGION`, `IDP`, `KMS_KEY`, `DNS`, `DATA_STORE`) return `NOT_INDEPENDENT` with explicit witness paths.
2. **Cryptographic Decision Envelope & Replay (`P1`)**:
   - Computes RFC 8785 canonical SHA-256 digests (`CanonicalJson`) and seals envelopes with **RS256 JWS** (`EnvelopeSigner`).
   - `POST /v1/decision-replays` re-verifies signature validity and digest equality (`VERIFIED` vs `TAMPER_DETECTED`).
