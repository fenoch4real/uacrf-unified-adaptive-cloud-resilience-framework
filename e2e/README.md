# Browser E2E tests

The live browser suite verifies the local Keycloak OIDC login and UX01 →
EDGE01 → S01/S02/S07 workflows against disposable infrastructure.

## Run

Prerequisites: Docker with a running daemon, Node.js, npm, and free loopback
ports 8080–8087, 8089–8091, and 8180.

```sh
npm ci --prefix e2e
(cd e2e && npx playwright install --with-deps chromium)
npm --prefix e2e run test:live
```

`test:live` builds the required application images, starts an isolated
PostgreSQL/Keycloak/application stack, including S02, S03, S07, S08, and S10, waits for service
health endpoints, and runs Playwright headlessly. It generates test passwords,
client secrets, and delegation keys in a private temporary directory;
credentials are not stored
in the repository. The runner removes its uniquely named containers, network,
and database volume on exit. Playwright uses one worker because scenarios
mutate the shared bootstrap principals and isolated service data.

The Playwright tests cover administrator and approver capability separation,
access after a browser reload, invalid-password rejection, protected-route
redirection, and local-session logout. The administrator can admit an
unadmitted user from the trusted local Keycloak directory; the S01 receipt
confirms admission creates no memberships or capabilities. The approver sees
the directory without admission controls, and a CSRF-valid direct submission
is denied by S01. Since the directory is context-scoped to existing
memberships, the newly admitted user does not appear there until separately
granted access.

The same browser journey then verifies the existing independent grant
contract: the approver records a decision for the admitted principal, and the
separate administrator applies it using the authorization reference and
matching grant details. S01 consumes that reference with membership creation.
The approver's direct membership submission and the administrator's direct
decision submission are both denied. This is a manual trusted-channel
handoff—there is no persisted request inbox or browser approval queue.

The browser stack also gives the sample user separate service-scoped
policy-read and policy-write grants and a test SYSTEM principal a policy-write
grant. It proposes 26 synthetic drafts through S01, EDGE01 and S10, and
verifies two-page UX01 browsing and navigation between historical and fresh
current reads. The
user submits a first and later revision through UX01, replays the original
request after the later revision, and verifies a changed request with the same
key conflicts. It also checks denial for another service and an ungranted
user. A separate human reviewer receives service-scoped policy-read and review
grants, rejects the second revision, and verifies unchanged replay,
changed-content conflict and stale-revision conflict. The proposer is denied
self-review. A separate signed-approval scenario uses a disposable RSA key
and a test-only trust set scoped to the fixed local approver and service;
the repository's local signing helper produces the assertion.
Its read and review memberships are inserted directly, bypassing S01 grant
decisions. It verifies invalid signature denial, approval, unchanged replay
and changed-content conflict. These records do not authorize actions or
establish production trust provisioning or provider acceptance.

The synthetic inventory test separately admits a human principal and uses the
same approval and membership workflow to grant owner eligibility, global
owner selection, S02 journey/service/agent read/write, and S03 read/write access.
It also admits the local S03 publisher service account as a SYSTEM runtime
principal and grants `runtime.principal.use` and `evidence.inbox.write` through
that same independent approval workflow. The runner passes the disposable
publisher secret only to Playwright, which authenticates with client
credentials, verifies S01 resolves it to that SYSTEM principal through EDGE01,
and requests a 30-second S04 delegation limited to `evidence.inbox.write`.
The flow also confirms that an ungranted catalog capability is denied. This
is isolated local identity evidence, not production deployment-identity or
provider acceptance. It imports 122 sample observations (42 inventory and
80 metrics), safely replays the import, checks both inventory pages, and
reads the paginated retained-observation view for all three metric payload
shapes. It checks the per-source `NEVER_RUN` collection status and the explicit
freshness limitation on the inventory page. It does not contact cloud providers
or qualify evidence.

The same authorized user creates a draft journey, registers and links a service
through UX01, EDGE01, and S02, then reads the journey and linked service back.
The user also registers standalone and service-bound agent workloads, then
reads both persisted records back through UX01 using a narrow
`catalog.agent.read` delegation. The readback verifies workload identifiers,
kind, owner, runtime principal, and service association; no runtime action is
performed. A separately admitted transferee with only `owner.eligible` is
denied workload readback by S01 and receives no records. Separately, the suite
admits the dedicated `uacrf-s03-collector`
service account as SYSTEM, grants it only `connections.observation.write`
through the approval workflow, and verifies client-credentials resolution and
a narrow S03 delegation through S01. An ungranted catalog capability is
denied. The collection runner remains disabled in this browser stack, so this
is identity/delegation evidence only—not collection execution or provider
acceptance.

The same user receives independently approved global `assessment.case.read`,
`assessment.case.write`, `assessment.evaluation.read`, `assessment.evaluation.run`, and
`assessment.recommendation.read` grants. The suite creates and replays a draft
through UX01, EDGE01, S01 and S07, reads it back, reviews and seals it, and
verifies that replaying the old seal request conflicts. It evaluates the sealed
case with an empty measurement set, replays the same request and checks that
only one historical result is retained. The result is `INSUFFICIENT_EVIDENCE`
with verification `SKIPPED`, not qualified evidence. A separately admitted
user is denied creation, sealing and evaluation. The supplied objective and graph
references are synthetic and not verified at creation or sealing. Separately,
the user browses fixture
cases, evaluations, and an investigation-only recommendation. Those fixture
rows and placeholder digests are inserted directly into the disposable database,
bypassing case creation, sealing, evaluation, and recommendation generation;
they are not qualified evidence or provider acceptance. The S07 case,
evaluation, and recommendation list APIs are unpaginated.

The browser suite also grants `assessment.incident.write`,
`assessment.incident.read`, `assessment.learning.read` and
`assessment.learning.write` independently and follows the case-to-incident and
incident-to-learning links, including the second page of each. An ungranted
user is denied both views and incident creation. A creation attempt using
synthetic evidence against a SKIPPED evaluation is rejected, with the key and
input retained; no incident is created. The 51 incidents and 51 learning records are
directly inserted synthetic fixtures with placeholder provenance and digests;
the test submits and replays a learning proposal for one synthetic incident and
verifies only one retained proposal. An ungranted user is denied proposal
submission. Proposal creation does not revalidate evidence or establish review
or adoption. The test does not demonstrate successful verified incident creation,
evidence qualification or evidence-qualified learning approval and cannot
establish provider acceptance.

The disposable stack explicitly enables S07 learning review and independently
grants `assessment.learning.review` to the sample human. The browser verifies
self-review denial, denial of approval for a directly inserted synthetic
fixture, an independent rejection and historical read, duplicate-decision
conflict, and denial for an ungranted user. The synthetic fixture does not
demonstrate successful evidence-qualified approval; review remains disabled by
default outside this test stack and a decision does not authorize adoption.

The suite also browses S08 metadata through UX01 and EDGE01. Independently
granted public and internal read capabilities show the appropriate synthetic
rows, exclude another tenant, and reject an ungranted restricted classification.
A user without knowledge-read access is denied. These metadata fixtures are
inserted directly into the disposable database, bypass the knowledge lifecycle,
and are not provider acceptance or evidence of current eligibility.
The same suite stages synthetic plaintext through UX01, EDGE01 and S08, replays
the identical keyed request, confirms one visible candidate, and denies an
ungranted user. A staging receipt is historical and does not establish approval
or retrieval eligibility.
The suite grants a separately admitted human knowledge-read and review
capabilities. The proposer is denied self-review; the independent reviewer
reads the synthetic candidate plaintext and rejects it, and current metadata
shows the result. A user without those capabilities cannot read the candidate.
That review tests rejection, not provider acceptance.
The suite also stages a separate synthetic candidate, independently approves
it, denies withdrawal by a user without the withdrawal capability, and
withdraws it through the version-bound confirmation flow. It reads back the
withdrawn state. This lifecycle test does not establish provider acceptance.
The same flow runs and replays a keyed exact-term retrieval, denies a user
without `knowledge.retrieval.run`, then verifies the replay conceals the
withdrawn excerpt. Its synthetic manifest is `NOT_VERIFIED`, not a verified
case binding or provider acceptance. A separate read grant is required to
recheck the known retrieval ID: the ungranted reviewer is denied, while the
authorized reader can find it in bounded retrieval history and sees the
excerpt concealed after withdrawal. The ungranted reviewer is also denied
history access; the browser test does not exercise multi-page history.

An identity manager can also disable an enabled principal from the directory
with a required audit reason and the displayed revision. The browser does not
offer re-enabling; S01 preserves existing memberships and requires trusted
source verification and independent grant review before re-enabling is
available. The suite verifies the approver cannot submit a direct disable
request and the authorized administrator can disable the principal.

The isolated bootstrap administrator acts as the transfer operator without
catalog-write capabilities. The browser flow transfers the draft journey and
then the unanchored service to a second, separately admitted human with
independently approved `owner.eligible`, and verifies each updated owner on
readback. S01 supplies the eligible-owner list and narrow S02 read delegation;
the transfer mutation delegation contains only `ownership.transfer`. The
service is transferred before its agent workload is registered. A separate
principal without transfer authority cannot open the journey owner selector
or transfer page, and CSRF-valid direct journey-creation and transfer
submissions remain denied. Missing and invalid CSRF tokens are rejected for
context selection and logout without changing the selected context or session;
valid browser forms still work. These are HTTP rejection checks, not a complete
cross-origin or HTTPS review. The suite uses the isolated first-admin
bootstrap fixture and grants catalog capabilities only through the independent
approver/administrator workflow; it does not test production identity-provider
configuration.
