# S02 Catalog, Protected Agent Workloads & Customer Journeys Specification

## Overview
`catalog-service` (`S02`, port `8083`, database `uacrf_catalog`) models what an enterprise runs and what business outcomes must survive failure. It tracks conventional software services separately from autonomous customer AI agents and connects both to versioned Customer Journeys.

## Simple Requirements & Invariants
1. **Service & Agent Separation:** Maintain independent views for Conventional Services (`GET /v1/services`) and Protected Customer Agent Workloads (`GET /v1/agent-workloads`, `SINGLE_AGENT` or `MULTI_AGENT`), creating internal `AGENT_ANCHOR` rows for standalone agents without distorting conventional service metrics.
2. **Immutable Workload Revisions:** Store cryptographically hashed `agent_workload_version` manifests (`CanonicalJson`) and require distinct-author activation before a new agent version goes live (`FR-AWL-001..012`).
3. **Customer Journeys & Impact Profiles:** Organize services and AI capabilities into ordered `customer_journey` steps with criticality tiers (`REQUIRED`, `OPTIONAL`, `UNKNOWN`) and versioned `journey_impact_profile` workflows (`DRAFT -> SUBMITTED -> APPROVED/REJECTED`, enforcing `reviewer != author`).
4. **Shared Resource Lineage:** Track shared dependencies across `MODEL`, `KNOWLEDGE`, `TOOL`, `MEMORY`, `IDENTITY`, and `RUNTIME` assets so hidden concentration risks surface before failover.
