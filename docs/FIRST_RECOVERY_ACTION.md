# First bounded recovery action

Status: selected for M0 (local adapter only). Cloud-provider acceptance is not
claimed. This is the action S10/S11/S12 prove end to end in M5.

## Action

`workload.set-replicas`: set the replica count of one registered local test
workload to a target value.

Why this one:

- Typed and narrow: one target id and one integer; no shell, query or
  model-generated instruction crosses the boundary.
- Reversible: the prior count is recorded before dispatch, so compensation is
  the same action with the prior value.
- Idempotent: setting the same target twice has the same effect, so exact retry
  and resume are safe.
- Independently observable: the adapter exposes its own state, separate from
  S11's record of what it asked for.

## Bounds

| Bound | Rule |
|---|---|
| Target | Only a workload registered in S02 with the local adapter binding for the same tenant and environment |
| Value | Integer within the workload's declared `[min_replicas, max_replicas]`; default 1..5; step limit of 2 per attempt |
| Authority | A current S10 action authorization for the exact target, value, tenant, environment and plan digest; short-lived and single attempt |
| Approval | Independent human review distinct from the requester; S01 re-checked at reservation and at final dispatch |
| Failure | Outage or revoked authority stops the action; it is never read as approval |

## Local test adapter

A small stub service (`sample-data/` fixtures describe its workloads, clearly
labeled as sample data) with:

- `PUT /workloads/{id}/replicas` taking `{value, attempt_id}`; repeating the
  same `attempt_id` returns the original result.
- `GET /workloads/{id}` returning current replicas, last `attempt_id` and an
  append-only change journal owned by the adapter.
- A fault switch for tests: reject, time out after applying (ambiguous), or
  apply then report failure.

The adapter holds no UACRF credentials beyond a per-attempt permit and cannot
reach S01, S02 or any database.

## Acceptance for M5

1. A valid authorization changes replicas, and an independent read of the
   adapter journal matches S11's receipt.
2. Exact retry with the same `attempt_id` applies once.
3. A timeout after apply is recorded as uncertain and resolved by a status
   query, never by blind re-dispatch.
4. Revocation between reservation and dispatch blocks the change; the
   adapter journal shows no write.
5. Compensation restores the recorded prior value.
6. Out-of-bounds value, wrong tenant or environment, and replayed permits are
   rejected before the adapter is called.
