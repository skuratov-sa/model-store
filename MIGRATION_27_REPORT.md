# Migration 27 — administrator order cases

Current stage: separately requested independent audit complete; no commit/push.
The final audit evidence is at the end; implementation-stage results below are
retained as history and are superseded where sources changed.

## Initial inventory, 10 November 2026

Initial stage: inventory before implementation. Implementation and current
verification progress are recorded below; this inventory is retained as history.
Managed worktree `C:/Users/skura/.codex/worktrees/6e53/model-store` was clean,
detached at accepted local master `5add9e56e8bd8f39236286794bcfc8ed02a05599`.
Created `codex/migration-27-admin-cases` directly from that SHA. Initial branch
creation was denied by filesystem permissions; authorized metadata retry passed.
No remote commands, commits, subagents or new chats. No AGENTS.md found in
the worktree, its source subdirectories or checked ancestors.

Dependencies: accepted user cases26 (f59d893), lifecycle25, media12.3 and
CurrentAdminLock16.2 in this base. Read their reports, adjacent reviews28,
actual Java AdminController/OrderCaseService/OrderCaseRepository, case DTOs,
V1.23 schema, lifecycle/public stock ports and current authorization contracts.

Exact planned owned new files:

1. `src/main/kotlin/com/model_store/modern/ordering/case/admin/domain/AdminOrderCase.kt`
2. `src/main/kotlin/com/model_store/modern/ordering/case/admin/application/AdminOrderCasePorts.kt`
3. `src/main/kotlin/com/model_store/modern/ordering/case/admin/application/AdminOrderCases.kt`
4. `src/main/kotlin/com/model_store/modern/ordering/case/admin/infrastructure/JdbcAdminOrderCases.kt`
5. `src/main/kotlin/com/model_store/modern/ordering/case/admin/api/AdminOrderCaseController.kt`
6. `src/main/kotlin/com/model_store/modern/ordering/case/admin/api/AdminOrderCaseErrors.kt`
7. `src/test/kotlin/com/model_store/modern/ordering/case/admin/AdminOrderCaseTest.kt`
8. `src/test/kotlin/com/model_store/modern/ordering/case/admin/AdminOrderCaseHttpTest.kt`
9. `MIGRATION_27_REPORT.md`

## Contract and implementation boundary

All routes use `/admin/actions/order-cases`: GET queue (state=OPEN, limit=50,
offset=0), GET /{caseId}, PUT /{caseId}/telegram-url (url query), POST
/{caseId}/resolve (outcome/comment query). Mutations return numeric case ID.
Queue sorts created_at DESC,id DESC, accepts limit1..100 and nonnegative offset.
Detail retains the complete nested case and order/image snapshot from26.
Telegram accepts max2048, HTTPS (case-insensitive scheme), exactly lowercase
t.me/telegram.me host, no userinfo, nonblank path other than /. Stores raw URL.
No new URL restrictions on query/fragment/port. Comment uses Java isBlank
semantics; resolution stores the original, untrimmed comment.

DISPUTE: BUYER -> FAILED; SELLER -> COMPLETED only if previous ON_THE_WAY;
both require current DISPUTED. PAYMENT_APPEAL: either BUYER/SELLER -> CANCELLED
from DISPUTED without returning stock again. CANCELLATION_REQUEST: REJECTED
only closes the case, CANCELLED requires current=previous and returns limited
stock via existing catalog ProductReservation.returnLimitedStock. Ordinary
PAY/CANCEL, proof activation and stock SQL will not be copied. Typed guarded
resolution SQL stays in new ordering admin files, history solely by SQL trigger.

All application operations check active nonagent current ADMIN using owner lock
in the same writable JPA transaction. Prebinding rejects bot ACCESS/agent JWT.
Mutations discover case/order, lock distinct admin+buyer+seller ASC, then
lock/recheck order parties, then case, then catalog stock if necessary. Changed
associations refuse without following locks. No participant locks after order.
Existing case26 read DTO/store and lifecycle transaction/locks may be reused
through their public contracts. No writes to participant/catalog/media/seller/
reputation tables. No owner extensions, shared/config edits or changes to
Java/reactive/Flyway/docker-compose.yml/plan_2.md/MIGRATION_PLAN.md required.

## Verification plan

Use JDK `C:/Users/skura/.jdks/temurin-21.0.11`, Gradle cache
`C:/Users/skura/.gradle`, offline and test RSA keys only. Private embedded
PostgreSQL plus mocked S3/SMTP. Test HTTP legacy parity/outcomes/DTO/errors,
current/stale roles, invalid IDs/foreign image exclusion, repeated/concurrent
resolve, opening26/payment25 interleavings, rollback including stock/history,
and participant lock order/association recheck without deadlock. Preserve
completed logs/XML and exact failures. Separate coordinator-requested audit
and commit stages have not begun.

No real context-compaction command or context-usage counter is exposed here;
this report is not a context compaction.

## Implemented boundary and verification progress

All nine planned files now exist. Only the six new production files, two own
test files and this report were authored. Existing26 public read model/store
and API detail mapper are reused, with API-owned queue DTO. Admin case SQL
accepts typed CaseResolutionTarget (FAILED/COMPLETED/CANCELLED), not ordinary
payment actions. SQL parameters are bound. Trigger remains the only history
writer. Catalog return is called exactly after successful guarded cancellation
request transition; appeals never return stock. No owner extension was needed.

Verified accepted26 f59d893,28 161160b,25 e24ac524,12.3 44e0c7e and16.2
8a989e5 are ancestors (all git merge-base exit0). Branch HEAD stays the supplied
5add9e56 base. No tracked existing file is modified.

Execution record (not hiding launch or test failures):

- First attempted launch did not start Gradle: build/ did not yet exist for log
  redirection. The shell returned0 despite redirection failure; this is NOT a
  passing build. Created the local output directory before the next command.
- `migration-27-http-1.log`: exit1 before Gradle execution, existing global
  wrapper cache lock was write-protected. Authorized local cache retry followed.
- `migration-27-http-2.log`: exit1, BUILD FAILED in2m11s,25 tests/1failure.
  Only failure was an overly strong shared legacy/modern assertion expecting a
  signed agent_access/ADMIN token to be rejected before missing query binding.
  Actual legacy security only excludes refresh, so agent type reaches binding.
  Modern already rejected it before binding. Preserved completed XML under
  `build/migration-27-http-2-results/` and counters in corresponding summary JSON.
- Corrected the test to assert this legacy distinction explicitly (400 on
  missing resolve parameters and200 on read) and modern403/ACCESS_DENIED before
  binding across all four malformed routes. Fixed a test request to pass proof
  as the actual query parameter; added deterministic successful-payment-first
  and admin-first tests rather than relying on the random race picking both.
- `migration-27-http-3.log`: directly observed exit0, BUILD SUCCESSFUL in1m13s,
  **30 tests/3classes,0failures/errors/skips**: legacy11,modern17,domain2.
  Preserved completed XML `build/migration-27-http-3-results/`, counters
  `build/migration-27-http-3-summary.json`, tested source manifest
  `build/migration-27-http-3-source.json`.
- Subsequently strengthened the production resolution target type and corrected
  catalog overflow mapping to the actual legacy500 INTERNAL_ERROR, added both
  HTTP overflow rollback tests and URL rollback assertions. Thus the30-test run
  is historical, NOT the final verification of these changed sources.

Final regression launched with bootJar and RUN_BOOT_JAR_SMOKE=true. Frozen
pre-build src/main+src/test manifest: `build/migration-27-regression-source.json`.
Log `build/migration-27-regression.log`; exit file only after process completion.
It selects own admin tests, accepted26 tests, lifecycle HTTP, order creation,
stock reservation, ORDER evidence, CurrentAdmin authorization, reviews, shared
MVC security and actual dual-mode JAR smoke. XML from a running process is not
read or counted. The inherited six accepted26 contract tests run in each own
HTTP class deliberately; they are regression coverage, not twelve newly authored
admin tests.

Security difference for32: modern requires current ACTIVE/nonagent ADMIN for
queue/details too, not only a JWT role, and holds its owner lock through each
operation. Legacy queue/details depend on the JWT scope and mutations recheck
only role (not active status). Modern rejects agent_access even with signed ADMIN
claims; legacy accepts that synthetic claim combination as described above.
Ordinary issued agent tokens have USER role, so this fixture does not claim the
normal issuer currently grants ADMIN agent tokens. No legacy change was made.

No separate coordinator-requested independent audit or local commit has begun.

Exact final verification command (PowerShell, assigned JDK/cache/test keys):

```
gradlew.bat --offline --no-daemon bootJar test
  --tests '*AdminOrderCase*Test'
  --tests '*UserOrderCase*Test'
  --tests 'com.model_store.modern.ordering.lifecycle.*HttpTest'
  --tests 'com.model_store.modern.ordering.create.*Test'
  --tests 'com.model_store.modern.catalog.product.reservation.ProductReservationTest'
  --tests 'com.model_store.modern.media.image.command.OrderImageEvidence*Test'
  --tests 'com.model_store.modern.catalog.product.command.ProductStatusAuthorizationTest'
  --tests 'com.model_store.modern.reputation.*Test'
  --tests 'com.model_store.modern.shared.config.ModernMvcSecurityTest'
  --tests 'com.model_store.modern.shared.api.BootJarDualModeSmokeTest'
```

## Final implementation-stage verification and handoff, 10 November 2026

Final process completed with directly observed **exit0, BUILD SUCCESSFUL in8m2s**.
**162 executed tests in20classes,0failures/errors/skips** in one invocation.
No source changed during or after that invocation. This supersedes the earlier
30-test result. Own classes: legacy12,modern18,domain2 =32 executed checks,
including12 inherited26 HTTP regressions (20 newly authored checks). Accepted26
also ran in its own three classes (22); this deliberate repeated base coverage
means162 is an execution count, not162 distinct authored scenarios.

| Selected area | Executed tests |
| --- | ---: |
| Own administrator classes (including inherited26) | 32 |
| Accepted26 own classes | 22 |
| Lifecycle25 HTTP | 30 |
| Order creation24 | 16 |
| Catalog stock reservation | 10 |
| ORDER media evidence | 14 |
| Current ADMIN16.2 authorization | 10 |
| Reviews28 | 19 |
| Shared MVC security | 8 |
| Actual dual-mode JAR smoke | 1 |

Evidence retained locally:

- `build/migration-27-regression.log` / `.exit-code`.
- Completed XML copied ONLY after process exit to
  `build/migration-27-regression-results/`; per-class counters
  `build/migration-27-regression-summary.json`.
- Source manifest:570 files,0mismatches on post-run rehash; SHA256
  `89ab3e9cb2ad23bc429745eecc585bda9cf78d9df4df1e863f55fb470a0f3083`.
- Actual JAR SHA256
  `738282708df86290a2fe3665f441d6b99f2f60a7f5b988f7c92ce5d5524e22a4`;
  the smoke's printed frozen artifact hash matches the final JAR exactly.
- Both child application logs preserved as
  `build/migration-27-bootjar-legacy.log` and `build/migration-27-bootjar-modern.log`.
  Smoke verified isolated S3/SMTP startup, security/OpenAPI and existing negative
  JSON in both profiles, with normal child shutdown. Actual case operations are
  verified through the full-server HTTP classes, not additional JAR-specific case
  requests. Test RSA keys only; no production/remote service or Docker used.
- Aggregate counters/hashes: `build/migration-27-final-hashes.json`.
- Two read-only thread diagnostics (`build/migration-27-progress-32452.txt`,
  `build/migration-27-progress-2.txt`) showed progress from embedded PostgreSQL
  initdb to actual JAR startup. No duplicate test process, termination, swallowed
  deadlock, or retry of a failed business transaction. Shutdown-time R2DBC reset
  messages from inherited test resources were logged; they caused no test errors.

Verified behavior: exact legacy routes/queries/ID responses/full normalized DTOs,
URL scheme/host/length/userinfo and untrimmed decision comments, per-kind outcome
matrix, wrong statuses/IDs/roles/foreign image exclusion, current and stale ADMIN,
bot/agent denial before binding, repeated and simultaneous decisions. Deterministic
participant waiters establish role revocation, changed parties, reversed admin/
party IDs without40P01, both payment-first and resolution-first orderings, and
opening/payment competitors. Order/history/stock/decision/image links commit once
or roll back together, including outer failure, late CHECK failure and stock
overflow. Direct mutation ports reject no-tx/read-only/JDBC-only callers; accepted
owner tests cover their additional foreign-EMF/manager requirements.

Limits: targeted regression, not the entire repository suite, load testing or
mixed legacy/modern writer rollout certification. Legacy's weaker authorization
and synthetic ADMIN agent-type acceptance remain explicit differences for32.
No task29.3/30 work was taken. All six production files/two tests/report remain
new and uncommitted on `codex/migration-27-admin-cases` at supplied base5add9e56.
No shared/config, accepted owner, Java/reactive, Flyway, user compose/plan or
MIGRATION_PLAN edit; no stage/commit/push/fetch/pull/publication/deployment.

Implementation stage is complete and stopped. A real context-compaction command
and usage counter remain unavailable. The separately requested new independent
audit and local commit await the coordinator's later separate messages; this
implementation report is not that audit and not a context compaction.

Final file check: exactly9new owned files; all pass the untracked-file whitespace
check. Git status contains only this report and the two new admin source/test
directories. Removed only locally generated `.kotlin` compiler output after
verifying its resolved path is the workspace's `.kotlin` directory. A first
whitespace wrapper incorrectly treated git no-index's ordinary new-file exit1
as a failure; rerunning with autocrlf disabled produced no whitespace diagnostics
for any of the9files. No code was changed during this check.

## Separate coordinator-requested context step, 10 November 2026

After the implementation report and162/20final run, the coordinator separately
requested actual compaction or explicit unavailability, with audit/commit still
forbidden until later separate messages. The currently exposed tools contain no
actual context-compaction command and no context-usage counter. Therefore actual
compaction cannot be performed or a usage percentage measured in this chat.
No summary is being represented as compaction. Source manifests, completed XML,
directly observed exit0, exact JAR SHA and the nine-file inventory remain retained
at the paths above. No new independent audit or commit was started.

## Separate independent audit — fresh review, 10 November 2026

The coordinator subsequently requested this audit in a separate message, after
the separate context-command unavailability confirmation. This is a new review,
not acceptance inferred from the previous162/20green run. No commit is allowed.
Reviewed all9owned files again against actual Java AdminController,
OrderCaseService/OrderCaseRepository/OrderRepository, ProductServiceImpl/
ProductRepository, DTO/enums, ImageServiceImpl/ImageRepository and effective SQL
V1.0/V1.2/V1.21/V1.22/V1.23. Rechecked plan architecture/scope/security rules,
actual modern JWT decoder/route guard and legacy WebSecurity/AgentAccount filter,
CurrentAdminLock/JpaLifecycleTransaction, accepted25/26 application/SQL and catalog
reservation owner implementation. No external documentation assumptions were
substituted for these sources.

Before changes, saved all9owned files and SHA256 manifest under
`build/migration-27-audit-before/`. Added two new shared legacy/modern repro
tests BEFORE touching production. Directly observed
`build/migration-27-audit-repro.log` exit1, BUILD FAILED in1m:4tests,2failures.
Both legacy tests passed; both modern tests failed. Completed XML retained in
`build/migration-27-audit-repro-results/`. This is a defect reproduction, not
passing verification. No full all-source repro manifest was frozen; the copied
six production files are the exact unchanged implementation-stage source.

### Findings and fixes

1. **SQL length failure had the wrong JSON envelope.** Effective `order.comment`
   and history.comment are varchar(500), case.resolution_comment is TEXT. A501-
   character decision with an order transition returns legacy500 INTERNAL_ERROR
   in the five-field envelope. Modern returned generic Boot error JSON instead.
   Own controller advice now translates SQLSTATE22001/22003 to the actual legacy
   safe500envelope, while preserving reference/constraint error shapes for other
   integrity failures and safe500for other persistence failures. No silent
   truncation or new500-character API limit: REJECTED cancellation still accepts
   long TEXT decisions. A late PostgreSQL P0001 exception exercises the generic
   persistence mapping separately from integrity/length failure.

2. **Persisted nonpositive order quantities diverged.** Effective SQL has NOT NULL
   count but no positive CHECK. Legacy incrementCountIfLimited explicitly skips
   amount<=0. Modern always called the positive-quantity catalog port and returned
   500without cancelling. Admin application now skips stock return for those
   historical quantities; guarded cancellation/history/case still complete.
   No PURCHASABLE/unlimited/overflow logic or stock SQL was copied into ordering.

3. **A point detail loaded every case/evidence of its order.** Reusing26.cases
   made a single administrative detail perform unbounded extra sibling-case
   reads. Own adapter detail now selects only requested case, reuses26's scalar
   order read and reads that case's evidence plus legacy ACTIVE ORDER IDs. Queue
   selects only required fields rather than also loading resolution TEXT. DTO,
   explicit nulls, ordering and access policy are preserved. Deleted case evidence
   IDs remain present (as legacy); TEMPORARY/deleted/wrong-tag images are excluded
   from the separate actual orderImages collection.

4. **Concurrency/rollback assertions could pass for the wrong cause.** Replaced
   generic waiter counts with exact normalized SQL multiset, backend PID and
   blocker diagnostics tied to the root holder; the reverse revoker test passes
   a separately captured expected backend PID. Replaced broad RuntimeException
   rollback acceptance with exact23514/named CHECK or the explicit outer-failure
   exception after observing real uncommitted order/stock/history/case effects.
   Losing payments/openings require precise business codes/messages. Added all
   four operations x role/status/is_agent x both ordering directions, actual
   foreign EMF and wrong DataSource manager guards, and exhaustive domain
   outcome/previous/current-state error precedence (including nullable previous).
   Added order_case reassociation refusal without following the new order's
   participants, and deterministic opening-first vs resolution-first schedules.

Production edits in this audit: AdminOrderCases.kt, AdminOrderCasePorts.kt,
JdbcAdminOrderCases.kt and AdminOrderCaseErrors.kt. Both own test files and this
report changed. Domain and controller production files are unchanged. Scope is
still exactly the original9new files; no existing owner/shared/config/Java/
Flyway/compose/plan file was edited.

### Fresh verification progress

`build/migration-27-audit-own.log`: directly observed exit0, BUILD SUCCESSFUL
in1m16s, **37tests/3classes,0failures/errors/skips** (legacy14,modern21,domain2).
Completed XML `build/migration-27-audit-own-results/`; counters
`build/migration-27-audit-own-summary.json`. This verifies the four production
fixes, exact error/SQL assertions, complete role/status/agent direction matrix,
and foreign-EMF/DataSource rejection. No XML was parsed before process completion.

After that run added two own association/opening-order tests, strengthened image
filtering coverage and the generic late-SQL-failure test/handler. Therefore37is
an intermediate result, not final current-source verification. The new frozen
all-source manifest is `build/migration-27-audit-final-source.json`. Full selected
regression uses exactly the earlier final command with RUN_BOOT_JAR_SMOKE=true,
now logging to `build/migration-27-audit-final.log`; exit file is written only on
completion. The old162/20artifact is historical and not claimed for these sources.

### Static conclusions and remaining boundaries for32

- Every HTTP entry point uses verified Actor. The shared decoder validates RSA,
  expiry/type/claims; @ModelAttribute rejects non-ACCESS/non-ADMIN claims and
  bot ACCESS before binding. No JWT reparsing, user-supplied caller ID or auth
  overload. Read and write methods check current ACTIVE/nonagent ADMIN via owner
  lock in the same caller writable JPA transaction, not a managed stale entity.
  No case/order/private participant data is read before durable authorization on
  read routes. Mutations do scalar discovery only before sorted lock acquisition.
- Complete mutation participant set is caller+buyer+seller DISTINCT ASC. When
  caller's ID is reached, owner CurrentAdminLock checks its locked tuple. Then
  order locks/rechecks parties, then case locks/rechecks order association/open
  state. A changed association refuses; no new participant/order is followed.
  Stock is locked/written only through catalog after the guarded ordering update;
  admin never activates images. All own mutation/lock adapters directly enforce
  the writable joined application EMF/DataSource transaction guard before SQL.
- Resolution matrix matches actual legacy: seller dispute only previous
  ON_THE_WAY; buyer FAILED; appeal BUYER/SELLER both CANCELLED without stock
  return; cancellation REJECTED ignores current order status and only closes
  case; CANCELLED requires current=previous and catalog return only once.
  No ordinary PAY/CANCEL/proof/stock implementation copied. Payment25 and opening26
  retain their public owner operations. Trigger V1.2 is the only history writer,
  emits only for changed status, uses transaction time; no new timestamp tie rule.
- All SQL values are bound. Own writes are only order/order_case; no participant,
  catalog, media, seller or reputation write. Details contain exactly legacy case/
  order/image IDs, no mail/password/private identity. ID guessing cannot bypass
  current admin checks. Normal ADMIN intentionally sees any case, not only its
  own participant orders. Missing/foreign-party IDs on user routes retain26 rules.
- Deliberate security differences remain: legacy reads trust JWT ADMIN scope;
  mutations check only role. Modern rejects stale role, inactive status and agents
  with403, holds its current lock to transaction end, rejects signed agent type
  even with ADMIN claim. Legacy's synthetic signed ADMIN/agent type acceptance
  is tested, not a claim its normal issuer produces such a token.
- Legacy comments/URL validation precedence remains: well-formed token with
  invalid content can get400before a current-role check; malformed path/query
  binding can precede current-role checking. Bot/type denials precede binding.
  This yields no write/private-data access. Snapshot readers retain READ_COMMITTED
  legacy behavior rather than a new repeatable-read guarantee. Error `dbMessage`
  for authorized constraint/reference failures remains legacy-compatible and may
  contain PostgreSQL diagnostics; length/generic failures have no such details.
- Existing catalog owner contract requires positive product ID. Historical
  hand-written/corrupt order snapshots with nonpositive product IDs are outside
  that accepted port and may be refused more strictly than legacy (500/rollback).
  No shared owner contract was weakened and no foreign file was edited. Ordinary
  legacy/modern creation validates positive quantity/product existence; the
  effective schema's nonpositive quantity case is now explicitly compatible.
- No certification of concurrent legacy+modern rollout, entire-suite coverage,
  load testing or production behavior is claimed. Shared integration31/32 and
  agent29.3/schedulers30 remain outside this scope.

### Additional audit finding: Telegram optimistic version contract

Fresh reread of Java changeTelegramUrl and repository SQL exposed one more
concurrency deviation. Legacy checks the case's originally read OPEN state and
CAS version; a competing URL update/resolution after that observation must fail
with400 ORDER_CASE_INVALID_TRANSITION. Modern had discarded the observed version
after acquiring locks, allowing overwrite (or a409replay instead of the CAS loss).

Preserved the finished broad run BEFORE changing that code:
`build/migration-27-audit-final.log`, directly observed exit0, BUILD SUCCESSFUL
in7m58s, **171executed tests/20classes,0failures/errors/skips**. Archived completed
XML/counters in `build/migration-27-audit-final-results/` and
`build/migration-27-audit-final-summary.json`; hashes in
`build/migration-27-audit-final-hashes.json`. Source rehash had0mismatches,
manifest SHA299938cb80ce73fb3ffc40ea9473fc5d565903a691ed245a9082635b710324ed.
JAR SHA dac8c09d24dbad999b689060d568d7de50dba645b5b76a3410a073d2224e1c63.
Both actual child logs retained as `build/migration-27-audit-bootjar-legacy.log`
and `build/migration-27-audit-bootjar-modern.log`. A read-only diagnostic
`build/migration-27-audit-progress-34944.txt` showed progress into the dual-mode
JAR startup, without interrupting or duplicating the test worker.

Added a deterministic observed-version regression BEFORE the production fix:
hold buyer, prove the URL request's exact participant SQL wait after its scalar
case observation, perform a real competing owner URL update/resolution, commit,
then require the losing original URL400and the winner's version/value preserved.
`build/migration-27-audit-url-repro.log`: directly observed exit1, BUILD FAILED
in1m14s,3tests/1failure. The actual loser returned200/case ID instead of400.
Two new legacy/modern human-binding tests passed (exact required query/enum/path
error envelopes). Completed XML preserved in corresponding `-results/` folder.

Fixed only AdminOrderCases.kt: Telegram lock path retains observed.requireOpen
and compares the observed version to the locked version/state after current
admin+all-party/order/case locking and reassociation checks. Resolve keeps its
existing locked-current-case rule. No new lock, owner file or transaction.
Already closed on initial observation remains409; version changed after OPEN
observation becomes400. Added regression covers both competing URL and resolution.

Final replacement verification selects own admin tests, lifecycle HTTP and actual
dual-mode JAR smoke with a rebuilt artifact. Exact source frozen in
`build/migration-27-audit-url-final-source.json`; log/exit prefix
`build/migration-27-audit-url-final`. This is a separate invocation from171/20.
Untouched broader dependency classes will retain the preceding broad evidence;
no single aggregate invocation or old artifact is being claimed for changed
sources. Both changed source paths relative to that broad run are owned:
AdminOrderCases.kt and AdminOrderCaseHttpTest.kt.

Further explicit32 boundary: modern refuses changed order parties/case-order
associations instead of following a new lock set behind the lock frontier. This
is intentional safety behavior versus the old unlocked association observation.
Other URL/comment/DTO/outcome contracts remain as documented, including the
restored Telegram CAS conflict rather than last-writer-wins behavior.

### Final audit verification and stop, 10 November 2026

Final replacement process: directly observed **exit0, BUILD SUCCESSFUL in3m18s**.
**75executed tests in6classes,0failures/errors/skips** in this invocation:

| Current final class | Tests |
| --- | ---: |
| AdminOrderCaseLegacyHttpTest | 16 |
| AdminOrderCaseModernHttpTest | 26 |
| AdminOrderCaseTest | 2 |
| OrderLifecycleLegacyHttpTest | 9 |
| OrderLifecycleModernHttpTest | 21 |
| BootJarDualModeSmokeTest | 1 |

All44own-class executions passed, including12inherited26 regressions and32own
method/profile checks. All outcome/status error-precedence combinations, URL
CAS versus update/resolution, replay, current ADMIN revocations in both orders,
exact observed SQL/backend waits, changed associations, direct transaction guards,
late failures and complete rollback are current passing assertions. Both human
binding contracts and legacy/modern data/error comparisons pass. No remaining
identified own-scope actionable finding is left unfixed for ordinary supported
orders; the explicitly documented historical nonpositive product-ID owner-port
boundary and integration/load/rollout limits remain for32.

Completed final XML was copied only AFTER process termination to
`build/migration-27-audit-url-final-results/`, with per-class counters in
`build/migration-27-audit-url-final-summary.json`. Final log/observed exit:
`build/migration-27-audit-url-final.log` / `.exit-code`. Current-source manifest
`build/migration-27-audit-url-final-source.json` has **570files,0mismatches** after
the process. SHA256:
`2c4231ddee11631a865bbb09a348bf01f51298008cc2322a32d0f32989c6a37c`.

The new frozen JAR ran in both legacy and modern against isolated PostgreSQL/
loopback S3/SMTP and shut down normally. Its printed smoke hash matches the
current artifact exactly:
`f2ff4dbfa874ace00524cab9a07c4794337fa09b13a4aa4cd29b3dc868526b51`.
Both child logs retained as `build/migration-27-audit-url-bootjar-legacy.log`
and `build/migration-27-audit-url-bootjar-modern.log`. No production service or
remote operation was used. Shutdown warnings from inherited PostgreSQL resource
ordering are retained in the logs, not hidden or counted as test failures.

Current combined evidence replaces6broad classes with the6final classes above,
retains14unchanged dependency classes (99tests) from the171/20broad run, and
contains **174executions/20classes,0failures/errors/skips**. This is explicitly
TWO passing invocations, not a single174-test invocation. Of these174,12are the
deliberately duplicated inherited user-case checks; XML-based canonicalization
by declaring scenario/profile confirms **162unique method/profile checks**.
The171broad run and75final run overlap in72checks, with3new checks in the final
run (human binding in both profiles plus modern Telegram race). Intermediate
37-test validation and failing4/3-test repros are not added to unique coverage.
Counters: `build/migration-27-audit-combined-summary.json` and
`build/migration-27-audit-unique-summary.json`.

Source identity: only AdminOrderCases.kt and AdminOrderCaseHttpTest.kt differ
from the completed broad run; the entire final manifest otherwise matches.
All **562source/resource files outside the8owned Kotlin files** still match the
implementation-stage manifest. Domain and API controller also match the audit-
before snapshot. No source changed after the successful final invocation.
Final aggregate/hashes: `build/migration-27-audit-url-final-hashes.json`.
Exact audit delta: `build/migration-27-audit-delta.json`. Nine-file final hash
inventory, including this completed report: `build/migration-27-audit-owned-final.json`.

The exact9file scope remains the paths enumerated at the start:

| Owned file | Audit result |
| --- | --- |
| domain/AdminOrderCase.kt | Reviewed unchanged; invariants/URI/Unicode match Java |
| application/AdminOrderCasePorts.kt | Added selected-case read contract |
| application/AdminOrderCases.kt | Nonpositive quantity compatibility, bounded detail, observed URL CAS |
| infrastructure/JdbcAdminOrderCases.kt | Bounded case detail/queue reads; direct mutation JPA guards retained |
| api/AdminOrderCaseController.kt | Reviewed unchanged; verified principal/prebinding/DTO/routes |
| api/AdminOrderCaseErrors.kt | Safe legacy SQL/internal envelopes with preserved integrity details |
| test/AdminOrderCaseTest.kt | Exhaustive exact outcome/status/Unicode/URL error assertions |
| test/AdminOrderCaseHttpTest.kt | Fresh repros, exact PID/SQL/errors/guards/rollback/race tests |
| MIGRATION_27_REPORT.md | Separate audit findings, evidence, differences and limitations |

Final ownership/whitespace/source checks leave only these9new files, no tracked
existing edits or staged files. Removed only own generated `.kotlin` output after
checking its resolved path stays exactly inside this workspace. HEAD remains
`5add9e56e8bd8f39236286794bcfc8ed02a05599`, branch
`codex/migration-27-admin-cases`. No subagent, new chat, commit, push/fetch/pull,
publication, deployment, master/plan/shared/owner/Java/Flyway/user-compose change.

The separate audit is complete and stopped. Actual context compaction and its
usage counter remain unavailable. Local commit awaits the coordinator's later
separate instruction; it has not been performed.
