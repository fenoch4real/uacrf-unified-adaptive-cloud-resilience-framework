# Milestone 3 Flows — Assess, Path Independence (P5) & Sealed Envelopes (P1)

## 1. Assessment Review, Separation of Duties & P1 Sealing Sequence

```mermaid
sequenceDiagram
    actor Owner as Technical Reviewer
    actor Indep as Independent Reviewer
    participant UX as UX01 Report UI
    participant EDGE as EDGE01 Gateway
    participant S07 as S07 Assessment Service
    participant SGN as EnvelopeSigner (RS256)

    Owner->>UX: Approve Technical Review
    UX->>EDGE: POST /v1/assessments/{id}/reviews (TECHNICAL)
    EDGE->>S07: Forward with X-UACRF-Actor-Id
    Indep->>UX: Approve Independent Review
    UX->>EDGE: POST /v1/assessments/{id}/reviews (INDEPENDENT)
    alt actorId equals assessorId or serviceOwnerId
        S07-->>UX: 403 SELF_REVIEW_FORBIDDEN
    else Distinct reviewer approved both gates
        S07-->>S07: Mark report_status = ISSUED
        Indep->>UX: Seal RS256 DecisionEnvelope
        UX->>EDGE: POST /v1/assessments/{id}/envelopes
        S07->>SGN: Sign canonical SHA-256 manifest
        SGN-->>S07: RS256 JWS + payloadSha256
        S07-->>UX: DecisionEnvelopeDto
    end
```

## 2. Correlation-Domain Independence (P5) Activity Flow

```mermaid
flowchart TD
    Start([Evaluate Primary vs Alternate Path]) --> Extract["Compare Domains: REGION, ZONE, CLUSTER, IDP, KMS_KEY, DNS, DATA_STORE"]
    Extract --> Empty{"Either map empty?"}
    Empty -- Yes --> Unknown["Return UNKNOWN"]
    Empty -- No --> Intersect["Find shared domain values"]
    Intersect --> Disjoint{"Zero shared domains?"}
    Disjoint -- Yes --> Certified["CERTIFIED"]
    Disjoint -- No --> Critical{"Shared IDP / KMS / REGION / DNS / DATA_STORE?"}
    Critical -- Yes --> NotIndep["NOT_INDEPENDENT + Witness domains"]
    Critical -- No --> Cond["CONDITIONALLY_CERTIFIED"]
```
