# Protected workload interaction contract

Version 1.0 design revision 5.

Read-only views require workload.read and current tenant, environment, service, purpose and disclosure checks. Registration and manifest intake require workload.write; activation requires workload.activate and a distinct human reviewer. Dispatch reservation requires workload.execute, stop/handoff requires workload.control, evaluation requires workload.evaluate, and recovery staging requires workload.recover plus the existing authority chain. The trusted server rejects agent control identities; runtime producers enter through an authenticated broker. Never trust a client-selected identity or verified flag.

Show the workload kind and exact version, runtime state, evidence qualification, operational risk, evaluation verdict and recovery disposition separately. Display missing evidence as Unknown rather than a green healthy state. Explain blocked actions beside the action. A queued receipt must stay Pending until the durable domain result arrives; reloading a view must not repeat an effect. Preserve generation/revision conflicts and provide a refresh/review path.

Graph views expose tool effects and common failure domains. Checkpoint views show event sequence, namespace, byte digest, expiry and uncertain-effect reconciliation. Recovery views show the source checkpoint, remaining limits, exact proposal/approval/authority/workflow and current revalidation. Validation views explain missing mandatory checks and windows. Handoff previews disclose only authorized context and identify an accountable recipient. Billing views keep reservations, measured usage and charges in separate columns.

Provide keyboard navigation, visible focus, meaningful labels, screen-reader state updates and non-colour status cues in implementation. Confirm destructive or consequential runtime actions under the applicable approval policy. The prototype only previews interactions; no runtime, external notification or production accessibility acceptance is established.
