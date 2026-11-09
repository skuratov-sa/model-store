# Migration 25 — ordinary order lifecycle

Stage 1 implementation and verification complete, 20.10.2026. Separate selfreview completed and reported 21.10.2026 after resuming from the usage interruption; final test process had already exited successfully on 20.10.2026. No commit or push performed; publication has not started.

Final integration with accepted media 12.3 on exact local master `d8e2982e174796ad0a7fbfae762e85d3f88dcbc3` is complete. The successful run finished 21.10.2026; its saved evidence and unchanged source/JAR were revalidated and its report completed 09.11.2026 after interruptions. The coordinator then separately authorized the current full-diff review, recorded in the last section below. Original-base/SHA, defect, artifact and gate statements in earlier stage sections are historical; the latest review section identifies the final current source/artifact. No commit/push has been performed.

## Base and scope

- Managed worktree: `C:/Users/skura/.codex/worktrees/c0d7/model-store`.
- Branch: `codex/migration-25-order-lifecycle`.
- Exact initial HEAD and local master: `783770f16691286a1e8f3016b7dc5dd0f0dab76d`; branch created explicitly from that SHA, not origin/master.
- MIGRATION_PLAN has checked dependencies 24 and 12.2. Accepted catalog reservation 16.1, identity/seller/shared 20 are present.
- Own production files: `modern/ordering/lifecycle/{domain,application,infrastructure,api}/**`; own tests: `src/test/kotlin/com/model_store/modern/ordering/lifecycle/**`; this report.
- No edits to Java/reactive, Flyway, Gradle/Docker, shared/config, create24, media/catalog/seller/identity, MIGRATION_PLAN, docker-compose.yml or plan_2.md. No new chats or subagents.
- Coordinator later confirmed the media inversion independently in 29.1 (actual 40P01 against 04.5) and assigned its repair to new 12.3 in local master `efbda16`. This worktree remains on its instructed original base. Stage 1 can finish independently; final acceptance of 25 requires accepted 12.3 and a fresh participant/order/product/image regression on the integrated base.

## Actual legacy inventory and boundary

Reviewed the complete OrderController and OrderServiceImpl (not only original plan prose), OrderRepository, OrderStatusHistoryRepository/ServiceImpl, OrderCaseRepository, OrderCaseService payment interactions, OrderMapper, FindOrderResponse, ProductDto, UserInfoDto, OrderTransferDto, OrderStatusHistoryDto, CloseOrderRequest, Order/OrderStatusHistory/OrderCase and related constants. Related enrichers: ParticipantServiceImpl.findShortInfo, ProductServiceImpl.shortInfoById/buildProductDtos/withEffectiveGiveawayStatus, ImageServiceImpl.findActualImages/activateOrderProof; ImageRepository, ProductCategoryRepository, ParticipantRepository, AddressRepository, TransferRepository, UtilService.getImageId.

Schema inventory: all Flyway files enumerated, ordinary orders/history originally V1.0/V1.2; V1.16 external orders, V1.21 detached product and snapshots; V1.22 CANCELLED; V1.23 cases/uploaded_by and current expiry stock return; V1.24–V1.28 giveaway compatibility. Existing tests inventoried: OrderServiceImplTest (including full payment/dispute/cancel concurrency), OrderProductSnapshotMigrationTest, OrderCreateIntegrationTest/LegacyHttpTest, OrderImageEvidenceTest/IntegrationTest/LegacyContractTest and adjacent media command tests.

Owned routes: GET /order/seller and /order/customer, POST /order/{id}/AWAITING_PREPAYMENT, AWAITING_PREPAYMENT_APPROVAL, AWAITING_PAYMENT, ASSEMBLING, ON_THE_WAY, COMPLETED, CANCELLED. GET /order and POST /order/BOOKED stay exclusively with 24. DISPUTED/cancellation-request/payment-appeal/cases endpoints, opening cases and administrator resolutions stay with 26/27. Agent administrator actions stay with 29.3.

Minimal case writes confirmed by coordinator: payment/prepayment dismisses an existing OPEN CANCELLATION_REQUEST with REJECTED/outcome/comment/resolved_at/version; payment on an existing DISPUTED order requires an OPEN DISPUTE whose previous status is AWAITING_PAYMENT and an existing payment proof, replaces the proof and adds order_case_image without changing order status/comment/history. No case creation or resolution endpoint is added. Port methods expose only IDs/scalars and retain case ownership inside ordering.

`OrderPaymentCases` is the narrow application persistence port for those effects. `PaymentCase.requirePaymentAllowed` owns the pure open/kind/previous-status/proof rule. Later 26/27 must lock the order before its case and reuse these existing effects rather than duplicate payment transitions. `LifecycleStore.disputedPayment` guards current DISPUTED status and previous proof; the order lock serializes replacement and resolution. A repeated request using the now ACTIVE proof fails; another fresh proof may be attached to the same eligible open dispute, as in legacy. Closed/other-kind/other-previous-status/missing-proof cases reject without image writes.

Domain rules: seller BOOKED agreement chooses AWAITING_PREPAYMENT iff prepayment>0, otherwise AWAITING_PAYMENT; customer nonzero prepayment from AWAITING_PREPAYMENT; seller approval from AWAITING_PREPAYMENT_APPROVAL; customer payment from AWAITING_PAYMENT; seller shipping from ASSEMBLING; customer completion from ON_THE_WAY; either party cancellation only from BOOKED. Repeats of ordinary transitions fail, with no extra history or stock return. The legacy bot guard applies to every seller route including CANCEL even if invoked by a bot customer; list routes and customer payment/completion do not add bot/account-status restrictions. ACCESS USER/ADMIN only, shared Actor, no JWT reparse.

## Transactions and owners

Commands use explicit JPA transactionManager. Read scalar seller/buyer IDs, lock participant IDs ascending, lock/recheck order; product stock through ProductReservation.returnLimitedStock, proof through OrderImageEvidence.activate(order,uploader,listOf(imageId)), including singleton null. Require activation result 1. Typed missing evidence maps to legacy 404 IMAGE_NOT_FOUND/message; NOWAIT busy maps to 409 and escapes transaction without retry. No foreign writes or entities imported.

The preliminary scalar order observation also preserves legacy compare-and-set errors: validate the observed state/party before waiting, then compare it with the locked current row. A loser that observed a valid old state receives «Статус заказа изменился. Повторите операцию»; a request first observing an already completed transition receives «Нельзя выполнить операцию с данными условиями». Disputed proof replacement compares the observed proof too, returning the legacy payment-changed error for a competing replacement. The existing normal-payment→DISPUTED fallback remains allowed after the lock/recheck. All writes still use guarded status/proof SQL, not a detached entity save.

The existing V1.2 trigger alone writes history on status change (also initial insert); no application history INSERT. It preserves comment and PostgreSQL current_timestamp changed_at. Disputed proof replacement creates no history. Current V1.23 process_expired_booked_orders updates only BOOKED rows and returns limited PURCHASABLE stock itself. Manual cancel returns stock only after its own locked actual BOOKED→CANCELLED transition; an expiry winner makes cancellation fail. Catalog owns current availability/null stock/overflow behavior.

Actual HTTP overflow baseline: legacy returns 500 / INTERNAL_ERROR / «Внутренняя ошибка», with null details. The owner port detects arithmetic overflow earlier; the lifecycle advice preserves that same envelope, rolling back status/history. An initial implementation's invented 409 was corrected after executing the real legacy HTTP case. Busy evidence has no prior NOWAIT HTTP equivalent; its new bounded contention response is 409 / IMAGE_BUSY / «Изображение занято. Повторите операцию».

Delivery URL and comment keep legacy permissiveness: empty strings and arbitrary URL schemes/text are stored verbatim; missing optional comment remains null. Actual legacy HTTP tests establish that comment length 501 and delivery URL length 1001 produce generic 500 INTERNAL_ERROR/null details, with no committed status/history. JDBC translates PostgreSQL 22001/22003 differently from R2DBC; the local API advice explicitly maps those SQL states to the observed legacy envelope. Other integrity errors retain legacy INVALID_REFERENCE/DUPLICATE_KEY mappings. No added URL validation or smaller length cap.

## Explicit legacy filter difference

The actual legacy `configuration/AgentAccountAccessFilter.java:38–46` rejects any signed bot participant outside its two product/upload exceptions **before** OrderController, with empty HTTP 403, including list/customer routes. Inside OrderController, ordinary seller commands (including CANCEL called by a bot customer) have the more specific bot-seller guard. Per the task's explicit instruction, modern reproduces this seller-action guard as 403 ACCESS_DENIED / «Заказами бота управляет администратор» and does not introduce a blanket bot-customer/account-status restriction. Tests distinguish the legacy pre-controller filter from modern use-case behavior explicitly. An ordinary customer can still read/cancel/pay an old order whose seller is a bot; ADMIN tokens retain ordinary party checks, without becoming administrator actions.

## Historical foreign media defect at 783770f — deterministic repro, not a fix

At the original stage-1 base `783770f16691286a1e8f3016b7dc5dd0f0dab76d`, `src/main/kotlin/com/model_store/modern/media/image/command/infrastructure/JdbcImageCommandRows.kt` had the following behavior (these are historical line numbers, not the accepted 12.3 implementation):

- `authorizeUpload` locks the acting participant at line 78.
- PRODUCT branch joins product/owner and locks `FOR UPDATE OF p, owner` at lines 92–100, after the actor lock, without sorting actor and owner IDs.
- Administrator upload to an agent-owned product is explicitly authorized at line 105. If the owner has a smaller ID, this reverses ordering's ascending participant order. The joined product/owner lock can also introduce product→owner ordering.

Historical stage-1 test: `OrderLifecycleModernHttpTest.repro media admin upload reverses ascending participant lock order for agent owned product`. It created an agent owner before the administrator, held the owner participant, started the **actual** `ImageCommandRows.insertBatch(PRODUCT, productId, adminId)`, and waited for PostgreSQL to report that actual joined media query blocked on the owner connection. The owner connection then attempted the administrator lock (ordering's next ascending lock) and deterministically received SQLSTATE 55P03 with a 300ms lock timeout, proving the wait cycle. Rollback released owner and the upload finished. The test avoided leaving a deadlock or hung worker; all waits were bounded. This successful expected-55P03 result was evidence of the defect, not its repair. Its original source is additionally preserved in `build/migration-25-before-d8e2982/test/OrderLifecycleHttpTest.kt`; stage-1/audit completed XML and this historical explanation retain the evidence. The current own test has been replaced only after integrating accepted 12.3, as described below. No media/shared/config file was edited by task 25.

The fixture also links that administrator as the old order's customer and verifies sequential admin upload when actor ID is smaller than owner ID. This complements 29.1's 04.5/actual-40P01 repro with the exact ordering participant sequence and a bounded 55P03 demonstration; it does not repair either owner port.

ORDER upload takes actor participant then order, with no foreign participant lock afterward; ORDER deletion rejects before locking. Own integration test holds both participants ascending and order during a lifecycle transition, verifies delete refusal, lets actual upload wait until commit, then claims its TEMPORARY proof successfully. Foreign owner/actual uploader IDs are not interchanged.

## Verification

Initial compileKotlin: exit 0, BUILD SUCCESSFUL, log `build/migration-25-compile.log`. Only fixture RSA paths in JAVA_TOOL_OPTIONS, JDK 21.0.11 and offline Gradle cache. Test infrastructure uses private embedded PostgreSQL and mock S3/SMTP; no production services.

Successful intermediate HTTP/concurrency runs (XML parsed only after process exit 0):

- `build/migration-25-http-3.log`: exit 0, BUILD SUCCESSFUL in 1m24s, 19 tests (legacy 6, modern 13), no failure/error/skip. Saved XML `build/migration-25-http-3-results`, counters `build/migration-25-http-3-summary.json`.
- `build/migration-25-http-final.log`: exit 0, BUILD SUCCESSFUL in 1m7s, 21 tests (legacy 7, modern 14), no failure/error/skip. Saved XML `build/migration-25-http-final-results`, counters `build/migration-25-http-final-summary.json`. This baseline exposed the legacy overflow response; final mapping and complete normalized JSON comparison were added afterward and are verified below.

Two earlier diagnostic runs failed while implementing PostgreSQL scalar reads (primitive typed getObject, then Float unsupported for float8) and discovering the legacy bot filter. These were corrected using boxed nullable integer/boolean reads and `getFloat`/`wasNull`. Those failed runs are not counted as accepted results; their XML was not parsed.

Broad regression: **exit 0**, **BUILD SUCCESSFUL in 9m13s**, **84 tests in 14 classes, 0 failures, 0 errors, 0 skipped**, and **bootJar**. Log `build/migration-25-regression.log`, copied completed XML `build/migration-25-regression-results`, counters `build/migration-25-regression-summary.json`.

| Class | Tests |
| --- | ---: |
| ProductReservationTest (16.1) | 10 |
| ImageCommandsTest | 7 |
| OrderImageEvidenceTest (12.2) | 8 |
| OrderImageEvidenceIntegrationTest | 4 |
| OrderImageEvidenceLegacyContractTest | 2 |
| ProductImageAttachmentTest | 4 |
| JdbcImageReadPortTest | 1 |
| OrderCreateIntegrationTest (24) | 12 |
| OrderCreateLegacyHttpTest | 4 |
| OrderLifecycleLegacyHttpTest | 7 |
| OrderLifecycleModernHttpTest | 14 |
| ModernMvcSecurityTest | 8 |
| ModernSharedHttpIntegrationTest | 2 |
| BootJarDualModeSmokeTest | 1 |

Invocation: `gradlew --offline --no-daemon bootJar test --tests 'com.model_store.modern.ordering.lifecycle.*HttpTest' --tests 'com.model_store.modern.ordering.create.*Test' --tests 'com.model_store.modern.catalog.product.reservation.ProductReservationTest' --tests 'com.model_store.modern.media.image.command.OrderImageEvidence*Test' --tests 'com.model_store.modern.media.image.command.ImageCommandsTest' --tests 'com.model_store.modern.media.image.command.ProductImageAttachmentTest' --tests 'com.model_store.modern.media.image.read.JdbcImageReadPortTest' --tests 'com.model_store.modern.shared.config.ModernMvcSecurityTest' --tests 'com.model_store.modern.shared.api.ModernSharedHttpIntegrationTest' --tests 'com.model_store.modern.shared.api.BootJarDualModeSmokeTest'`, with `RUN_BOOT_JAR_SMOKE=true`.

This includes actual signed RSA HTTP legacy/modern responses; full normalized JSON equality for completed ordinary/preorder/external flows and enriched/deleted-product/inactive-counterparty lists (only fixture identities/IDs/timestamps normalized, independently asserted against DB); all ordinary status/party refusals; null/wrong/missing/foreign/ACTIVE/DELETE/wrong-tag/wrong-order proofs; repeated claims; history comment/Europe-Moscow formatting; cancellation paid/unpaid/current availability/unlimited/missing product and stock overflow rollback; existing cancellation dismissal and dispute attachment/closed/wrong-kind/wrong-previous-status/missing-proof; competing transition/cancel/payment/expiry/creation/proof claims; late case/outer-tx rollback; NOWAIT and ORDER upload/delete; USER/ADMIN/agent_access; and the preserved media inversion repro.

The broad test JVM was inspected twice while running because Windows embedded PostgreSQL initialization was slow. It was progressing (first in ProductImageAttachmentTest.initdb, later in LifecycleHttpContract.initdb); it completed normally without termination/retry or skipped tests. Diagnostics are `build/migration-25-test-thread-diagnostic*.txt`. No foreign process was modified.

The smoke test ran (not skipped) against a frozen copy of one bootJar in both modes, with its own embedded PostgreSQL and self-contained loopback S3/SMTP. Logs `build/migration-20-bootjar-legacy.log` and `build/migration-20-bootjar-modern.log` are emitted by the accepted shared smoke test. No Docker or localhost:9000 service was used.

Final lifecycle changes after the broad run's compilation: exact 500 overflow envelope, observed-state compare-and-set error handling, local legacy-compatible database error advice, additional URL/comment bounds and deterministic lost-transition test, and HTTP client cleanup.

`build/migration-25-final-verification.log`: exit 0, BUILD SUCCESSFUL in 1m56s, **25 tests** (legacy 8, modern 16, actual dual-mode JAR smoke 1), no failures/errors/skips; bootJar rebuilt. Completed XML/counters are `build/migration-25-final-results` and `build/migration-25-final-summary.json`. Its newly added diagnostic length checks initially required a rollback/error without fixing the status; comparing their actual response output exposed the remaining R2DBC/JDBC 500-versus-409 difference. The advice was corrected, and those checks were strengthened to exact five-field JSON/500/code/message/null-details assertions.

**Final strict contract verification:** `build/migration-25-final-contract.log`, **exit 0**, **BUILD SUCCESSFUL in 1m49s**, **25 tests in 3 classes, zero failures/errors/skips**, and rebuilt **bootJar**. Completed XML parsed only after exit 0, copied to `build/migration-25-final-contract-results`; counters `build/migration-25-final-contract-summary.json`. Invocation: `gradlew --offline --no-daemon bootJar test --tests 'com.model_store.modern.ordering.lifecycle.*HttpTest' --tests 'com.model_store.modern.shared.api.BootJarDualModeSmokeTest'`, with `RUN_BOOT_JAR_SMOKE=true`, same JDK/cache/fixture-only JAVA_TOOL_OPTIONS. Both current length cases now assert and pass the exact actual legacy HTTP envelope. Deterministic observed-state loser error, current/deleted availability stock return, both actor/owner ID directions and client cleanup also pass.

The final run replaces the two earlier lifecycle class results and smoke result rather than adding them twice: **87 unique tests in 14 classes, zero failures, zero errors, zero skipped** across the broad neighbor regression and final current lifecycle/smoke run. Combined per-class counters: `build/migration-25-combined-summary.json`. Broader neighbor results are not falsely attributed to later lifecycle-only changes; the last edits are local advice behavior and own use-case lock/CAS compatibility, covered by the final actual HTTP/concurrency/JAR checks.

Final bootJar SHA-256: `0c775999123e11df49745a8a6d775c1569c90d4b560141fac8f835b42446433a`. Final working tree has only the new production owner package and own lifecycle test directory; no staged files, commit or remote write. HEAD remains `783770f16691286a1e8f3016b7dc5dd0f0dab76d` on `codex/migration-25-order-lifecycle`. The full repository suite was not run; only the explicitly listed targeted classes are claimed. No production services or keys were used.

## Stage boundary

No compression command or context-window/token-usage counter is exposed in this chat's tools. No summary is claimed to be actual context compression. Its unavailability was explicitly confirmed again after completion of implementation; the coordinator then separately authorized the audit below. No commit/push, remote update, checkbox change or next migration item. Final acceptance remains gated by integrated 12.3 and coordinator regression.

## Separate security / DDD / legacy-compatibility audit

Scope: every production lifecycle file, its actual HTTP/concurrency tests, accepted Actor/security, catalog reservation and media evidence contracts/implementations, the real legacy controller/service/repositories/mappers/enrichers, effective Flyway history/expiry functions. Rechecked the plan's domain/application/infrastructure/API ownership and explicit task exceptions. No subagents, media 12.3 edits, shared/config changes, rebase/merge, commit or push.

Findings and fixes:

1. **Writable JPA precondition was implicit on non-proof commands.** `@Transactional(transactionManager="transactionManager")` alone does not reject a joined read-only transaction or protect a mistakenly unproxied call. Added application `LifecycleTransaction` with infrastructure `JpaLifecycleTransaction`: require an actual writable transaction, the expected EMF's joined EntityManager, and bound expected DataSource before locks or writes. Every persistence mutation and write-lock operation (including narrow case effects for future 26/27) checks the same guard, preventing accidental direct-port autocommit. The adapter never begins, suspends, replaces or commits a transaction. A typed internal failure maps to the generic 500 envelope, without exposing JPA details. It extends RuntimeException rather than IllegalStateException so repository exception translation cannot turn the typed guard into a different persistence exception. Tests reject no transaction, JDBC-only, read-only JPA and an actual foreign EMF, assert no order/history/stock effects, then prove a writable outer JPA retains one txid across agreement/payment/evidence. The direct case port outside JPA is rejected too.

2. **Competing dispute payment could choose the wrong legacy error.** The initial code compared the changed proof/status before checking the open case. Legacy first requires OPEN/DISPUTE/previous AWAITING_PAYMENT/original proof, then performs its status/proof CAS. Now a previously observed DISPUTED request checks the locked case against its original scalar observation before the current status/proof CAS. A concurrent closed/resolved case produces the legacy unavailable error; a competing proof replacement in an otherwise eligible open dispute produces the legacy payment-changed error. An AWAITING_PAYMENT request that loses to a newly opened eligible dispute still attaches its proof to that case without another status/comment/history change. Added deterministic blocked-participant races for all three outcomes, including a resolution that also completes the order. Losing proofs remain TEMPORARY; exactly the winner is linked to the case.

3. **Nested HTTP output reused application read-model classes.** Added API-owned user/category/product/transfer DTOs and an explicit scalar mapper; all HTTP serialization and the history time annotation stay in `api/OrderResponses.kt`. Existing full legacy/modern normalized JSON comparisons verify unchanged nulls, fields and values. Application views remain framework/HTTP-free. Also moved the legacy controller's bot-action classification out of the domain `OrderAction` enum into application policy, leaving the domain free of route terminology.

4. **Participant enrichment fetched unnecessary private columns.** Its scalar read now selects only login, phone_number and mail needed by the existing response, instead of SELECT *. Password is never selected, imported, logged or returned by lifecycle.

5. **Rollback coverage omitted dismissed cancellation state.** The late outer-transaction payment rollback fixture now has an OPEN cancellation request. It requires original OPEN/version 0 together with the unchanged order/history and TEMPORARY proof after rollback. Existing duplicate case-image failure verifies order/proof/image rollback after a later case write error; stock rollback is checked separately because no ordinary command both activates a proof and returns stock.

6. **Concurrency diagnostics could cache stale pg_stat_activity.** The initial audit run's two race assertions missed the waiter because PostgreSQL caches statistics within the transaction holding the business lock. The polling helper now calls pg_stat_clear_snapshot before inspecting waiters, retaining the same row locks and transaction; it does not retry any failed business transaction. All connect/request/future/latch/statistics waits remain finite. The diagnostic run ended exit 1 (52 tests, two synchronization assertion failures); its XML was not parsed or counted as accepted results.

Audit conclusions beyond the fixes: query values are bound; the two SQL list-column alternatives are constants derived from a boolean, not client SQL. Lists filter by the verified actor's ID. Wrong party is rejected before evidence reads/claim; ADMIN does not bypass party/bot policy, and normal endpoints do not admit agent_access. USER/ADMIN and legacy bot-filter differences remain as explicitly documented above. No foreign entity/auth/password/mail-service models or Reactor dependency enter lifecycle, no second JWT parse or hidden `@Primary` bridge. Domain owns all ordinary transitions and eligible existing-dispute rule. Application joins JPA and uses only accepted catalog/media ID ports; infrastructure contains persistence/read models and the transaction guard.

Lock/write review: observe scalar order; participant IDs distinct/ascending; lock/recheck order; current product only via catalog return on the one successful BOOKED→CANCELLED transition; image only via singleton media evidence (including null), requiring result 1. Case rows are locked after the order; future case users must retain that order. No product/image/participant UPDATE or application history INSERT. Expiry remains SQL-owned and is never followed by a second return; manual cancellation loses cleanly after expiry. History changed_at remains PostgreSQL transaction time, including its existing tie semantics; no extra ordering/time rules were invented. NOWAIT exceptions escape the failed transaction with 409 and are not retried inside it. All case writes are the two assigned effects; case creation/endpoints/admin resolution remain 26/27.

**Audit verification complete:** `build/migration-25-audit-final.log`, process **exit 0**, **BUILD SUCCESSFUL in 2m28s**, rebuilt **bootJar**, **52 tests in 6 classes, zero failures/errors/skips**. The process completed 20.10.2026 at 21:49:57 Moscow time; on resumption 21.10.2026 its successful completion and unchanged tested source timestamps were checked before parsing the completed XML. No running Java process remained. There was no reason to rerun unchanged passing tests merely because the chat resumed.

| Final audit class | Tests |
| --- | ---: |
| OrderLifecycleLegacyHttpTest | 8 |
| OrderLifecycleModernHttpTest | 19 |
| OrderCreateIntegrationTest | 12 |
| OrderImageEvidenceIntegrationTest | 4 |
| ModernMvcSecurityTest | 8 |
| BootJarDualModeSmokeTest | 1 |

Exact invocation: `gradlew --offline --no-daemon bootJar test --tests 'com.model_store.modern.ordering.lifecycle.*HttpTest' --tests 'com.model_store.modern.ordering.create.OrderCreateIntegrationTest' --tests 'com.model_store.modern.media.image.command.OrderImageEvidenceIntegrationTest' --tests 'com.model_store.modern.shared.config.ModernMvcSecurityTest' --tests 'com.model_store.modern.shared.api.BootJarDualModeSmokeTest'`. Environment remained the assigned JDK 21.0.11/cache, only test RSA paths in JAVA_TOOL_OPTIONS, and `RUN_BOOT_JAR_SMOKE=true`; private embedded PostgreSQL/mock S3/SMTP and the accepted smoke's loopback services, no production service or Docker.

Completed XML copied to `build/migration-25-audit-final-results`; per-class counters saved in `build/migration-25-audit-final-summary.json`. The successful current smoke's frozen artifact SHA-256 is `eb50cafb7b599f7ea4181199dbcc759f93e21c4f1da926f140f3453b02c5657b`, matching the final local bootJar hash. This supersedes the stage-1 artifact hash above. New guard/foreign-EMF, closed-dispute/proof-CAS and normal-payment/dispute fallback tests all appear in the completed XML and pass. The late cancellation rollback assertions and guarded direct case port are also in this tested source state.

**Limits and handoff at audit completion:** all identified own-scope audit findings above were fixed and verified; this was targeted coverage, not a full repository suite or integrated acceptance. The then-unfixed foreign media inversion and pre-12.3 deterministic repro were intentionally unchanged at that stage. The coordinator subsequently accepted 12.3 and assigned the integration below; it replaces the old-behavior expectation with successful serialization using the repaired owner port while retaining historical evidence. Legacy's blanket bot filter versus assigned modern seller-action policy remains an explicit authorized difference, not a newly claimed security equivalence. History transaction-time ties and legacy database-error details are retained. Case creation/administrator resolution/endpoints remain 26/27, scheduler remains 30, and ordinary agent-admin actions remain 29.3.

Final audit worktree scope: new `modern/ordering/lifecycle/**` production files/report and own `src/test/kotlin/com/model_store/modern/ordering/lifecycle/**` only. No media 12.3, shared/config, Java/reactive, Flyway, plan, Gradle/Docker or user file edits. HEAD/base remains `783770f16691286a1e8f3016b7dc5dd0f0dab76d`, branch `codex/migration-25-order-lifecycle`; no staged files, commit, push or remote change. Actual context compression remains unavailable; no summary has been described as compression. This separate audit stage is complete and stopped.

## Final integration with accepted 12.3 — 21.10.2026

The coordinator separately authorized the same task 25 to integrate exact local master `d8e2982e174796ad0a7fbfae762e85d3f88dcbc3`, containing accepted common 12.3 code `44e0c7e` and its plan acceptance. Verified local master resolved to that exact SHA and original HEAD was its ancestor. The 11 changed upstream paths had no overlap with own lifecycle directories. Before updating, copied all 11 own uncommitted files to `build/migration-25-before-d8e2982/{main,test}` and saved per-file SHA-256 in `manifest.json`. Performed only `git merge --ff-only d8e2982e174796ad0a7fbfae762e85d3f88dcbc3`. All 11 hashes matched afterward. No reset, stash, deletion, conflict, remote fetch or origin/master use.

Only own test/report changed during this integration. No lifecycle production change was needed after the completed audit; accepted 12.3/23 files are inherited base commits, not task-25 edits. No media file was modified.

Replaced the historical inversion test with two real owner-port regression tests:

1. `media upload delete serialize below ordering participant order product locks in both actor ID directions`: runs upload and deletion with both admin<owner and owner<admin. Each fixture makes the administrator the customer of an old agent-owned order. The ordering JPA transaction holds the lower party ID; the actual media operation starts on a different backend. After verifying that exact media PID is blocked by the holder, the holder must acquire the higher participant with NOWAIT. It then executes the real lifecycle CANCEL through participant→order→catalog product, asserting media remains blocked until commit. After release the future must succeed, returning one TEMPORARY PRODUCT upload with the correct uploader/entity or completing the formerly eligible ACTIVE→DELETE. Order/history/stock assert one cancellation and one stock return.
2. `media waiting for product does not own images and completes deletion after product unlock`: the holder owns only the product row before starting actual media batch deletion in reversed image-ID order. An exact backend wait dependency proves the media operation has reached that held product. Both image rows must remain immediately lockable with NOWAIT while it waits. After release, the worker must finish successfully and both eligible rows must be DELETE.

Independent expectation review for false positives: worker PID is captured inside its actual caller JPA transaction and asserted different from the holder (no reentrant row-lock probes); `pg_blocking_pids` checks that exact worker/holder pair, not an unrelated blocked session or SQL-text shape. Snapshot refresh prevents stale diagnostic state. Probe assertions execute before releasing the held business row, and the worker must still be unfinished then. Futures propagate all failures, use finite get/queue/poll/termination timeouts, and assert final database states, not just lack of a deadlock exception. Original actor-first behavior in the reverse-ID case would own the high admin and fail the participant NOWAIT probe; original image-before-product deletion would fail an image NOWAIT probe. No skips, tolerated deadlocks, catches of NOWAIT failure, retries inside aborted transactions or relaxed allowed outcomes were introduced. Historical expected-55P03 evidence is not counted as a fix.

**Final integrated verification complete:** process **exit 0**, **BUILD SUCCESSFUL in 7m45s**, **117 tests in 16 classes, zero failures, zero errors, zero skipped**, and rebuilt **bootJar**. The run finished on 21.10.2026 at 11:56:43 Moscow time. Its saved exit-code file was checked before reading/copying completed XML. On resumption 24.10.2026 and again 09.11.2026, all **10 tested Kotlin files** matched `build/migration-25-integration-d8e2982-source-manifest.json` and the current JAR matched its tested hash; no Java process remained on 09.11.2026. The already successful unchanged run was not repeated.

| Integrated class | Tests |
| --- | ---: |
| ProductReservationTest (16.1) | 10 |
| AgentAccessLockIntegrationTest (04.5) | 11 |
| ImageCommandsTest | 7 |
| MediaLockOrderIntegrationTest (12.3) | 15 |
| OrderImageEvidenceIntegrationTest | 4 |
| OrderImageEvidenceLegacyContractTest | 2 |
| OrderImageEvidenceTest (12.2) | 8 |
| ProductImageAttachmentTest | 4 |
| JdbcImageReadPortTest | 1 |
| OrderCreateIntegrationTest (24) | 12 |
| OrderCreateLegacyHttpTest | 4 |
| OrderLifecycleLegacyHttpTest | 8 |
| OrderLifecycleModernHttpTest | 20 |
| BootJarDualModeSmokeTest | 1 |
| ModernSharedHttpIntegrationTest | 2 |
| ModernMvcSecurityTest | 8 |

Exact invocation: `gradlew --offline --no-daemon bootJar test --tests 'com.model_store.modern.ordering.lifecycle.*HttpTest' --tests 'com.model_store.modern.ordering.create.*Test' --tests 'com.model_store.modern.catalog.product.reservation.ProductReservationTest' --tests 'com.model_store.modern.media.image.command.OrderImageEvidence*Test' --tests 'com.model_store.modern.media.image.command.ImageCommandsTest' --tests 'com.model_store.modern.media.image.command.ProductImageAttachmentTest' --tests 'com.model_store.modern.media.image.command.MediaLockOrderIntegrationTest' --tests 'com.model_store.modern.media.image.read.JdbcImageReadPortTest' --tests 'com.model_store.modern.identity.participant.AgentAccessLockIntegrationTest' --tests 'com.model_store.modern.shared.config.ModernMvcSecurityTest' --tests 'com.model_store.modern.shared.api.ModernSharedHttpIntegrationTest' --tests 'com.model_store.modern.shared.api.BootJarDualModeSmokeTest'`. Assigned JDK `C:/Users/skura/.jdks/temurin-21.0.11`, `GRADLE_USER_HOME=C:/Users/skura/.gradle`, `RUN_BOOT_JAR_SMOKE=true`; JAVA_TOOL_OPTIONS contained only `-Dapp.public-key-path=keys/test_public_key.pem -Dapp.private-key-path=keys/test_private_key.pem`. Private embedded PostgreSQL, mock storage/mail and the smoke's self-contained loopback S3/SMTP; no production keys/services, Docker or localhost:9000 service.

Evidence retained:

- `build/migration-25-integration-d8e2982.log` and `.exit-code` (0).
- `build/migration-25-integration-d8e2982-summary.json` (all 16 per-class counters) and `build/migration-25-integration-d8e2982-totals.json` (117/0/0/0, exact base and artifact hash).
- Completed XML copied to `build/migration-25-integration-d8e2982-results/`.
- `build/migration-25-integration-d8e2982-source-manifest.json` (10 tested Kotlin source hashes).
- Frozen same-JAR smoke evidence and copied child logs `build/migration-25-integration-d8e2982-bootjar-legacy.log` and `build/migration-25-integration-d8e2982-bootjar-modern.log`.

Final JAR SHA-256 **`29bbc8ffd8307fbce34a886cc0e0c04a2ca617e4097ef1665ed9a0d2ab977731`** matches the frozen artifact reported by the actual, non-skipped dual-mode smoke. This supersedes all earlier stage artifact hashes.

The two replacement media tests pass with the accepted 12.3 implementation, including all four upload/delete × actor-ID-direction combinations. Their actual ordering CANCEL path proves one history transition and one catalog-owned stock return while media waits without holding the higher participant. The separate product wait proves both images are still free before the product lock is acquired and the permitted delete succeeds after release. Existing ORDER upload/delete, lifecycle rights/status/history/error JSON, proof singleton-null/foreign/wrong/repeat/NOWAIT, cancellation/paid/preorder/ordinary, late order/case/image/stock rollback, case dismissal/disputed payments, expiry/manual-cancel and creation races also pass in this same run. The 12.3 and 04.5 owner tests pass alongside 24, 16.1, 12.2 and security; no weaker outcomes or skips substitute for repaired serialization.

**Final disposition, 09.11.2026:** integration/report stage complete and stopped. HEAD and local master are both `d8e2982e174796ad0a7fbfae762e85d3f88dcbc3`; branch remains `codex/migration-25-order-lifecycle`. The common 12.3 implementation is inherited as `44e0c7ef33209c43f7d5bce33981e4f51afdc6cc`. Own production Kotlin is unchanged from the completed audit; only own test/report were adjusted for integration. Working tree has only the two own lifecycle directories as untracked additions, no staged files or foreign tracked edits; no commit, push, remote operation or checkbox update. This is the named targeted integrated suite, not the entire repository suite. Coordinator acceptance/publication and any subsequently requested audit remain separate steps.

After completing this report, the available tool metadata was checked again: no actual context-compaction command or context-window usage counter is exposed. No compression was performed, and no summary is described as compression.

## Separate full-diff review after 12.3 — 09.11.2026

This is the separately authorized review after final integration/report completion, not a rebranding of the earlier audit. Reviewed all 11 new owner-scope files (9 production Kotlin files, one test file and this report), actual Java routes/service/repositories/mappers/DTO/enrichers, the common security Actor/JWT rules, current accepted media/catalog transaction and locking contracts, and effective history/expiry triggers. Exact base, HEAD and local master remain `d8e2982e174796ad0a7fbfae762e85d3f88dcbc3`, branch `codex/migration-25-order-lifecycle`.

### Newly found and fixed

1. **Participant-avatar tie behavior differed from legacy.** One shared read helper used `ORDER BY created_at,id LIMIT 1` for both product and participant images. Actual `ParticipantServiceImpl.findShortInfo` → `ImageServiceImpl.findMainImage` consumes the first result of `ImageRepository.findActualIdsByEntity`, whose SQL orders only by created_at and has no SQL LIMIT. Product enrichment deliberately uses its different created_at,id main-image query. The participant branch now consumes the existing created_at-only ACTIVE-image read; PRODUCT keeps its ID tie-break. This changes no authorization, foreign writes, public fields, null semantics or transaction boundaries.

   Added a real HTTP test inherited by both legacy and modern: equal-timestamp image rows are inserted with reverse ID order. The oracle executes the **actual Java repository annotation SQL**, rather than duplicating the modern implementation's requested result. The fixture explicitly requires that this oracle pick the higher avatar ID, proving that the old modern ID tie-break would pick a different row; then HTTP userInfo.imageId must match the oracle. A simultaneous product tie must still choose the lower ID in both modes. Both profiles pass. This aligns the read contract; it does not invent a guarantee about general PostgreSQL order among timestamp ties.

2. **Expected concurrent losers were too broadly classified in tests.** Several tests checked success counts/database effects but would accept any captured exception as a loser. Tightened the shared race harness: every failure must be `LifecycleFailure`, never a SQL deadlock/timeout, unexpected rollback, infrastructure error or assertion. It must also have the exact scenario's allowed problem: INVALID_TRANSITION/STATUS_CHANGED for competing ordinary transitions or expiry, PROOF_NOT_FOUND for the competing shared proof, and DISPUTE_PAYMENT_UNAVAILABLE for concurrent case closure. Positive winners/final state/count assertions remain. This is a coverage fix, not a claim that a production deadlock was reproduced after 12.3.

No new production IDOR, privilege bypass, auth/password/entity leak, cross-context write, transaction/rollback defect or domain ownership violation was found in the remainder of the reviewed diff. Only own JdbcLifecycleRead, test and report changed in this review; media/Java/reactive/Flyway/shared/plan were not edited.

### Security, transaction and compatibility checks

- **Authorization/IDOR:** shared verified Actor only; ACCESS USER/ADMIN and positive ID required. Lists are keyed by actor ID; path order IDs never grant party rights. Every command checks seller/customer against the observation and rechecks unchanged parties under the order lock. ADMIN is an ordinary party on these routes, with no administrative bypass. Body accepts only closureReason/comment and cannot substitute actor, seller/customer, uploader, case ID or action. No second JWT parse. Current is_agent is checked again under participant locks for the assigned bot-forbidden actions; customer and old agent-owned order policy is retained.
- **Information boundaries:** API emits explicit local scalar DTOs; no foreign entity/auth/password/mail-service model, persistence object or JWT claims object is serialized. Participant SQL selects only the legacy response's login/phone/mail for the authorized counterparty, not password. All query values are bound; the two interpolated list-column choices are application constants, not request SQL.
- **Writable common transaction:** application uses the named JPA transactionManager; command and persistence guards reject no transaction, read-only, JDBC-only and foreign EMF before a write/lock. Existing tested shared EMF/DataSource resources are used; no REQUIRES_NEW, private commit, hidden service replacement or S3 action. Runtime failures escape the transaction boundary, including stock/case and media failures; media also marks rollback-only. Existing late failures verify order/history/case/image/stock rollback.
- **Lock order after 12.3:** distinct participant IDs ascending → locked/rechecked order → catalog product for actual cancellation, or media images for proof; case locks follow the order. FK references point only to rows already locked by the caller. The accepted media owner code acquires participants before product/order and images afterward. Current successful integration tests prove no high-actor lock before low owner and no image lock while waiting on product, then require actual permitted upload/delete results. No failed transaction is retried.
- **Proof:** always `listOf(imageId)`, even null, with required activation count 1. The accepted port enforces ORDER/TEMPORARY/uploaded_by/entity null-or-same-order, positive IDs and ascending NOWAIT claims; a singleton null is 404 rather than a zero-image success. Caller checks party/transition first. ACTIVE/repeated/foreign/wrong-tag/wrong-order/missing proof cannot be claimed; busy is a bounded 409.
- **Stock/expiry:** only a successful locked BOOKED→CANCELLED transition returns stock, only through catalog. Current availability and limited/null count rules, deleted/missing product and overflow follow the accepted port. SQL expiry already performs its own return and is not followed by another one. Repeats/losers make no second write; SQL's status predicate and the held order lock serialize expiry/manual cancellation. Overflow and late caller failure roll back status/history as well as stock.
- **History/cases:** only the existing trigger inserts history, with actual comment and transaction timestamp. Repeat errors do not insert history. Ordinary payment/prepayment dismisses only an existing OPEN cancellation request; eligible existing-dispute payment replaces proof/adds evidence without changing status/comment/history. Open/kind/previous-status/original-proof and current proof/status CAS are checked in legacy order. Closed-case and normal-payment/dispute races retain the actual legacy refusal/fallback. No case creation, case endpoints or administrator resolution were added.
- **DDD/scope:** pure domain transitions and existing-dispute rule; application orchestration/ports; persistence/read models/guard in infrastructure; DTO/error conversion in API. Neighbor interfaces carry IDs/scalars, no foreign aggregate associations. GET /order and POST /order/BOOKED remain exclusively in accepted 24. No common config, dependency, migration, Docker or user-file changes, no new task/subagent.

### Explicit handoff differences and limits for 32

1. The actual legacy global bot filter emits empty 403 before controller invocation, even for customer/list routes. The explicitly assigned modern policy instead rejects seller actions (including CANCEL by a bot actor) with typed 403 JSON while retaining customer/list behavior and access to old agent-owned orders. This authorized difference remains visible in tests; no equivalence with the blanket legacy filter is claimed.
2. Ordinary routes trust the accepted signed ACCESS Actor role/ID and preserve legacy access for signed non-agent BLOCKED/DELETED accounts. There is no new per-request account-status/revocation check here. ADMIN still gains no action on another party's order; fresh administrator/agent administration checks belong to 29.3/owner ports, not these ordinary routes. Accepted shared security's stricter invalid token/agent_access rules are inherited, not silently reimplemented.
3. Evidence NOWAIT contention has the explicit new 409 IMAGE_BUSY response; legacy's waiting writers have no identical bounded-contention response. No retry in the failed transaction is allowed. Human-facing length/overflow and ordinary transition errors match executed legacy HTTP; retained raw database `details.dbMessage` can remain driver-specific as already identified by shared 20 and must be reviewed in 32.
4. History retains PostgreSQL current_timestamp and its tie behavior. Participant image ordering now matches legacy's timestamp-only rule; no stable secondary avatar ID is promised. Product main-image ID tie-break is preserved. List reads retain legacy enrichment/query scaling; no full-suite, load/performance or parallel modern/reactive-writer guarantee is claimed. Only one chosen runtime should own live writes; unmigrated case/admin/scheduler integration remains 26/27/29.3/30/31.

### Verification of this review's final state

The unchanged successful integrated **117 tests / 16 classes** run on d8e2982 remains saved, not repeated. After the real participant-read fix, `build/migration-25-post12-3-review.log` completed **exit 0**, **BUILD SUCCESSFUL in 1m34s**, rebuilt bootJar and **31 tests / 3 classes**, no failures/errors/skips: legacy lifecycle 9, modern lifecycle 21, actual dual-mode JAR smoke 1. Completed XML (parsed only after exit 0) is saved in `build/migration-25-post12-3-review-results`, counters in `build/migration-25-post12-3-review-summary.json`, exit code in `.exit-code`, child logs in `build/migration-25-post12-3-review-bootjar-{legacy,modern}.log`.

Invocation: `gradlew --offline --no-daemon bootJar test --tests 'com.model_store.modern.ordering.lifecycle.*HttpTest' --tests 'com.model_store.modern.shared.api.BootJarDualModeSmokeTest'`, with the same assigned JDK/cache, test-RSA-only JAVA_TOOL_OPTIONS, `RUN_BOOT_JAR_SMOKE=true`, private PostgreSQL and mocks/loopback services. Both avatar-oracle HTTP cases appear and pass in the XML. Frozen same-JAR smoke SHA-256 **`4848506723e709856f2350ff3075f2734e207fefe956b57126387918f4131ef5`**, matching the local final bootJar, supersedes the earlier integration artifact hash.

The subsequent race-harness edits change tests only, not production/JAR. Their first type-check iteration passed the three affected tests (exit 0, 43s); then the allowed business-problem sets were narrowed as described above. **Final exact-problem targeted run: exit 0, BUILD SUCCESSFUL in 41s, 3 tests, zero failures/errors/skips**. Evidence: `build/migration-25-post12-3-race-final.log`, `.exit-code`, `-results/` completed XML and `-summary.json`. The three tests are concurrent transitions/cancellation/payment/expiry/creation, competing proof/NOWAIT/late rollback, and invalid/resolved disputes/late case attachment failure. Each contains multiple actual PostgreSQL scenarios; no scenario is skipped.

Exact targeted invocation: `gradlew --offline --no-daemon test --tests '*OrderLifecycleModernHttpTest.concurrent transitions*' --tests '*OrderLifecycleModernHttpTest.proof competing*' --tests '*OrderLifecycleModernHttpTest.invalid and resolved*'`, same assigned JDK/cache and test-key-only JAVA_TOOL_OPTIONS. XML was parsed/copied only after confirmed process exit 0. No unchanged neighbor suite or identical JAR smoke was repeated for a test-only assertion change. These are replacement results, not 117+31+3 unique tests or a fresh full suite. Aggregate review evidence is `build/migration-25-post12-3-review-totals.json`; final current Kotlin source hashes are `build/migration-25-post12-3-review-source-manifest.json`. The local JAR still matches the 31-test run's actual frozen dual-mode smoke hash, because only test assertions changed afterward.

### Complete task-25 file list

Under `src/main/kotlin/com/model_store/modern/ordering/lifecycle/`:

- `domain/OrderLifecycle.kt`
- `application/OrderLifecycle.kt`
- `application/OrderViews.kt`
- `infrastructure/JdbcLifecycleStore.kt`
- `infrastructure/JdbcLifecycleRead.kt`
- `infrastructure/JpaLifecycleTransaction.kt`
- `api/OrderLifecycleController.kt`
- `api/OrderLifecycleErrors.kt`
- `api/OrderResponses.kt`
- `MIGRATION_25_REPORT.md`

Own tests: `src/test/kotlin/com/model_store/modern/ordering/lifecycle/OrderLifecycleHttpTest.kt`.

**Final review disposition:** complete and stopped on 09.11.2026. Found/fixed one real read-compatibility deviation (participant timestamp ties) and one race-test false-positive gap (unexpected losing exceptions/problem codes). No other new own-scope security or DDD violation found. Remaining intentional differences/limits for 32 are explicitly listed above; unmigrated cases/admin/scheduling and mixed-reactive-writer/performance guarantees are not claimed. Exact local base/HEAD/master remains `d8e2982e174796ad0a7fbfae762e85d3f88dcbc3`, branch `codex/migration-25-order-lifecycle`. Current task file list is the 11 files above, all still uncommitted/untracked additions. This review changed only `infrastructure/JdbcLifecycleRead.kt`, own `OrderLifecycleHttpTest.kt` and this report; foreign tracked files and Java/reactive/Flyway/shared/common plan/user files are unchanged. `git diff --check` passes; no Java process remained after tests. No commit, push, checkbox update, new task or subagent. Publication/acceptance remains a separate coordinator step. No actual context-compaction command or context-window usage counter is exposed; no summary is claimed as compression.

## Separately authorized publication handoff — 09.11.2026

After accepting the completed final review, the coordinator explicitly authorized committing these exact 11 lifecycle files and pushing only `HEAD:refs/heads/codex/migration-25-order-lifecycle`. Pre-publication checks confirmed current branch `codex/migration-25-order-lifecycle`, tested HEAD/intended parent `d8e2982e174796ad0a7fbfae762e85d3f88dcbc3`, no staged or foreign tracked edits, the exact owner file list above, and unchanged hashes for all 10 tested Kotlin files. The current tested JAR still matches `4848506723e709856f2350ff3075f2734e207fefe956b57126387918f4131ef5`. Only this publication note was added after testing; production/test code is unchanged.

Local master subsequently advanced to coordinator-reported `55919e4dd564e855986813434bc71652940208c2` for plan-only item 16.2. Per explicit instruction, this tested branch is not updated/rebased for that unrelated plan note. The publication parent stays d8e2982, and shared master/plan are not publication targets.

Both actual origin fetch and push URLs were verified as `https://github.com/skuratov-sa/model-store.git`. A read-only remote query before staging/publication returned master `3899e2daa212b675d97459c70b53c3f339489dce`; the destination branch did not yet exist. Stage is limited to the two own lifecycle directories, with no build evidence, broad add, Java/media/shared/plan or user files. No force/default/all/mirror/master push is authorized. The final commit SHA/parent, remote destination SHA, post-push master comparison and clean-tree result will be reported in the separate publication completion message; the commit cannot embed its own SHA.
