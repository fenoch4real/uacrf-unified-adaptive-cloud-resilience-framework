# Milestone 1 Activity & Sequence Diagrams

## 1. Two-Person Grant Approval & Delegation Exchange (Activity)

```mermaid
flowchart TD
    A[Admin admits HUMAN/SYSTEM principal] --> B[Principal stored with zero memberships]
    B --> C[Independent Approver submits grant decision]
    C --> D{Approver == Target or non-HUMAN?}
    D -->|Yes| E[Reject 403 SELF_APPROVAL_PROHIBITED / INELIGIBLE]
    D -->|No| F[Persist single-use GrantDecision with expiry]
    F --> G[Distinct Manager submits Membership creation]
    G --> H{Manager == Approver or Recipient, or Decision expired/consumed?}
    H -->|Yes| I[Reject 403/409 without granting membership]
    H -->|No| J[Atomically consume GrantDecision + insert Membership + Audit + Outbox]
```

## 2. Two-Person Grant Approval & Delegation Exchange (Sequence)

```mermaid
sequenceDiagram
    actor Admin as Bootstrap Admin
    actor Approver as Independent Approver
    participant UX as UX01 / EDGE01
    participant S01 as S01 Identity Service
    participant PG as PostgreSQL (uacrf_identity)

    Admin->>UX: Admit principal (HUMAN)
    UX->>S01: POST /v1/principals (Idempotency-Key)
    S01->>PG: Insert principal + audit + outbox
    S01-->>UX: 201 Created (runtimeEffect=NONE)

    Approver->>UX: Approve owner.eligible for Maya
    UX->>S01: POST /v1/grants/decisions
    S01->>PG: Verify approver != target, insert grant_decision
    S01-->>UX: 201 Created (grantDecisionId)

    Admin->>UX: Apply approved membership
    UX->>S01: POST /v1/memberships (Idempotency-Key)
    S01->>PG: Lock decision, verify manager != approver/target, consume + insert membership
    S01-->>UX: 201 Created
```

## 3. Customer Journey & Impact Profile Review Lifecycle (Sequence)

```mermaid
sequenceDiagram
    actor Owner as Journey Owner
    actor Reviewer as Distinct Reviewer
    participant UX as UX01 / EDGE01
    participant S02 as S02 Catalog Service
    participant PG as PostgreSQL (uacrf_catalog)

    Owner->>UX: Register Journey + Steps + Impact Draft
    UX->>S02: POST /v1/customer-journeys & PUT /impact-profile
    S02->>PG: Persist journey, steps, SHA-256 contentDigest draft + outbox
    S02-->>UX: DRAFT revision 1

    Owner->>UX: Submit Impact Profile
    UX->>S02: POST /v1/customer-journeys/{id}/impact-profiles/1:submit
    S02->>PG: Transition DRAFT -> SUBMITTED + outbox

    Reviewer->>UX: Review Impact Profile
    UX->>S02: POST /v1/customer-journeys/{id}/impact-profiles/1:review
    alt reviewerId == authorId
        S02-->>UX: 403 SELF_REVIEW_PROHIBITED
    else distinct reviewer
        S02->>PG: Transition SUBMITTED -> APPROVED + audit + outbox
        S02-->>UX: 200 OK (APPROVED)
    end
```
