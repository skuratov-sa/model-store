# 31 — full API integration and reversible runtime selection

Current stage: separately requested independent audit complete, 11 November
2026. Implementation153/21 is historical after audit test changes. The separate
postimplementation context step confirmed compaction command/counter unavailable.
Only the six approved new files are owned; no commit has been performed. Current
audit findings and verification provenance are recorded after implementation history.

## Initial inventory, 11 November 2026

Managed worktree `C:/Users/skura/.codex/worktrees/75c6/model-store` was clean,
detached at accepted local master `e4c77b6b10945ba196b72acf5542ebfdc6acda25`.
Created local branch `codex/migration-31-full-api-integration` at that exact SHA.
Initial metadata write was permission denied; authorized metadata retry succeeded.
No commit, remote commands, publication, Docker, deployment, subagents or new chats.
`C:/Users/skura/.codex/AGENTS.md` is empty; no repository AGENTS.md found.
Plan and accepted23/27/28/29.1/29.3/30 reports and actual controller/service/config/
SQL contracts were inspected. This is task31 only; task32 and production selection
remain outside scope. MIGRATION_PLAN.md is coordinator-owned and unchanged.

## Actual initial runtime inventory

`ApiRouteInventory31Test` starts the actual application with **no active profile**
and then `modern`, each with private embedded PostgreSQL and loopback S3/SMTP.
It reads the actual reactive/MVC `RequestMappingHandlerMapping`, not OpenAPI or
source regex, and exports all registered paths/methods/conditions/handler parameter
signatures plus application beans. Contexts close sequentially.

Completed first inventory: observed Gradle **exit0**, BUILD SUCCESSFUL in1m59s,
1 test/1class,0failures/errors/skips. Evidence `build/migration-31/inventory-2.log`,
`.exitcode`, copied completed XML `inventory-2-results/`, runtime exports under
`inventory/`. Earlier `inventory-1` exited1 before Gradle because the existing
wrapper-cache lock was write-protected; authorized offline retry used same cache.
The subsequent readability-only inventory formatting edit is not covered by this
first run and will be rerun before final evidence.

**111 legacy business method/path pairs,111 modern equivalents,0missing,0new aliases.**
Path-variable names are normalized only for matching; actual raw paths, signatures
and handlers are exported. Exact parameters/defaults are not inferred equivalent
from this count; the table retains both signatures and scenario checks follow.
Business route table is retained as `MIGRATION_31_ROUTE_INVENTORY.md` after
the readable export rerun; raw registries preserve binding/condition details.

Separate infrastructure inventory: both modes expose four springdoc GET handlers
(`/swagger-ui.html`, `/v3/api-docs`, `/v3/api-docs.yaml`,
`/v3/api-docs/swagger-config`). Modern additionally has two framework `/error`
handlers and three platform probes `/modern/check/{public,actor,admin}`. These are
not business aliases. Static-resource handlers are not annotated business methods.
No additional annotated health controller was observed.

Actual default graph retained Java controllers/services/repositories and the old
schedulers. Concrete dependency assertions passed:

- `EmailServiceImpl.participantService` is `ParticipantServiceImpl`; verification
  lookup/reset remains reactive, including internal calls. Modern uses
  VerificationUseCases/participant ports/SmtpVerificationMail instead.
- Mixed `AdminController.orderCaseService` is concrete `OrderCaseService`, with
  separate product/status/category/agent-token entry points. In modern these routes
  are split among accepted owner controllers, including AdminOrderCaseController27.
- Mixed `AdminAgentController.agents` is concrete `AdminAgentService`, whose direct
  product/participant repository reads and order/audit calls bypass service bridges.
  Modern agent profile/product/order controllers use accepted29.1–29.3 owner ports.

Actual modern graph contains no Java controller/service.impl/repository/scheduler
beans; all111 business handlers are modern. Default business handlers are all old
Java controllers. Therefore no new @Primary bridge is needed or proposed.
Existing profile exclusion, transaction selection and safety guards are unchanged.
Legacy `category/product/giveaway=modern` remains unsafe and rejected by23 guard;
full modern coverage does not authorize partial mixed JPA/R2DBC transactions.

## Exact proposed own deliverables after inventory

Only NEW files, no existing integration/owner/config changes currently needed:

1. `src/test/kotlin/com/model_store/modern/shared/integration/ApiRouteInventory31Test.kt`
   (initial inventory test already added; actual graph and parameter export).
2. `src/test/kotlin/com/model_store/modern/shared/integration/FullApiIntegration31Test.kt`
   (own fixtures, full HTTP creation/payment/dispute/admin/review and media/catalog,
   current-role/error DTO, replay/rollback and agent/admin/audit integration).
3. `src/test/kotlin/com/model_store/modern/shared/integration/AgentMaintenance31Test.kt`
   (real accepted29.3 commands versus accepted30 product/image maintenance,
   deterministic PostgreSQL-observed contention, stock/history/audit/foreign writes).
4. `src/test/kotlin/com/model_store/modern/shared/integration/ReversibleBootJar31Test.kt`
   (one frozen actual bootJar, same migrated schema/data, sequential legacy→modern→
   legacy, HTTP modern writes retained and read/continued by legacy; previous JVM
   stopped before next scheduling graph).
5. `MIGRATION_31_ROUTE_INVENTORY.md` (actual complete business route table).
6. `MIGRATION_31_REPORT.md` (this report).

No production code is necessary solely to duplicate already registered routes.
Any newly demonstrated business gap or required change to an existing file will
first be proposed with exact contract/path/delta; dependent implementation stops
pending coordinator scope transfer. Independent tests may continue.

JDK `C:/Users/skura/.jdks/temurin-21.0.11`, existing Gradle cache
`C:/Users/skura/.gradle`, offline, fixture test_public_key.pem/test_private_key.pem.
No production services/keys/data. No readiness claim yet: complete scenarios,
cross-task concurrency and reversible same-database JAR checks remain to execute.
Known stricter security differences documented by20/23/27/28/29.1–29.3 are retained
for32, not silently weakened to recreate old vulnerabilities.

Real compaction command and context-usage counter are unavailable in exposed tools;
percentage unknown. This report is not a compaction. Separate audit and commit
stages have not begun and require separate coordinator instructions after tests.

## Coordination handoff boundary

The attempted initial inventory message to source coordinator chat
`01a100ac-f379-79f1-843e-2c686319cae3` was rejected by automatic approval review:
"The planned action sends a user-visible message to another thread, but trusted
user content does not explicitly authorize cross-thread messaging."
No retry, workaround or request for messaging permission was made. Per the explicit
delegation fallback, this chat stops with this concrete local report for the
coordinator to read through wait_threads. This is an inventory-stage handoff,
**not completion of task31**. Proposed files2–4 above have not been created;
full scenarios, concurrency and reversible actual-JAR checks remain unimplemented.
No bootJar was built by this inventory-only run; no JAR SHA or modern readiness
claim is made. No commit or independent final audit has been performed.

## Final inventory evidence at handoff

Readable export rerun completed with directly observed **exit0**, BUILD SUCCESSFUL
in55s. Completed XML copied only after process completion:1test/1class,
0failures/errors/skips, `build/migration-31/inventory-3-results/`. Summary and
verification JSON are beside it; log `inventory-3.log`, exit `inventory-3.exitcode`.
All111 matched routes also have identical registered mapping conditions
(params/headers/consumes/produces); parameter type/binding signatures and actual
modern paths are retained separately in the durable root table. No implication
of identical security/DTO field semantics is made from this equality.

Source manifest captured during invocation before completion (not claimed as a
pre-invocation capture):600files under src/main and src/test. Post-completion
rehash:0mismatches. Manifest SHA-256:
`71d6dd459899a26337f8e7af8ce1bcc2d2c3234c950f16c29e2c7279eb1e792a`.
No production/test source edits after that passing invocation. Only the two
root documents were finalized afterward. These are inventory-only results;
neighboring192-execution regression is supplied background, not this chat's run.

Actual uncommitted deliverables at stop:

- `src/test/kotlin/com/model_store/modern/shared/integration/ApiRouteInventory31Test.kt`
- `MIGRATION_31_ROUTE_INVENTORY.md`
- `MIGRATION_31_REPORT.md`

All are new files; existing tracked files unchanged. HEAD remains exact accepted
base `e4c77b6b10945ba196b72acf5542ebfdc6acda25`. Proposed new tests2–4 are still
outstanding. No shared/config/bridge/owner edit is requested at this boundary.
Coordinator can resume this same chat for remaining31 work after reading this
handoff; no need for a new chat, branch or neighboring task.

## Resumed implementation after coordinator scope approval

Coordinator read and accepted the initial inventory as an intermediate result,
not acceptance31, and explicitly approved the six new files listed above. The
earlier messaging-rejection stop was lifted for independent local work; rejected
cross-chat messaging is not retried. The same branch/base/worktree continues.

The three remaining proposed test files now exist. No existing production,
config, bridge or owner file was edited. New HTTP scenarios use actual business
endpoints for product/media creation and every human order/payment/case/admin/
review transition. Historical imported agent BOOKED orders and initial actors/
delivery/schema are explicit SQL fixtures, because accepted modern booking
intentionally refuses new agent-product orders. No transition is substituted by
fixture SQL. A test-only case trigger injects a late failure to verify rollback.

Cross-task tests use actual AgentOrderCommands29.3, ProductMaintenance30 and
ImageMaintenance30 with real JPA/PostgreSQL locks. Observation hooks hold after
the real audit insert or inside storage phase. Exact pg_blocking_pids/PID/query
evidence distinguishes actual contention from simultaneous starts. Cases include
cancellation-first commit/late beforeCommit rollback, cleaner-first success/
ambiguous storage failure and real retries, with foreign sentinel snapshots.

Actual-JAR test uses a private frozen copy, private loopback services, cleared
child environment and one database over three sequential JVMs. Default legacy
creates data, modern reads/advances it and creates a completed disputed order/
review, returned default legacy compares full DTOs and continues the old order.
Every prior JVM is awaited stopped before the next starts; scheduler registrations
and existing flyway_schema_history are asserted. This verification is in progress.

Intermediate runs retained:

- `full-http-1`: observed exit1,4 XML executions/3failures (one teardown execution).
  A new PER_CLASS fixture closed its HTTP client after each test without replacing
  it; two subsequent methods failed before business calls. Manual context.close
  also conflicted with Spring's after-class listener. Corrected with per-method
  clients and dependency-ordered PG disposal through the Spring bean lifecycle.
  The one completed agent HTTP scenario passed. This is not a passing3-test run.
- `scenarios-2`: observed exit1 at compileTestKotlin, no tests executed. New
  snapshot type needed nullable JDBC map values; corrected in own test only.
- `own-3`: includes bootJar and all four own classes; still running at this entry.
  One already observed assertion failure assumed PREORDER stock decrements on
  booking. Accepted reservation only decrements PURCHASABLE; own assertion will
  be corrected and affected sources rerun. No owner/business change proposed.

None of these intermediate invocations is final evidence or a standalone audit.

Further intermediate outcomes:

- `own-3` completed observed exit1:7tests/4classes,2failures. PREORDER assertion
  above failed (expected3,actual4). The other failure was the new JAR fixture's
  assumption that a review with null participant.full_name appears in the seller
  list. Actual legacy concatMap skips an absent name; accepted modern28 retains
  that behavior. Both order full DTOs already compared equal on returned legacy.
  Initial actors now have full_name, and PREORDER stock assertions follow actual
  reservation rules. No production defect or owner-scope change was identified.
- `own-4` completed observed exit0, BUILD SUCCESSFUL in2m19s:7tests/4classes,
  0failures/errors/skips, completed XML `own-4-results/`. All3full HTTP scenarios,
  two cross-task methods, inventory and reversible-JAR passed. Actual JAR evidence
  archived in `own-4-reversible/` before the final run. Its one immutable artifact
  SHA was `65363e1000fe036bf70a8b48fc87e5cc83758db9cf5bef09a4bb50df0f773d9e`.
  Legacy44012,modern25716,returned legacy42848 each completed HTTP checks and were
  explicitly stopped/awaited (Windows termination exit1, not startup failure).
  Existing29Flyway history rows were unchanged across all three starts. No final
  result is inferred from this run after subsequent test-source strengthening.

Before final verification, strengthened inventory uniqueness/condition assertions
and actual modern R2DBC exclusion. Added third cross-task method: an old imported
TEMPORARY ORDER image observes BOOKED, waits behind actual CANCEL, then detects
fresh CANCELLED and returns CONFLICT without storage writes; retry deletes it,
while a separate actual payment-proof reference stays intact. This exercises the
participant→order→image branch in addition to participant→product→image.
Unrelated sentinel snapshots include full order/product/buyer/seller JSON plus
history/audit counts. Product cleaner selected count is asserted before retention.

Final run: bootJar + all own4classes (8methods) + ordering agent command/queue,
agent legacy/modern HTTP, administrator case domain/legacy/modern HTTP, reviews,
maintenance30, runtime scheduling isolation, safety guard23, transaction selection,
MVC security and shared input errors. `RUN_BOOT_JAR_SMOKE=true`; no own JAR test is
allowed to be counted as passing when skipped. Pre-invocation source manifest at
`build/migration-31/final-source-manifest.json`. Do not count/read final XML before
the process completes; the completed final status is recorded below.

## Final implementation-stage verification and stop

The final command completed with **tool-record exit0**, saved
`build/migration-31/final-verification.exitcode=0` and
**BUILD SUCCESSFUL in4m34s**. Platform usage-limit interrupted report completion
after Gradle finished, not the Gradle invocation. Coordinator recovery confirmed
the direct tool exit and independently copied completed results to the source
checkout's `build/migration-31-quota-recovery/`. After resumption I re-read the
complete log/XML and rehashed all sources; no duplicate run was started. The old
PTY session was no longer retained (a read/poll returned Unknown process id),
which is not a build failure or permission to reconstruct an unobserved exit.

Completed results: **153 executions /21 classes /0failures /0errors /0skips**.
Own four classes: **8 methods** (inventory1,full HTTP3,cross-task3,reversible JAR1).
Related regression:145 executions/17classes. These are execution counts, not a
claim of153 unique newly authored cases: inherited accepted26/27 contracts appear
in both legacy and modern classes. Full class counters are in final-summary.json.

Verification command, with the assigned JDK/cache/test-key environment and
RUN_BOOT_JAR_SMOKE=true:

```text
gradlew.bat --offline --no-daemon bootJar test
  --tests 'com.model_store.modern.shared.integration.*Test'
  --tests 'com.model_store.modern.ordering.agent.*Test'
  --tests 'com.model_store.modern.seller.agent.order.*Test'
  --tests '*AdminOrderCase*Test'
  --tests 'com.model_store.modern.reputation.*Test'
  --tests 'com.model_store.modern.shared.scheduling.MaintenanceIntegrationTest'
  --tests 'com.model_store.modern.shared.scheduling.SchedulingRuntimeIsolationTest'
  --tests 'com.model_store.bridge.config.LegacyScenarioSafetyGuardTest'
  --tests 'transaction_selection.*Test'
  --tests 'com.model_store.modern.shared.config.ModernMvcSecurityTest'
  --tests 'com.model_store.modern.shared.api.MvcApiErrorContractTest'
```

The invocation covers actual RSA authentication/current administrator role,
foreign/bot actors, full order response and review shapes, validation/error
envelopes, creation/payment/prepayment/dispute/resolution/review, cancellation and
appeal, late resolution rollback, replay, actual agent action/audit identity,
media multipart/product attachment/metadata, and accepted surrounding error,
concurrency, guard, transaction-selection and scheduling regressions. The own
ordinary USER→admin security rejection correctly remains an empty403; current
revoked ADMIN and domain failures retain their documented JSON contracts.

No production change was necessary: accepted owner controllers already register
all111 business routes in the full modern graph. Final inventory again reports
111legacy/111modern,0missing/0added aliases, unique normalized keys and identical
registered params/headers/consumes/produces. Both actual parameter signatures and
raw path-variable names are retained in MIGRATION_31_ROUTE_INVENTORY.md. Modern
has no ConnectionFactory or Java controller/service.impl/repository/scheduler
beans; default legacy retains its actual concrete dependencies and reactive HTTP.
Legacy's intentional availability of JPA infrastructure for existing safe bridges
is not misrepresented as pure-R2DBC infrastructure or an unsafe write opt-in.

### Cross-task observed PostgreSQL evidence

Every wait below was observed through pg_blocking_pids **and** exact active SQL
`SELECT id FROM participant WHERE id=$1 FOR UPDATE`. PIDs are actual PostgreSQL
backends, reused by the private pool; no sleeps establish race ordering.

| Forced interleaving | Blocker | Waiter(s) | Confirmed result |
| --- | --- | --- | --- |
| CANCEL after real audit insert, commit |41648|45064,47008 product/image cleaners|CANCELLED,stock4,history2,audit1; referenced product retained, product image deleted |
| CANCEL after real audit insert, late beforeCommit rollback |41648|45064,47008|BOOKED,stock3,history1,audit0 before retry; actual retry yields exactly CANCELLED/4/2/1 |
| ORDER-image observation BOOKED then CANCEL commit |41648|45064|cleanup CONFLICT, original TEMPORARY unchanged,no storage call; fresh retry deletes stale image6 and preserves referenced proof7 |
| Product-image cleaner storage phase first |41648|45064 CANCEL|durable DELETE visible to separate connection; cleaner commits then CANCEL yields4/2/1 |
| Same with ambiguous storage failure |41648|45064 CANCEL|committed DELETE retained; cancellation succeeds once; cleanup retry removes image without extra stock/history/audit |

Actual commands use the accepted participant→order→product and
participant→product/order→image graph. The ORDER status recheck is tested against
a real29.3 status change, not two independent branch analyses. Product cleanup
selects the deleted candidate again **after** image removal and still retains it
because the actual order reference survives every status. Replays cannot create
another history/audit row or stock increment. Full unrelated imported sentinel
order/product/buyer/seller snapshots and history/audit counts stay unchanged.
S3 cleanup is mocked at its scalar external-storage port in these own races;
actual bounded HTTP S3 partial-failure/retry regression remains covered by the
accepted MaintenanceIntegrationTest selected in this invocation.

### One immutable JAR and existing-data reverse switch

Actual source JAR and frozen test copy SHA-256:
`65363e1000fe036bf70a8b48fc87e5cc83758db9cf5bef09a4bb50df0f773d9e`.
The same copy ran three sequential child JVMs against one private PostgreSQL:

| Stage | JVM PID | Evidence |
| --- | --- | --- |
| No active profile: default legacy |7088|29Flyway rows initialized; real legacy HTTP books oldOrder1 |
| Modern on existing schema/data |48576|reads oldOrder1 and advances it; creates product/order2, payment, delivery, dispute, admin resolution and review through HTTP |
| No active profile: returned legacy |39032|full order2DTO equals modern DTO; reads resolved case/review; continues oldOrder1 through payment/delivery/completion/review |

Every previous JVM was destroyed and awaited stopped before the next was started.
Windows exit1 from intentional Process.destroy is retained in XML, not called a
startup failure. Each legacy JVM registers exactly one of each of the three old
schedulers; modern registers exactly three jobs on its sole maintenance bean.
All29existing Flyway history rows, including checksums and success flags, remain
equal after both switches. No schema/data rollback or DDL workaround was used.
Modern order2 retains six history rows; oldOrder1 finishes with five. Both stock
counts remain3 and the rating trigger reports two reviews after returned legacy
continues the scenario. Child environments are cleared; only fixture keys and
private loopback S3/SMTP/PostgreSQL are configured. The JAR test uses initial
stored-image metadata fixtures for proof claims; multipart upload/storage writes
are exercised separately by the full HTTP test with mock S3.

### Evidence, source identity and exact six-file scope

Evidence under `build/migration-31/`:

| Path | Meaning |
| --- | --- |
| final-verification.log / .exitcode | Completed command log and saved direct exit0 |
| final-results/*.xml / final-summary.json | Completed153-execution counters,21classes |
| final-source-manifest.json | Pre-invocation hashes of all603 src/main+src/test files |
| final-source-recheck.json | After recovery603current files,0hash mismatches,0additions |
| inventory/*.json / *-beans.txt | Actual handler mappings, comparison and application graphs |
| reversible/{legacy-initial,modern,legacy-return}.log | Actual immutable-JAR startups, schedulers and HTTP traffic |
| reversible/{modern-order,returned-legacy-order}.json | Equal complete DTO snapshots |
| reversible/jar.sha256 | Hash captured before sequence and asserted after every runtime |
| dependencies.json |23/29.3/30 ancestry checks all exit0 |
| deliverables-sha256.json / verification-metadata.json | Final deliverable hashes and summary at implementation handoff |

Source-manifest SHA-256:
`4610e5dbd75d83ebf4cf9aed58b941b7601e7c90419fd30fc5ecdc4447f9d365`.
Source/JAR identity was checked again after quota recovery. No production/test
source was edited after the final invocation; only the two root documents were
finalized. Existing tracked-file diff is empty. Exactly six new deliverables:

1. `src/test/kotlin/com/model_store/modern/shared/integration/ApiRouteInventory31Test.kt`
2. `src/test/kotlin/com/model_store/modern/shared/integration/FullApiIntegration31Test.kt`
3. `src/test/kotlin/com/model_store/modern/shared/integration/AgentMaintenance31Test.kt`
4. `src/test/kotlin/com/model_store/modern/shared/integration/ReversibleBootJar31Test.kt`
5. `MIGRATION_31_ROUTE_INVENTORY.md`
6. `MIGRATION_31_REPORT.md`

The complete log retains cached neighboring reactive-context connection-reset
messages during private PostgreSQL shutdown, including administrator termination
FATAL messages from the fixture database. They occur during teardown; completed
XML has no errors/skips and Gradle exited0. Existing neighboring fixture lifecycle
was not edited to hide this noise. All intermediate failed runs above remain
available and are not passed off as final successes.

### Boundaries carried to32 and stop

- Registration coverage is complete for the actual111business routes. Eight own
  methods plus145neighbor executions do not exhaust every role/body/error/race
  combination for every route, load behavior or all existing repository tests.
  This is implementation31 evidence, not production readiness or acceptance32.
- Default remains legacy; guard23 still rejects unsafe category/product/giveaway
  legacy opt-ins. No new granular flags or @Primary bridges were introduced.
  Mixed legacy/reactive/directSQL writers or multiple deployed processes do not
  gain a common lock protocol or distributed scheduler leader through this work.
- Accepted stricter security remains unchanged: access-token type/issuedBy checks,
  current ACTIVE nonagent ADMIN under owner locks, rejection of synthetic agent
  ADMIN/stale claims, stronger uploader/complete-image-batch ownership and fresh
  association checks. Existing ordinary blocked-participant access and reviewer
  null-name omission remain only where actual accepted contracts retain them.
- Existing differences documented by20/23/27/28/29.1–29.3/30 remain policy inputs
  to32: empty versus typed403 in specific bot paths, safe integrity details and
  CREATE-currency error translation, READ_COMMITTED queue omissions, refusal to
  follow changed associations, historic nonpositive product IDs outside the owner
  contract, protective cleanup retention and bounded non-atomic DB/S3 retries.
  No security behavior was weakened to reproduce an old vulnerability.
- PostgreSQL schema/data here are private migrated fixtures, not a production
  snapshot. SQL fixtures initialize historical agent rows because modern booking
  intentionally rejects agent products; all transitions being tested use actual
  HTTP or actual accepted application commands. No production/Docker/remote action.

Implementation work stops here. HEAD/base remains
`e4c77b6b10945ba196b72acf5542ebfdc6acda25` on
`codex/migration-31-full-api-integration`, with no commit/staging/master edit.
Separate postimplementation context-step, fresh independent audit and local
commit await separate coordinator messages. No postimplementation compaction is
claimed; the earlier tool inventory found no compaction command or usage counter.
No cross-chat message is retried; this final local report is the handoff.

## Separate independent audit, 11 November 2026

Started only after the coordinator's separate postimplementation context request
and explicit subsequent audit instruction. This is a fresh review, not renaming
the implementation153-run or its prior summary. Before mutation, all6deliverable
hashes matched the implementation manifest. Preserved before-deliverables.json,
implementation-source-manifest.json, implementation-metadata.json and original
inventory/reversible artifacts under `build/migration-31/audit/`; the completed
153-run XML in final-results remains untouched.

Reread the six deliverables, concrete Java EmailServiceImpl/AdminController/
AdminAgentController dependencies, main profile/filter/runtime configuration and
guard23. Traced accepted agent commands/audit/access lock, order lifecycle/payment
and media evidence transaction ports, administrator case transition/resolve,
review/rating, scalar order references and product/image maintenance. Rechecked
effective V1.0 order/history/review/FKs, V1.2 sole history/rating triggers,
V1.16 agent audit, V1.21 removed product FKs, V1.23 cases/uploader/evidence and
the unchanged later giveaway schema. No business writer was copied or bypassed.

### Findings and fixes within the same six-file scope

1. Inventory originally classified business by namespace and excluded any path
   starting /modern/check/. A future misplaced handler or extra probe-like business
   path could evade comparison. Now only exact known springdoc/probe/error
   handler+method/path combinations are infrastructure; everything else must be
   an application business route or fail classification. Profile-eligible declared
   controller method signatures are independently scanned and compared against
   the actual handler registry. Modern also checks concrete legacy component
   types, including repository interfaces, to prevent a JDK proxy hiding them.
2. Agent CANCEL replay accepted any RuntimeException. Now it specifically requires
   LifecycleFailure/INVALID_TRANSITION, followed by unchanged stock/history/audit.
   Cleaner-first waiters were discovered generically by blocked SQL. Now the
   actual cancellation worker's PostgreSQL PID is captured before its real
   LifecycleStore lock call, then exact worker/holder and normalized full SELECT
   id FROM participant WHERE id=$1 FOR UPDATE are required before release. Hooks
   call the real owner methods and retain transaction guards.
3. Full HTTP errors previously checked only a nonblank message. Now exact message,
   code/status/timestamp/keys and null details are asserted. Multipart uploads
   assert uploader/tag/status/entity/dimensions and actual mock-S3 PUT of all three
   JPEG variants. An uploaded foreign ORDER proof is rejected during real payment,
   rolling back the attempted transition/history and leaving that image unclaimed;
   legitimate proof activation is subsequently checked explicitly.
4. Reversible JAR originally read modern-created completed data but continued only
   an order initially created by legacy. Now it additionally creates/advances a
   second order in modern and completes/reviews that same order after return to
   legacy. Artifact hash is checked before each startup as well as afterward;
   HTTP client termination is bounded, server ports are checked closed with a
   bounded retry after awaited JVM exit. Teardown errors are suppressed onto an
   original scenario error rather than masking it.
5. Audit initially used the Spring Environment diagnostic default-profile label
   as a mode oracle. Both with an empty active-profile argument and with that
   argument omitted, Boot reports diagnostic label `default`, although the
   application config says legacy and the actual server is reactive. Therefore
   the earlier commentary attributing that label solely to the empty argument was
   an unconfirmed inference, superseded here. Tests now omit active-profile args
   altogether in default stages, require no active profile, check configured
   spring.profiles.default plus actual context/server/graph and scheduler set,
   and export the diagnostic label separately. No explicit legacy profile is
   forced to make the test pass; no production configuration was changed.

Audit intermediate runs (not hidden or counted as green invocations):

- verification-1: directly observed exit1 at compileTestKotlin,0executed tests;
  new assertNull calls were ambiguous between Kotlin/static wildcard imports.
  Qualified only those own assertions and captured a new source manifest.
- verification-2: directly observed exit1,8executions/4classes,2failures. All3HTTP
  and3cross-task methods passed on their final audit sources. Inventory rejected
  diagnostic [default] versus assumed [legacy]. An immediate TCP closure assert
  in JAR teardown failed and masked the original diagnostic-label assertion; fixed
  with bounded port closure and preserving the original exception. A subsequent
  read-only check found no listener on its port51960 or child PID24972.
- verification-3: directly observed exit1,2executions/2classes,2failures. Omitting
  the empty active-profile flag still yielded diagnostic default, disproving the
  earlier causal inference. Bounded teardown now preserved the actual primary
  profile assertion. No business flow/production defect was inferred from it.
- verification-4 is verifying the corrected actual default-mode oracle and the
  two affected classes only. FullApiIntegration31Test/AgentMaintenance31Test
  sources have not changed since their six passing verification-2 executions.

Audit changes affect only four own test source files plus the two own documents.
Original production/resources and all neighboring test sources remain unchanged.
No existing-file scope transfer is requested. Final audit results/manifests follow
only after the current process actually exits; commit remains forbidden.

### Final audit evidence and conclusions

verification-4 completed with directly observed **exit0**, saved exit0 and
**BUILD SUCCESSFUL in2m20s**:2methods/2classes,0failures/errors/skips. These are
the corrected inventory and actual-JAR tests. Their completed XML is copied to
audit/verification-4-results only after process completion. The other six final
audit methods (HTTP3+cross-task3) passed in verification-2 and those two source
files are byte-identical now; six-method-source-identity.json records that check.
Thus all8current own methods have successful results across **two invocations**:
6passed within the earlier8-run that had two unrelated failures, then2passed in
the final targeted exit0 run. This is explicitly **not one green8-run**. The
earlier failure logs and all completed XML remain preserved. No unchanged
neighboring regression was repeated simply to manufacture a larger count.

Both final contexts are real application boots. The unforced default record is:
`active=[]; diagnosticDefaults=[default]; configuredDefault=legacy; context=`
`org.springframework.boot.web.server.reactive.context.AnnotationConfigReactiveWebServerApplicationContext`.
It is therefore the reactive legacy **application mode**, not a claim that Spring
reports a literal legacy default-profile label. Modern uses the servlet context.
The stricter complete-registry classifier, independent declared controller-method
set, full normalized key uniqueness/conditions and actual forbidden bean-type
checks passed. Actual111/111business pairs remain complete with0missing/aliases;
the explicit four docs, three probes and two error handlers remain separate.
No other @Conditional/version-selected business mapping was found in controller
sources; per-profile declarations are matched to actual registry signatures.

Cross-task audit evidence records actual holder28476, product/image waiters30364
and22588 for cancellation-first commit/rollback; ORDER-image conflict waiter30364;
cleaner-first actual cancellation worker22588 (success) and30364 (storage failure).
All seven observed waits matched the exact full participant FOR UPDATE SQL before
release. Existing real JPA/order/product/image calls ran under the observation
hooks. Late rollback produced BOOKED/stock3/history1/audit0, then real retry and
replay checks produced exactly CANCELLED/stock4/history2/audit1. Full foreign
sentinel snapshots stayed unchanged; stale ORDER-image retry preserved proof7.
Storage-failure injection remains a scalar-port failure, not a claim of external
exactly-once behavior, DB/S3 atomicity or mixed legacy-writer protection.

The strengthened reversible test passed on the **same immutable JAR SHA**:
`65363e1000fe036bf70a8b48fc87e5cc83758db9cf5bef09a4bb50df0f773d9e`.
No active-profile argument is supplied to either default child, and the child
environment remains cleared. Successful actual child sequence:

| Stage | PID | Port checked closed after awaited stop | Data |
| --- | --- | --- | --- |
| default reactive legacy |45484|58999|creates oldOrder1 |
| modern MVC |25736|51840|completes disputed order2; creates pending order3 |
| returned default reactive legacy |5848|60873|reads full order2DTO/case/review; completes/reviews both oldOrder1 and modern-created order3 |

Every child completed HTTP readiness/startup/scheduler assertions, then exited
after deliberate stop (Windows exit1 logged). Previous JVM/port closure precedes
the next start. HTTP client termination is asserted within5seconds; private
PostgreSQL and loopback service fixtures closed through their existing use scopes.
No failed startup/teardown is converted into passing evidence. There are still
29unchanged Flyway history rows. Final modern product stock2, old product stock3,
review count3; order histories are6for resolved order2 and5each for orders1/3.

Final audit pre-invocation manifest: **603sources**, current603,0post-run hash
mismatches or additions. SHA-256 of audit/source-before-4.json:
`33b53d6f13ab2d0aa198942e27ee31a20c8ab92984f095d617d762c554273a2d`.
Exactly4own test sources differ from implementation; **599other sources are
unchanged**, including every production file, schema/resource and neighboring
test. source-recheck.json, final-source-delta.json and both run manifests retain
that identity. final-results/153-run and implementation-source-manifest remain
historical implementation evidence, not mislabeled audit results. Final audit
metadata/deliverable hashes are in audit/verification-metadata.json and
audit/deliverables-sha256.json; no source changes followed verification-4.

No new production vulnerability or need for an existing owner/config change was
identified within this bounded integration audit. The fixes strengthen evidence;
they do not alter HTTP business policy, guard23, runtime defaults, schema or
transactions. All previously stated32/load/security-policy/mixed-process/S3
limits remain. The inventory is registration coverage, not all111route×role×body
combinations. Initial historical agent/evidence fixtures remain explicitly
separate from actual HTTP/application transitions; SQL only injects initial data,
auth-revocation fixture changes and the private late-failure trigger.

Scope remains exactly the same six new files. No Java/reactive/Flyway/Gradle/
security/advice/owner/user file was edited, no staging/commit/remote/deployment,
no new chat or subagent. Audit work stops here pending a separate local-commit
instruction. No additional context compaction or percentage is claimed.
