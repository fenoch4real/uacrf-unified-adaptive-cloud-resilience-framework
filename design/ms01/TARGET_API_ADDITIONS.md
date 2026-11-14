# Additional target API capabilities

Version 1.0 | Architecture revision MS 01

The source contains 144 documented operations. The following proposed target additions fill customer-facing gaps for environment/connection enrollment, learning/prediction, workflow status, billing administration and reporting. They are not part of the historical 74 canonical operations and have no implemented-handler claim. Existing operations retain their own wire contracts. These records specify architecture-level fields and guards; milestone implementation publishes closed request/response DTOs and lifecycle constraints before exposing a new route.

All commands use the common idempotency/revision, current identity, scoped disclosure and retained audit rules. Reads paginate bounded collections. Unknown quantities are null rather than invented values. Monetary values use exact decimal strings and explicit currency. Caller fields never select reviewer identity, qualified state, authority tier or verified flags. A webhook signature is checked over its original bounded bytes before normalization.

## S01 target additions

- **POST /v1/tenants**. Enroll an organization. Inputs: display_name, region_policy_ref, initial_owner_subject. Guard: platform.tenant.admit with independently approved bootstrap proof.
- **GET /v1/environments**. List current permitted environments. Guard: identity.environment.read.
- **POST /v1/environments**. Create an owned environment. Inputs: name, provider_scope, data_policy_ref, reason. Guard: identity.environment.manage.

## S03 target additions

- **GET /v1/connections**. List permitted connections. Guard: connection.read for reads; connection.manage or connection.collect for commands; current scope required.
- **POST /v1/connections**. Enroll a provider/source connection. Inputs: name, provider, credential_reference, allowed_scopes, source_capabilities, owner_id, reason. Guard: connection.read for reads; connection.manage or connection.collect for commands; current scope required.
- **GET /v1/connections/{id}**. Read connection and verification state. Guard: connection.read for reads; connection.manage or connection.collect for commands; current scope required.
- **POST /v1/connections/{id}:verify**. Verify actual read-only source access. Inputs: expected_revision, reason. Guard: connection.read for reads; connection.manage or connection.collect for commands; current scope required.
- **POST /v1/connections/{id}:retire**. Stop new collection and retain history. Inputs: expected_revision, reason. Guard: connection.read for reads; connection.manage or connection.collect for commands; current scope required.
- **POST /v1/connections/{id}/discoveries**. Start bounded inventory discovery. Inputs: expected_revision, resource_scopes, deadline, reason. Guard: connection.read for reads; connection.manage or connection.collect for commands; current scope required.
- **GET /v1/discoveries/{id}**. Read source discovery and matching conflicts. Guard: connection.read for reads; connection.manage or connection.collect for commands; current scope required.

## S07 target additions

- **POST /v1/incidents**. Retain an attributable incident. Inputs: workload_id, source_case_id, observed_from, description, evidence_refs, reason. Guard: assessment/model/learning capability as appropriate; current source receipts; independent promotion; feature gates apply.
- **GET /v1/incidents/{id}**. Read incident and learning links. Guard: assessment/model/learning capability as appropriate; current source receipts; independent promotion; feature gates apply.
- **POST /v1/learning-records**. Record hypotheses actions owners and closure tests. Inputs: incident_id, experiment_id, hypotheses, action_items, evidence_refs, expected_revision, reason. Guard: assessment/model/learning capability as appropriate; current source receipts; independent promotion; feature gates apply.
- **POST /v1/forecasts**. Request an approved advisory forecast. Inputs: workload_id, model_version, feature_set_ref, feature_set_digest, horizon_seconds, mode, deadline, reason. Guard: assessment/model/learning capability as appropriate; current source receipts; independent promotion; feature gates apply.
- **GET /v1/forecasts/{id}**. Read forecast lineage uncertainty and evaluation. Guard: assessment/model/learning capability as appropriate; current source receipts; independent promotion; feature gates apply.
- **POST /v1/model-versions**. Retain a model card and immutable version. Inputs: model_id, version, artifact_ref, digest, model_card_ref, evaluation_pack_ref, reason. Guard: assessment/model/learning capability as appropriate; current source receipts; independent promotion; feature gates apply.
- **POST /v1/model-versions/{id}:review**. Independently decide a model version. Inputs: expected_revision, decision, evaluation_result_ids, reason. Guard: assessment/model/learning capability as appropriate; current source receipts; independent promotion; feature gates apply.
- **POST /v1/model-versions/{id}:promote**. Activate only a reviewed passing model version. Inputs: expected_revision, target_mode, reason. Guard: assessment/model/learning capability as appropriate; current source receipts; independent promotion; feature gates apply.
- **GET /v1/workloads/{id}/score**. Read separately calibrated quantitative results. Guard: assessment/model/learning capability as appropriate; current source receipts; independent promotion; feature gates apply.

## S11 target additions

- **POST /v1/workflows**. Retain a bounded workflow definition. Inputs: workload_id, provider_plan_id, validation_plan_id, recovery_path_id, deadline, reason. Guard: execution read/manage capability; S10 permits required for effects; cancellation does not erase accepted effects.
- **GET /v1/workflows/{id}**. Read steps attempts budgets and observed outcomes. Guard: execution read/manage capability; S10 permits required for effects; cancellation does not erase accepted effects.
- **POST /v1/workflows/{id}:cancel**. Fence new dispatch and retain in-flight outcomes. Inputs: expected_revision, control_generation, reason. Guard: execution read/manage capability; S10 permits required for effects; cancellation does not erase accepted effects.
- **GET /v1/provider-plans/{id}**. Read immutable translated plan and preconditions. Guard: execution read/manage capability; S10 permits required for effects; cancellation does not erase accepted effects.
- **GET /v1/execution-attempts/{id}**. Read attributable provider attempt and uncertainty. Guard: execution read/manage capability; S10 permits required for effects; cancellation does not erase accepted effects.

## S12 target additions

- **POST /v1/recovery-paths**. Retain a versioned recovery definition. Inputs: workload_id, scenario, failure_domains, steps, probe_requirements, artifact_ref, digest, reason. Guard: recovery.path.manage; actual certification remains separate.
- **GET /v1/validation-results/{id}**. Read independent observed probe outcome. Guard: validation.read plus source disclosure.

## S13 target additions

- **POST /v1/billing/accounts**. Create a tenant commercial account. Inputs: customer_id, currency, billing_contact, tax_profile_ref, reason. Guard: Current tenant finance capability and independent review where required; webhook uses verified provider signature and allowlisted source, never a finance role.
- **GET /v1/billing/accounts/{id}**. Read account and frozen commercial terms. Guard: Current tenant finance capability and independent review where required; webhook uses verified provider signature and allowlisted source, never a finance role.
- **POST /v1/billing/plan-versions**. Draft a versioned commercial plan. Inputs: plan_id, version, currency, metrics, allowances, prices, effective_from, terms_ref, reason. Guard: Current tenant finance capability and independent review where required; webhook uses verified provider signature and allowlisted source, never a finance role.
- **POST /v1/billing/plan-versions/{id}:review**. Independently approve exact plan version. Inputs: expected_revision, decision, reason. Guard: Current tenant finance capability and independent review where required; webhook uses verified provider signature and allowlisted source, never a finance role.
- **POST /v1/billing/subscriptions**. Create a governed subscription. Inputs: account_id, approved_plan_version_id, starts_at, charging_policy_ref, reason. Guard: Current tenant finance capability and independent review where required; webhook uses verified provider signature and allowlisted source, never a finance role.
- **GET /v1/billing/subscriptions/{id}**. Read subscription and effective periods. Guard: Current tenant finance capability and independent review where required; webhook uses verified provider signature and allowlisted source, never a finance role.
- **POST /v1/billing/subscriptions/{id}:change**. Schedule bounded subscription change. Inputs: expected_revision, approved_plan_version_id, effective_at, reason. Guard: Current tenant finance capability and independent review where required; webhook uses verified provider signature and allowlisted source, never a finance role.
- **POST /v1/billing/subscriptions/{id}:cancel**. Schedule cancellation under the contract. Inputs: expected_revision, effective_at, reason. Guard: Current tenant finance capability and independent review where required; webhook uses verified provider signature and allowlisted source, never a finance role.
- **POST /v1/billing/periods/{id}:rate**. Rate admitted measured usage once. Inputs: expected_revision, usage_cutoff, reason. Guard: Current tenant finance capability and independent review where required; webhook uses verified provider signature and allowlisted source, never a finance role.
- **POST /v1/billing/invoices**. Draft an invoice for a frozen period. Inputs: account_id, period_id, rated_usage_ids, reason. Guard: Current tenant finance capability and independent review where required; webhook uses verified provider signature and allowlisted source, never a finance role.
- **POST /v1/billing/invoices/{id}:post**. Post an immutable reviewed invoice. Inputs: expected_revision, reason. Guard: Current tenant finance capability and independent review where required; webhook uses verified provider signature and allowlisted source, never a finance role.
- **GET /v1/billing/invoices/{id}**. Read invoice lines taxes and allocations. Guard: Current tenant finance capability and independent review where required; webhook uses verified provider signature and allowlisted source, never a finance role.
- **POST /v1/billing/payment-attempts**. Request collection with stable provider identity. Inputs: invoice_id, amount, currency, payment_method_ref, reason. Guard: Current tenant finance capability and independent review where required; webhook uses verified provider signature and allowlisted source, never a finance role.
- **POST /v1/billing/credits**. Record an attributable credit. Inputs: invoice_id, line_allocations, amount, currency, reason, evidence_refs. Guard: Current tenant finance capability and independent review where required; webhook uses verified provider signature and allowlisted source, never a finance role.
- **POST /v1/billing/refunds**. Request bounded refund against paid allocation. Inputs: payment_id, credit_id, amount, currency, reason. Guard: Current tenant finance capability and independent review where required; webhook uses verified provider signature and allowlisted source, never a finance role.
- **POST /v1/billing/reconciliations**. Reconcile source usage invoices and provider settlements. Inputs: account_id, period_id, source_cutoff, reason. Guard: Current tenant finance capability and independent review where required; webhook uses verified provider signature and allowlisted source, never a finance role.
- **POST /v1/billing/provider-webhooks/{provider}**. Admit a signed provider event. Inputs: provider_event_id, signed_payload. Guard: Current tenant finance capability and independent review where required; webhook uses verified provider signature and allowlisted source, never a finance role.

## S14 target additions

- **GET /v1/activity**. Read scoped recent activity and projection watermark. Guard: audit.read/export or notification.manage with current source and destination policy.
- **POST /v1/notification-routes**. Create owner-aware finding delivery policy. Inputs: workload_scope, event_types, destination_reference, escalation_policy, reason. Guard: audit.read/export or notification.manage with current source and destination policy.
- **POST /v1/notification-routes/{id}:update**. Revise routing without hiding prior deliveries. Inputs: expected_revision, destination_reference, escalation_policy, reason. Guard: audit.read/export or notification.manage with current source and destination policy.
- **POST /v1/evidence/bundles**. Request sealed export after source-owner disclosure. Inputs: source_references, purpose, classification, deadline, reason. Guard: audit.read/export or notification.manage with current source and destination policy.

## Publication and completion gate

Each addition receives a stable operation ID, closed JSON schemas, error and status contracts, owner tables/migrations, current-authorization checks, idempotency semantics, restrictive sequence/activity alternatives and positive/adverse executed tests. Architecture coverage does not substitute for those implementation deliverables. Provider webhook admission acknowledges retained receipt only; invoice/payment/refund completion remains a separate observed ledger outcome.
