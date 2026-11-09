# Migration 26 — buyer/seller order cases

Current stage: separate security/plan audit complete. No commit or push.
The initial proposal below is retained as history and was superseded
by the coordinator's explicit in-scope contract approval, recorded at the end.

## Initial inventory, 09.11.2026

Status: dependency-contract decision required before implementation. This is an
initial inventory and proposal, not a completion or verification report.

- Worktree: `C:/Users/skura/.codex/worktrees/5db2/model-store`.
- Initial HEAD and accepted local base: `68247f3df439923a431300e7c39c175357e8d225`.
- Own branch: `codex/migration-26-user-cases`, created explicitly from that SHA.
  Initial worktree was clean and detached. Creating the branch required access
  to Git's worktree metadata outside this directory; the authorized retry passed.
- Dependencies: accepted 25 (`e24ac52424bf3be801849b0db2a3cd87ac52b647`),
  12, 12.2 and 12.3, inherited in the supplied base. Read the lifecycle 25 report,
  ORDER_EVIDENCE_12_2_REPORT and MIGRATION_12_3_REPORT and the common plan.
- Only file created at this stage: `MIGRATION_26_REPORT.md`.
- No commit, push, remote update, master mutation, production connection,
  Gradle execution or test execution. No subagents were started.

## Exact proposed implementation file list

Pending the owner-port decision, implementation is planned in these new files:

1. `src/main/kotlin/com/model_store/modern/ordering/case/user/domain/UserOrderCase.kt`
2. `src/main/kotlin/com/model_store/modern/ordering/case/user/application/UserOrderCases.kt`
3. `src/main/kotlin/com/model_store/modern/ordering/case/user/application/UserOrderCasePorts.kt`
4. `src/main/kotlin/com/model_store/modern/ordering/case/user/infrastructure/JdbcUserOrderCases.kt`
5. `src/main/kotlin/com/model_store/modern/ordering/case/user/api/UserOrderCaseController.kt`
6. `src/main/kotlin/com/model_store/modern/ordering/case/user/api/UserOrderCaseResponses.kt`
7. `src/main/kotlin/com/model_store/modern/ordering/case/user/api/UserOrderCaseErrors.kt`
8. `src/test/kotlin/com/model_store/modern/ordering/case/user/UserOrderCaseTest.kt`
9. `src/test/kotlin/com/model_store/modern/ordering/case/user/UserOrderCaseHttpTest.kt`
10. `MIGRATION_26_REPORT.md`

The list is a proposal, not a claim that these files exist. Java/reactive,
Flyway, Gradle/config/shared, existing owner files, other migration packages,
docker-compose.yml, plan_2.md and MIGRATION_PLAN.md remain unchanged.

## Actual legacy inventory and contracts

Read actual OrderController, OrderCaseService, OrderCaseRepository,
OrderRepository, ImageRepository, OrderCase, OrderCaseDetail and V1.23 SQL;
compared the accepted LifecycleStore/OrderPaymentCases/OrderLifecycle,
LifecycleOrder/PaymentCase, JdbcLifecycleStore, OrderViews and ImageReadPort.

All routes have the OrderController prefix `/order`:

| Method/path | Input | Success |
| --- | --- | --- |
| POST `/order/orders/{orderId}/DISPUTED` | Required query comment, optional imageIds | Case ID |
| POST `/order/{orderId}/cancellation-request` | Required query comment | Case ID |
| POST `/order/{orderId}/payment-appeal` | Required query comment, optional imageIds | Case ID |
| GET `/order/{orderId}/cases` | No body | Array of OrderCaseDetail, empty array when no cases |

The doubled `order/orders` in the dispute path is present in the actual Java
mapping and must be preserved. Images are query parameters, not a JSON body.
Every command requires a non-null/nonblank comment before repository work.
The case stores Java-trimmed comment; the order transition stores the original
comment. Cancellation request does not change order status or history.

Dispute: either party; no existing OPEN case; status in
AWAITING_PREPAYMENT_APPROVAL, AWAITING_PAYMENT, ASSEMBLING, ON_THE_WAY;
non-null payment proof; guarded transition to DISPUTED and new OPEN DISPUTE.
The case retains the status before the transition.

Cancellation request: either party; no OPEN case; no payment proof; status
AWAITING_PREPAYMENT or AWAITING_PAYMENT; additionally prepayment must be null/0
unless status is AWAITING_PREPAYMENT. Creates OPEN CANCELLATION_REQUEST.

Payment appeal: buyer only; rejects any previous PAYMENT_APPEAL, including a
resolved one, before checking CANCELLED status and existing OPEN case.
Transitions CANCELLED to DISPUTED and creates OPEN PAYMENT_APPEAL.

Case details contain the complete nested case (including nullable fields and
version), current orderStatus, buyerId, sellerId, nullable productId, count,
totalPrice, prepaymentAmount, snapshot currency, paymentProofImageId,
orderImages and caseImages. Cases sort by created_at DESC, id DESC; case image
IDs sort ascending; actual order images sort by created_at in the legacy
repository. Images are ID arrays, not image objects.

Legacy service errors: blank comment is IllegalArgumentException with
`Укажите причину обращения`; missing/foreign order and invalid evidence use
IllegalArgumentException with
`Операция недоступна для текущего состояния заказа или обращения`;
invalid state is ORDER_CASE_INVALID_TRANSITION (400); OPEN conflict is
ORDER_CASE_ALREADY_OPEN (409); repeated appeal is PAYMENT_APPEAL_ALREADY_EXISTS
(409). Exact HTTP envelopes still require actual signed HTTP verification;
they have not been asserted in this stage.

Null/empty evidence is a no-op. Duplicate IDs reject without deduplication.
All other evidence must be TEMPORARY ORDER, uploaded by the caller and either
unassigned or assigned to this order. Activation must use the accepted media
OrderImageEvidence port in the same writable application JPA transaction.
Only ordering inserts case/image relations. No media/catalog SQL writes.

V1.23 has the partial unique index uq_open_order_case on order_id WHERE
state='OPEN'; order_case_image has unique image_id. Existing order history
trigger is the only history writer. The accepted payment use case dismisses
cancellation requests and handles subsequent payment in eligible DISPUTE;
26 must reuse it, not implement a second payment path.

## Required owner-port decision (proposal to coordinator)

LifecycleStore already provides observeOrder, ascending participant locks,
lockOrder and writable transaction verification. Its only status-write method
requires an ordinary OrderAction, with action-dependent proof/delivery writes.
No action represents opening a dispute or a payment appeal. Supplying COMPLETE
or CANCEL merely to obtain a plain SQL update would misrepresent the contract.
Duplicating order-transition SQL in 26 would bypass the instructed owner port.

Proposed minimal extension, to be supplied/authorized by the coordinator in
the lifecycle owner scope before implementation of the dependent commands:

`LifecycleStore.markDisputed(orderId: Long, expectedStatus: String, comment: String): Boolean`

Contract: require the existing writable application JPA transaction, caller
already holds participant IDs ASC then the order lock and has validated party,
case kind and current state. Guard order ID and expected status, set only
status=DISPUTED and comment, leave proof/delivery/stock untouched, rely on the
existing trigger for history, return true iff exactly one row changed. No
new transaction, retry, exception swallowing or unrelated table writes.
Only the legacy eligible dispute source states and CANCELLED need support.

Likely owner files requiring coordinator work:
`ordering/lifecycle/application/OrderLifecycle.kt` (interface declaration) and
`ordering/lifecycle/infrastructure/JdbcLifecycleStore.kt` (implementation),
plus owner tests. Neither file has been changed by 26.

The existing snapshot lacks totalPrice and productCurrency needed for case
details. Proposed narrow read owner contract, rather than exporting a JPA
entity: `caseOrder(orderId: Long): CaseOrderSnapshot?` containing only orderId,
sellerId, customerId, status, productId, quantity, totalPrice, prepaymentAmount,
productCurrency and paymentProofImageId. Coordinator should confirm whether
26 may own this ordering SQL read in its new adapter or wants the snapshot
supplied by lifecycle. Existing lifecycle list views require unrelated user,
product and transfer enrichment and are not a precise replacement.

No foreign file is changed pending that decision. Dependent implementation and
verification cannot be reported complete before the port is available.

## Required verification after the decision

Use JDK C:/Users/skura/.jdks/temurin-21.0.11, cache C:/Users/skura/.gradle,
gradlew.bat --offline --no-daemon and only test RSA paths in JAVA_TOOL_OPTIONS.
Use isolated embedded PostgreSQL and mock/local S3/SMTP. Verify actual HTTP/JWT,
exact JSON/error/null/empty contracts, both parties, foreign orders/images,
duplicate/invalid evidence, rollback of case/status/history/evidence, concurrent
second opening and payment/cancellation/expiry races. Lock participant IDs ASC,
then order/product, then images; never move back or catch a deadlock to continue
the transaction. Reuse accepted lifecycle payment/cancellation behavior.

Build one frozen actual JAR, run the existing legacy/modern fixture with
RUN_BOOT_JAR_SMOKE=true, preserve source SHA, JAR SHA, logs, process exit codes
and completed XML only after process termination. None of these checks has
run yet; this report makes no passing-test or artifact claim.

No actual context-compaction command or context-usage counter is exposed in
this chat. This inventory is not described as compaction. The separate
coordinator-requested audit and commit stages have not begun.

## Coordinator decision and implemented boundary

The coordinator explicitly authorized a typed application/store contract in
the new `ordering/case/user/**` package: this remains the ordering bounded
context and may write order_case/order_case_image and guarded order DISPUTED
status changes. No extension of accepted lifecycle25 files is needed. Own
scalar ordering reads also reside in this adapter. All ten files listed above
now exist; the proposed names were retained exactly.

`UserOrderCaseStore.markDisputed(CaseOrder, comment)` requires the caller's
writable application JPA transaction and an already-held order lock, compares
the expected status and changes only status/comment. Existing trigger writes
history. Application owns case-opening rules; existing lifecycle25 remains
the only implementation of ordinary payment/cancellation/stock behavior.
LifecycleStore supplies participant locks and the order lock; its accepted
LifecycleTransaction verifies the caller's JPA resources. Media's actual
OrderImageEvidence.activate accepts nullable lists and claims images in the
same transaction. There are no direct image writes, catalog writes, foreign
entities, new transaction managers, retries or caught-and-continued SQL errors.

Opening observes scalar parties first, authorizes, locks distinct participant
IDs ascending, locks the order and compares its locked parties to the initial
observation. Association churn refuses instead of pursuing a new participant
behind the lock frontier. Current status/proof are read under the held order
lock. OPEN cases lock after the order; media image locks come last. The unique
OPEN index remains a database backstop. Case insert uses the prior status,
trimmed comment and version 0; status/history retain the original comment.

Null/empty evidence both work through the accepted media port. Duplicate,
missing, foreign-owner/order, wrong tag/status or reused ACTIVE evidence causes
the complete transaction to roll back. Busy image returns the accepted media
409 IMAGE_BUSY contract. Late relation failures and outer caller failures also
roll back case, image, order and trigger-written history together.

Actual schema correction: V1.21 removes the product foreign key but retains
non-null product_id and imposes non-null snapshot product_currency; the order
prepayment_amount is also non-null. Deleted product tests retain its numeric
ID and snapshot instead of manufacturing nullable rows. Nullable DTO fields
remain representable, matching Java, without changing schema constraints.

## Implementation verification so far

- compileKotlin: exit 0; log `build/migration-26-compile.log`.
- First HTTP run: exit 1, 13 tests/2 failures. Both failures were an invalid
  test fixture assigning NULL to non-null product_id. This was a fixture error,
  not a production contract change. Log/exit code:
  `build/migration-26-http-1.log` / `.exit-code`.
- Second run: exit 1, 17 tests/2 failures. The same test also attempted null
  prepayment_amount/product_currency; PostgreSQL rejected prepayment_amount.
  Fixed the fixture to delete the product and preserve its historical ID and
  required snapshot. Log/exit code: `build/migration-26-http-2.log` / `.exit-code`.
- Third run: exit 0, BUILD SUCCESSFUL in 1m 6s; 17 tests in 3 classes,
  0 failures/errors/skips (legacy HTTP 5, modern HTTP 10, domain 2).
  Completed XML copied only after process exit to
  `build/migration-26-http-3-results`; counters
  `build/migration-26-http-3-summary.json`; log/exit code
  `build/migration-26-http-3.log` / `.exit-code`.
- Two additional deterministic/concurrent modern tests were added after that
  run's compilation. They are included in the broad regression, not claimed
  as already verified by the 17-test result.

Broad command (running at this report stage):

```
gradlew.bat --offline --no-daemon bootJar test
  --tests '*UserOrderCase*Test'
  --tests 'com.model_store.modern.ordering.lifecycle.*HttpTest'
  --tests 'com.model_store.modern.ordering.create.*Test'
  --tests 'com.model_store.modern.catalog.product.reservation.ProductReservationTest'
  --tests 'com.model_store.modern.media.image.command.OrderImageEvidence*Test'
  --tests 'com.model_store.modern.media.image.command.ImageCommandsTest'
  --tests 'com.model_store.modern.media.image.command.ProductImageAttachmentTest'
  --tests 'com.model_store.modern.media.image.command.MediaLockOrderIntegrationTest'
  --tests 'com.model_store.modern.media.image.read.JdbcImageReadPortTest'
  --tests 'com.model_store.modern.shared.config.ModernMvcSecurityTest'
  --tests 'com.model_store.modern.shared.api.ModernSharedHttpIntegrationTest'
  --tests 'com.model_store.modern.shared.api.BootJarDualModeSmokeTest'
```

RUN_BOOT_JAR_SMOKE=true. Before compilation, source hashes for all src/main and
src/test files were saved in `build/migration-26-regression-source.json`.
Log `build/migration-26-regression.log`; exit-code file is written only after
the process exits. No XML from the running process has been inspected.

## Final verification and handoff, 09.11.2026

The broad process completed with exit **1**, BUILD FAILED in **8m 12s**:
**127 tests, one failure**, all other 126 passed. The sole failure was the new
test `changed parties after scalar observation refuse without following new
participant locks`: its observer transaction cached pg_stat_activity, so the
poll never observed a later lock waiter. Fixed only the test observer by
calling pg_stat_clear_snapshot before each poll. It still requires a real
PostgreSQL lock wait before changing the order's parties; it is not weakened
to an arbitrary sleep or an assertion of eventual completion.

Preserved broad evidence: `build/migration-26-regression.log`,
`build/migration-26-regression.exit-code`,
`build/migration-26-regression-results/`,
`build/migration-26-regression-summary.json`. Completed XML was read only
after the process exited. Two read-only thread diagnostics in
`build/migration-26-regression-progress*.txt` showed normal progress through
private embedded PostgreSQL initialization in media tests. No live test process
was duplicated, terminated or silently retried.

After the observer fix, all own tests were rerun together:

```
gradlew.bat --offline --no-daemon test --tests '*UserOrderCase*Test' bootJar
```

Final own run: exit **0**, BUILD SUCCESSFUL in **1m 12s**, **19 tests in three
classes, zero failures/errors/skips**. `bootJar` was up-to-date.

| Own class | Tests |
| --- | ---: |
| UserOrderCaseLegacyHttpTest | 5 |
| UserOrderCaseModernHttpTest | 12 |
| UserOrderCaseTest | 2 |

Log/exit: `build/migration-26-final-http.log` / `.exit-code`.
Completed XML: `build/migration-26-final-http-results/`; counters:
`build/migration-26-final-http-summary.json`.

The 108 unchanged dependency tests from the broad run passed: stock reservation
10; media commands 7, lock ordering 15, ORDER evidence 8+4+2, PRODUCT attachment
4, image reads 1; order creation 12+4; lifecycle legacy/modern 9+21; MVC security
8; shared HTTP 2; actual-JAR smoke 1. Together with the final own 19, current
unique coverage is **127 tests in 18 classes, zero remaining failures/errors/
skips**, recorded in `build/migration-26-combined-summary.json`. This is a
combination of the broad run's unchanged dependency classes and the replacement
own-class results, **not a claim of a single green 127-test invocation**. The
broad command was not repeated after the observer-only test change; production
source and the actual artifact were unchanged.

Actual dual-mode smoke in the broad run passed using the same frozen JAR:

- SHA-256: `3dd3d41dc9213f722825af047825ac48c88a2c579b570747aa7c39ab69339230`.
- Legacy and modern both verified S3/SMTP startup, OpenAPI/security and all
  eight existing negative JSON cases; both child JVMs stopped normally.
- XML: `build/migration-26-regression-results/TEST-com.model_store.modern.shared.api.BootJarDualModeSmokeTest.xml`.
- Child logs copied to `build/migration-26-bootjar-legacy.log` and
  `build/migration-26-bootjar-modern.log` before any later test could replace them.
- The final own run and final hash recheck left that exact JAR unchanged.

Source identity: HEAD remains the supplied accepted base
`68247f3df439923a431300e7c39c175357e8d225`, with ten new uncommitted owner files.
The final 533-file src/main+src/test manifest is
`build/migration-26-final-source.json`, SHA-256
`1732cbf5ed22fb1e04cd1b7e3998d8155f88d79872cafb7c4d93e8fa596ef088`.
After the successful tests, only surplus blank lines at the HTTP test file's
EOF were removed for the whitespace check. The exact tested manifest is
retained as `build/migration-26-final-tested-source.json`, SHA-256
`41bba83cff052ac6829d3f2696c099b92c949cffb1245ccfbc4e6d4a2e5969a8`.
No executable code changed after that successful test run. Final source/JAR
hashes and aggregate counters are in `build/migration-26-final-hashes.json`.
The earlier broad manifest is retained separately; only the observer test
changed afterward, with no production change.

Final checks: all 533 current source files match the final manifest; the JAR
hash still matches the successfully smoked artifact. All ten new files pass
the whitespace check, and tracked git diff is empty. No generated compiler
session file remains in the worktree's untracked change list.

Coverage includes full normalized legacy/modern JSON parity for dispute,
payment-driven cancellation dismissal and appeal; exact route/ID responses and
error envelopes; both parties, outsider denial, ADMIN party restriction and
agent_access denial; null/empty/duplicate/wrong/foreign images; all relevant
status combinations; previous appeal precedence; deleted product snapshots;
trimmed case versus original history comment; OPEN uniqueness; NOWAIT conflict;
late relation failure and outer-JPA rollback; actual participant-frontier wait
followed by party churn; races of opening with another opening, ordinary
payment, cancellation and the real expiry function. Subsequent proof payment
uses lifecycle25 and preserves the eligible dispute's history/evidence behavior.

Limitations: the entire project test suite was not run. Existing shared JAR
smoke verifies startup/security/error contracts, while the new successful
case endpoints run through actual signed HTTP in full Spring test contexts.
No claim is made that legacy reactive writers adopt modern participant lock
ordering, or that administrator resolution27 is implemented. No production
DB, keys, Docker, S3, SMTP or external services were used. No foreign source,
shared config/build, Flyway, plan or user Docker files were changed. Current
local master `2b00807` differs from this instructed base only in MIGRATION_PLAN;
it was inspected read-only and was not merged or modified.

Stage 1 stops here. Separate context-compaction availability confirmation,
independent audit and local commit remain subsequent coordinator stages.

## Separately requested security/plan audit

After the separate confirmation that genuine compaction and its usage counter
are unavailable, the coordinator explicitly requested an independent audit.
Read all ten owner files, actual legacy OrderController/OrderCaseService,
OrderCase/OrderCaseDetail, case/order/image repository contracts, trigger/case
SQL, shared JWT/security and AgentAccountAccessFilter, accepted lifecycle25
rules/store/transaction guard, media12.2 evidence implementation and media12.3
participant/order/image locking implementation. Rechecked the actual plan entry
and dependency reports rather than treating the stage-1 report as an oracle.

Findings and fixes, confined to the existing ten-file owner scope:

1. **ACCESS bot actor authorization mismatch.** Legacy AgentAccountAccessFilter
   denies any acting participant with is_agent=true at all four case routes,
   returning an empty 403. Stage 1 only denied agent_access tokens and allowed
   such a bot's ACCESS token. Fixed through accepted LifecycleStore.isAgent,
   without participant SQL in the case adapter. The controller's ModelAttribute
   authorization runs before argument binding, so missing comment cannot turn
   this into a 400. Commands independently check at entry and again under the
   already-held participant locks. The read route checks the actor too. A human
   party retains access when only the counterparty is a bot. Actual signed
   legacy/modern HTTP tests check both buyer/seller bot actors, all four routes,
   missing query parameters, and the authorized human counterparty. A bounded
   concurrency test changes is_agent while a command is waiting and requires
   empty 403 with no case/status/history write after it obtains the lock.
2. **Ambiguous lock observer and ineffective changed-party fixture.** Stage 1
   matched any waiting participant query and changed buyer to the already-locked
   seller. The test now observes the exact holder backend using pg_blocking_pids,
   verifies the exact waiting SQL after parameter normalization, prints waiter,
   root holder, direct blockers and SQL, and replaces the buyer with a separate
   participant whose ID is lower than both original parties. Caller remains the
   seller, so rejection specifically proves association recheck, rather than
   merely losing party status. No production lock-frontier extension was added.
3. **Race overlap not established by launch barriers alone.** Existing concurrent
   success/failure assertions were retained, but opening/opening and opening/PAY/
   CANCEL now must both be observed blocked behind the known participant holder
   before release. The expiry race additionally holds the order and observes
   the actual SELECT process_expired_booked_orders() session blocked behind that
   holder. The bounded polling sleeps only pace observation; success requires
   an observed lock chain, never a timeout. The observer clears PostgreSQL's
   cached activity snapshot on every poll.
4. **Missing own foreign-manager check.** Added JDBC-only and a genuinely separate
   JPA EMF on the same DataSource to the existing no-tx/read-only/outer-rollback
   test. The read-only check invokes the proxied command, confirming REQUIRED
   participation does not silently upgrade a caller transaction. Existing
   late-relation and outer-failure checks still require complete rollback after
   actual media activation. Added HTTP refresh-token and unsupported-role denial.

Audit changes affect UserOrderCase.kt, UserOrderCases.kt,
UserOrderCaseController.kt, UserOrderCaseErrors.kt, UserOrderCaseHttpTest.kt and
this report. Store, DTOs and domain opening/ordinary lifecycle rules are unchanged.
No file or port belonging to 25/12.2/12.3/shared/another task was edited.

The first audit verification exited **1**, BUILD FAILED in **2m 8s**:
61 tests, 3 failures. All three were the strengthened test observer expecting
two *direct* blockers: PostgreSQL can queue the second waiter behind the first,
which is itself waiting behind the holder. Corrected the observer to walk the
actual blocker graph recursively to the known holder, retaining exact SQL and
waiter-count assertions. No production lock timeout, retry, deadlock catch or
weakened assertion was introduced. Evidence is preserved in
`build/migration-26-audit.log`, `.exit-code`,
`build/migration-26-audit-1-results/` and
`build/migration-26-audit-1-bootjar-{legacy,modern}.log`.

Final audit invocation (running at this stage):

```
gradlew.bat --offline --no-daemon bootJar test
  --tests '*UserOrderCase*Test'
  --tests 'com.model_store.modern.ordering.lifecycle.*HttpTest'
  --tests 'com.model_store.modern.shared.config.ModernMvcSecurityTest'
  --tests 'com.model_store.modern.shared.api.BootJarDualModeSmokeTest'
```

Environment is unchanged: specified JDK21/cache, only fixture RSA paths in
JAVA_TOOL_OPTIONS, RUN_BOOT_JAR_SMOKE=true, isolated embedded PostgreSQL and
mock/local S3/SMTP. Media/catalog/create regressions are unchanged and were
not repeated without cause. New actual-JAR smoke is necessary because audit
changed case authorization production code; the historical stage-1 JAR is no
longer the final artifact. Source hashes were frozen before compilation in
`build/migration-26-audit-final-source.json`; final log/exit code are
`build/migration-26-audit-final.log` / `.exit-code`. XML will be inspected only
after the final process exits.

### Final audit result

Final process exited **0**, **BUILD SUCCESSFUL in 1m 50s**. All **61 tests in
7 classes passed, zero failures/errors/skips**, in this single invocation:

| Class | Tests |
| --- | ---: |
| UserOrderCaseLegacyHttpTest | 6 |
| UserOrderCaseModernHttpTest | 14 |
| UserOrderCaseTest | 2 |
| OrderLifecycleLegacyHttpTest | 9 |
| OrderLifecycleModernHttpTest | 21 |
| ModernMvcSecurityTest | 8 |
| BootJarDualModeSmokeTest | 1 |

Final completed XML was read and copied only after exit 0 to
`build/migration-26-audit-final-results/`; counters are in
`build/migration-26-audit-final-summary.json`. The production authorization
change and all 22 current own tests are covered by this successful invocation.
Earlier stage-1 counts/hashes are historical and are not the final audit result.

Concrete blocker evidence retained in the modern case XML includes:
waiter 39832 -> holder 44268 on exact
`SELECT id FROM participant WHERE id=$1 FOR UPDATE`; waiter 13476 -> 39832 ->
44268 on the same SQL; and waiter 39832 -> holder 44268 on exact
`SELECT process_expired_booked_orders()`. Thus the success includes observed
direct and queued waits, not just simultaneous thread submission. The changed
party and is_agent-revoke tests each observed their actual waiter before the
holder changed the row. No timeout is accepted as a passing race outcome.

Final source manifest: `build/migration-26-audit-final-source.json`,
SHA-256 `19212674fca1c436de03683589d6e56b3eb8de5ad02a83f148615028ac9204db`.
All 533 current src/main+src/test files still match the exact pre-compilation
manifest; no source/test code changed after this successful run. HEAD remains
`68247f3df439923a431300e7c39c175357e8d225` on
`codex/migration-26-user-cases`.

Final actual JAR SHA-256:
`56218b454db0261722940694cfd507eaa0bd9e5bb7c422f8761931db4bc73867`.
The smoke test's frozen copy has this exact hash in completed XML. It verified
both modes with private PostgreSQL and local mock S3/SMTP, including startup,
OpenAPI/security and all eight negative JSON cases. Child logs are preserved
as `build/migration-26-audit-final-bootjar-legacy.log` and
`build/migration-26-audit-final-bootjar-modern.log`.
Source/artifact hashes, source count and test totals are saved together in
`build/migration-26-audit-final-hashes.json`.

Audit conclusions: USER/ADMIN ACCESS tokens still require the relevant party;
ADMIN has no foreign-order override. Only the buyer can appeal; any earlier
PAYMENT_APPEAL wins error precedence even when resolved. agent_access tokens
are denied distinctly from a database bot actor with an ACCESS token. Bot actor
denial now matches actual legacy HTTP, including empty body and parameter-binding
precedence; ordinary lifecycle25's separately accepted bot policy is untouched.
No participant status restriction was invented. OPEN uniqueness, guarded
DISPUTED, sole trigger history, owner-only media activation and shared writable
caller-JPA atomicity remain intact. There is no foreign entity import, foreign
SQL write, ordinary payment/stock rule copy, lock-frontier reversal, exception
swallowing, deadlock retry, test skip or broadened ownership.

Limits remain explicit: the whole project suite and admin27 were not run or
implemented; unchanged media/catalog/create regressions remain the previously
saved stage-1 evidence and were not repeated. Shared JAR smoke checks startup/
security/error contracts; successful case actions are verified with actual
HTTP/JWT in isolated full Spring test contexts. Legacy reactive writers are not
claimed to adopt modern participant lock ordering. No remaining issue requiring
an out-of-scope source change was identified in this audit.

Per the latest instruction, this chat stops after the audit/report, without
commit, push, new task or subagent. Only a separately requested local commit
stage may follow. The ten-file scope and prohibited/shared files remain intact.
