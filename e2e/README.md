# Browser E2E tests

The live browser suite verifies the local Keycloak OIDC login and UX01 →
EDGE01 → S01/S02/S07 workflows against disposable infrastructure.

## Run

Prerequisites: Docker with a running daemon, Node.js, npm, and free loopback
ports 8080–8087, 8089, and 8180.

```sh
npm ci --prefix e2e
(cd e2e && npx playwright install --with-deps chromium)
npm --prefix e2e run test:live
```

`test:live` builds the required application images, starts an isolated
PostgreSQL/Keycloak/application stack, including S02, S03, and S07, waits for service
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
`assessment.evaluation.read`, and `assessment.recommendation.read` grants and
browses synthetic cases, evaluations, and an investigation-only recommendation
through UX01, EDGE01, and S07. The suite checks workload filtering, empty
history and recommendations, and denial for a separately admitted user
without those grants. Fixture rows and placeholder digests are inserted
directly into the disposable database, bypassing case creation, sealing,
evaluation, and recommendation generation; they are not qualified evidence
or provider acceptance. The S07 case, evaluation, and recommendation list
APIs are unpaginated.

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
