# Provider icons actions and screen context

Version 1.0 · Design revision 6 · Visual refinement 2

The prototype now carries the richer context shown in the screen images. Sixteen key review and agent views show the accountable owner, current disposition, relevant capability and next review. Overview includes owners and the illustrative event history. Checkout evidence is Unknown, and three example capabilities lack current evidence; the image and reading guide use the same state.

Provider marks remain beside names in graph nodes, shared resources and the service/agent inventories. Placements are fictional examples. Provider identity is separate from runtime health, evidence qualification and recovery authority. Unknown placement must use a neutral label; a multi-provider capability must show its actual placements rather than infer one from its model vendor.

At the revision-6 baseline, all 49 then-registered views had a primary next step in ACTION_REGISTER.json. Alignment revision 22 extends the current register to 58 views across 13 destinations. Navigation goes to the relevant review page. Preparation actions collect a bounded review note and show local validation and cancellation. They never submit an HTTP request or claim domain success. Current mutation contracts still require their strict body schemas, trusted identity, permissions, idempotency and server checks when actual handlers are implemented.

The action register traces each API-associated action to the existing canonical operation, its mandatory controls and database mapping. No new canonical endpoints or database tables are introduced by presentation state. The two settings actions for connection provisioning and access editing remain unavailable because their live handlers are absent. Public edit, unlink, reorder and retirement contracts remain deferred; no enabled button presents them as implemented. Recovery dispatch stays disabled with a visible reason.

These checks close the identified presentation and action-mapping inconsistencies. They do not establish complete application implementation. Browser interaction, keyboard and accessibility acceptance and actual cloud/agent execution remain pending. The earlier readiness score is unchanged.
