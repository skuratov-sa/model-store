# Migration 12.2 — ORDER image evidence

Stage 1 implementation and separate selfreview, 20.10.2026. No commit or push at these stages.

## Base and ownership

- Managed worktree: `C:/Users/skura/.codex/worktrees/9ef1/model-store`.
- Branch: `codex/migration-12-2-order-evidence`.
- Original development base: local `master` / `e92c060c0e863dc176eb6f18ebea0c33c2cd8c4c` (not `origin/master`).
- Publication base / intended commit parent: local `master` / `313dc520de0ab2c0ae2ce7c48b706411e9b03f7d`. Updated by `git merge --ff-only 313dc520de0ab2c0ae2ce7c48b706411e9b03f7d` after separate publication authorization. All six new files retained identical SHA256 checksums across that fast-forward. Accepted 22/29.2 changes and the plan note are inherited base history, not part of the 12.2 commit diff.
- Accepted dependencies: 12 upload/delete and 24 order creation. Consumers: 25 payment proof, 26/27 case evidence.
- New production files only: `application/OrderImageEvidence.kt`, `infrastructure/JdbcOrderImageEvidence.kt`.
- Own tests: `OrderImageEvidenceTest.kt`, `OrderImageEvidenceIntegrationTest.kt`, `OrderImageEvidenceLegacyContractTest.kt`. This report is in the media command owner package.
- Accepted PRODUCT/profile ports, upload/delete implementation, Java/reactive, ordering/catalog/identity/seller, API/shared/config, Flyway, Gradle, Docker, `MIGRATION_PLAN.md`, `docker-compose.yml` and `plan_2.md` are unchanged.

## Port and caller contract

`OrderImageEvidence.activate(orderId, uploaderId, imageIds): Int` accepts IDs and returns only the number activated. Nullable inputs are explicit so invalid Java/HTTP-derived values produce a typed failure. There are no auth objects, neighboring domain/JPA entities, HTTP types, S3 or mail dependencies.

The caller must use its already open writable JPA transaction managed by `transactionManager`, on the application's EMF/DataSource. Before calling media, ordering must lock the relevant participant rows in stable ID order, lock the order, authorize the caller/uploader as the correct party (including any agent/admin policy), and validate the payment/case transition. Ordering owns all order, history, case and case-image relation writes. Media neither reads participant/order/auth records nor locks them.

The implementation checks an actual transaction, absence of readOnly, an EntityManager bound to the expected EMF and joined to the transaction, and a connection resource bound to the expected DataSource. Its JDBC template is constructed from that same DataSource. A JDBC-only or unrelated transaction fails with `TRANSACTION_REQUIRED`; the port never opens, commits, suspends or replaces a transaction. Runtime failures after the guard mark the joined JPA transaction rollback-only, protecting earlier caller writes even when a caller accidentally catches the exception. Failures must still propagate. In particular, PostgreSQL NOWAIT failure aborts that transaction; it must not be caught and followed by more writes in the same transaction.

All rows are locked and validated before a single image UPDATE. An invalid row therefore causes no partial activation. A port failure or a later ordering failure rolls back both owners' writes. A null or empty batch returns zero, after checking the transaction and positive order/uploader IDs; the payment proof caller must pass exactly one image as a singleton list, whereas a case caller may pass its nullable list directly. `listOf(null)` is a missing image, not a no-op. No per-order evidence limit is added because legacy has none here.

## Actual legacy contract

Reviewed `ImageServiceImpl.activateOrderProof`, `ImageRepository.activateCaseEvidence`, `OrderServiceImpl.transitionWithProof` / `recordPaymentInDispute`, `OrderCaseService.attachEvidence`, modern upload/read/delete and every Flyway file for image changes. The effective image schema is V1.0 + metadata V1.10 + the V1.12–V1.14 variant add/cast/remove sequence + `uploaded_by`/case relations in V1.23. No later migration changes the activation predicate.

The UPDATE predicate is preserved: `tag=ORDER`, `status=TEMPORARY`, exact `uploaded_by`, and `entity_id IS NULL OR entity_id=orderId`. It writes only status ACTIVE and entity_id. ACTIVE is rejected even for the same order/uploader; DELETE, another tag, another uploader (including NULL), another order and missing rows are rejected with the same non-disclosing `IMAGE_NOT_FOUND` problem. Activation is not idempotent.

The two legacy callers have different input/error contracts:

| Input/result | Legacy payment proof | Legacy case evidence | New port / future caller mapping |
| --- | --- | --- | --- |
| One eligible image | Requires UPDATE count 1 | Requires every requested row | Return count 1 |
| Null / nonpositive image ID | No matching row; proof error | No matching row; invalid operation | `IMAGE_NOT_FOUND`; caller maps to its existing proof/case response |
| Null / empty list | Not a proof shape | No-op | Returns 0; case caller may pass its nullable list directly; proof caller must construct a singleton |
| Null / nonpositive order or uploader ID | Caller validates before media | Caller validates before media | `INVALID_INPUT`; refusal at this internal boundary; caller must authorize existing participants/order first |
| Duplicates | Not a singleton shape | Reject before SQL, without silently deduplicating | `DUPLICATE_IDS`; case caller maps to its existing invalid-operation response |
| Missing / ineligible image | `IMAGE_NOT_FOUND`, “Подтверждение оплаты не найдено” | `IllegalArgumentException`, “Операция недоступна для текущего состояния заказа или обращения” | `IMAGE_NOT_FOUND`; ordering preserves the caller-specific response |
| Already ACTIVE / repeated claim | Reject | Reject | Reject, no reactivation |

The port exposes typed problems only. HTTP status/message mapping belongs to 25/26; this task adds no HTTP handler. `IMAGE_BUSY` is a transient locking conflict and must be surfaced as a conflict by those callers rather than retried inside the failed transaction. A retry may start a fresh caller transaction and must repeat authorization and transition checks.

One PostgreSQL contract test obtains SQL directly from the existing `ImageRepository.activateCaseEvidence` annotation by reflection, binds the real query with a JDBC bigint array, and compares its results with the new port on independent fixture rows. A second test class now constructs the actual legacy `ImageServiceImpl` and `OrderCaseService` with a real Spring Data R2DBC `ImageRepository` on a separate private database. It executes their real activation SQL and the actual private case attachment method (without changing its visibility), including original errors and reactive transaction rollback. It does not retype SQL as its sole oracle. Full reactive service HTTP behavior is outside this owner port.

## Locks and concurrency

The ordering sequence is participant → order → image. Media takes only image row locks, one SELECT per distinct ID in ascending order, retaining them until the caller completes. Separate SELECTs make lock acquisition independent of planner sorting. `FOR UPDATE NOWAIT` maps SQLSTATE 55P03 to `IMAGE_BUSY` and bounds contention with other claims or writers; it does not wait while the caller holds participant/order locks.

Modern ORDER upload takes participant then order locks and inserts fresh TEMPORARY rows; unassigned uploads take only the participant lock before INSERT. This agrees with the caller order and does not lock an existing image. Modern deletion rejects ORDER before taking locks. Existing PRODUCT attachment can encounter an ORDER ID but rejects its tag; NOWAIT avoids a wait cycle if it already holds that image. Media never follows an image with a foreign participant/order lock. Legacy UPDATE writers are also serialized by PostgreSQL row locks; modern's NOWAIT fails promptly if such a writer currently owns the row.

Concurrent orders cannot claim the same TEMPORARY row twice. Once a winner commits, a later claimant sees ACTIVE and is rejected. A losing batch does not leave its earlier rows ACTIVE. Tests use bounded future/latch waits of 5–10 seconds.

## Stage 1 verification (before selfreview fixes)

All runs use JDK `C:/Users/skura/.jdks/temurin-21.0.11`, `GRADLE_USER_HOME=C:/Users/skura/.gradle`, `gradlew --offline --no-daemon`. `JAVA_TOOL_OPTIONS` contains only test public/private RSA fixture paths. PostgreSQL instances are private embedded instances; tests use fake/mock storage and mail. No production DB, keys, SMTP or S3 is used.

Stage 1 process: exit **0**, `BUILD SUCCESSFUL in 4m 23s`; log `build/migration-12-2-verification.log`. Completed XML was read only after exit 0. **21 tests, 0 failures, 0 errors, 0 skipped** across five classes, and **bootJar** succeeded:

| Class | Tests | Coverage |
| --- | ---: | --- |
| `OrderImageEvidenceTest` | 7 | Single/batch claim, unassigned/same-order rows, duplicate/null/empty/missing/invalid IDs, wrong tag/status/uploader/entity, repeat rejection, whole-batch and caller rollback, writable JPA guard, JDBC manager/readOnly/no transaction rejection, NOWAIT, concurrent competing orders, actual legacy annotation SQL comparison |
| `OrderImageEvidenceIntegrationTest` | 2 | Full modern Spring wiring, all 29 successful Flyway versions, existing ORDER upload rows and ACTIVE read, identical JPA/JDBC txid, caller participant/order locks, actual order proof commit and rollback, zero S3/SMTP calls during activation |
| `ImageCommandsTest` | 7 | Existing upload/delete/ownership/compensation regressions with fake storage |
| `ProductImageAttachmentTest` | 4 | Accepted PRODUCT attachment/rollback/conflict regressions |
| `JdbcImageReadPortTest` | 1 | Existing PostgreSQL read/visibility regression |

Stage 1 invocation: `gradlew --offline --no-daemon test --tests com.model_store.modern.media.image.command.OrderImageEvidenceTest --tests com.model_store.modern.media.image.command.OrderImageEvidenceIntegrationTest --tests com.model_store.modern.media.image.command.ImageCommandsTest --tests com.model_store.modern.media.image.command.ProductImageAttachmentTest --tests com.model_store.modern.media.image.read.JdbcImageReadPortTest bootJar`.

The full project test suite was not run. No HTTP API was added. Concurrent tests use finite waits; the new tests have no HTTP calls. The implementation and fixtures were compiled against the existing JDK/Gradle dependencies offline. Remaining compiler warnings are existing project warnings and the reflection test's explicit Java `Long` parameter types.

Actual dual-mode JAR smoke verification is assigned by the coordinator to the repaired shared smoke test from chat 20. This task builds bootJar and verifies modern full-context wiring locally; it does not claim a completed legacy/modern process smoke run.

## Separate selfreview

Reviewed every new file, the current 12.2 plan entry, actual proof/case/payment callers, all image-related Flyway history, modern upload/delete/read, PRODUCT attachment, and the accepted JPA guard example. Production changes remain confined to the two new media files. Application contract/problems have no Spring/JPA/HTTP imports; infrastructure owns SQL/JPA. There is no annotated self-invocation, new transaction boundary, REQUIRES_NEW, order/case write, auth entity read, S3 or mail call in the port.

Findings and fixes:

1. Stage 1 rejected a null case list with `INVALID_INPUT`, requiring unnecessary caller normalization despite legacy treating null and empty identically. The port now treats both as a no-op. A singleton null image is `IMAGE_NOT_FOUND`, while duplicates (including repeated nulls) are rejected before row work. The report's table and KDoc were corrected together.
2. Stage 1 relied on exceptions escaping the caller transaction. A caller catching a validation error could commit earlier ordering writes. The port now marks the existing joined JPA transaction rollback-only on runtime failure; an integration test deliberately swallows an error after an earlier successful image activation and actual order update and requires all writes to roll back.
3. Coverage had a JDBC-manager rejection but no real foreign-EMF case. Added a second real EMF on the same DataSource and proved rejection, followed by success on the correct manager.
4. Concurrent batch callers previously supplied the same reversed list. They now supply opposite ID order, and a separate concurrent owner race verifies that a different uploader never claims the image. The NOWAIT test additionally verifies caller-write rollback and recovery in a new transaction.
5. Added actual legacy service/R2DBC execution for nullable proof, case null/empty/duplicates, mixed-batch rollback and repeat rejection; the earlier annotation-SQL parity check remains.
6. Added full-context concurrent ORDER upload/delete verification: while the caller holds participant → order → image locks, ORDER delete refuses promptly and upload waits for caller commit, then inserts a TEMPORARY row. Both worker waits and caller-side assertions are bounded.

Selfreview full regression: exit **0**, `BUILD SUCCESSFUL in 4m 7s`; **25 tests, 0 failures/errors/skips**, **bootJar** succeeded. Log: `build/migration-12-2-selfreview.log`; completed XML preserved in `build/migration-12-2-selfreview-25-results`, counters in `build/migration-12-2-selfreview-summary.json`.

Command: `gradlew --offline --no-daemon test --tests com.model_store.modern.media.image.command.OrderImageEvidenceTest --tests com.model_store.modern.media.image.command.OrderImageEvidenceIntegrationTest --tests com.model_store.modern.media.image.command.OrderImageEvidenceLegacyContractTest --tests com.model_store.modern.media.image.command.ImageCommandsTest --tests com.model_store.modern.media.image.command.ProductImageAttachmentTest --tests com.model_store.modern.media.image.read.JdbcImageReadPortTest bootJar`.

The last upload/delete test was added after compilation of that full regression, so the updated integration class was verified separately: exit **0**, `BUILD SUCCESSFUL in 37s`, **4 tests, 0 failures/errors/skips**, bootJar up-to-date. Log: `build/migration-12-2-selfreview-locks.log`; final completed XML: `build/test-results/test/TEST-com.model_store.modern.media.image.command.OrderImageEvidenceIntegrationTest.xml`.

Second command: `gradlew --offline --no-daemon test --tests com.model_store.modern.media.image.command.OrderImageEvidenceIntegrationTest bootJar`.

Final unique coverage across those two successful invocations: **26 tests in 6 classes, 0 failures, 0 errors, 0 skips**. The second run replaces the integration class's earlier three-test result with its current four-test result; it is not counted twice. Combined counters: `build/migration-12-2-final-summary.json`.

| Current class | Tests |
| --- | ---: |
| `OrderImageEvidenceTest` | 8 |
| `OrderImageEvidenceIntegrationTest` | 4 |
| `OrderImageEvidenceLegacyContractTest` | 2 |
| `ImageCommandsTest` | 7 |
| `ProductImageAttachmentTest` | 4 |
| `JdbcImageReadPortTest` | 1 |

No remaining finding was identified within the reviewed owner scope. Authorization/transition enforcement and HTTP error mapping remain explicit obligations of consumers 25/26/27. The entire project suite and dual-mode JAR process smoke were not run here; the latter remains assigned to the coordinator/shared smoke repair. Tests with the real legacy services use a minimal private image/case-image schema, while the modern Spring integration uses the complete unchanged Flyway schema. No production external service was contacted.

The genuine context-compaction command was unavailable in this chat and no summary was presented as compaction. Selfreview stops without commit/push; publication requires the separate coordinator instruction.

## Publication stage on the updated local base

The separate coordinator instruction authorized commit/push after updating from local master `313dc520de0ab2c0ae2ce7c48b706411e9b03f7d`. The final commit is restricted to these six new files:

- `src/main/kotlin/com/model_store/modern/media/image/command/application/OrderImageEvidence.kt`
- `src/main/kotlin/com/model_store/modern/media/image/command/infrastructure/JdbcOrderImageEvidence.kt`
- `src/main/kotlin/com/model_store/modern/media/image/command/ORDER_EVIDENCE_12_2_REPORT.md`
- `src/test/kotlin/com/model_store/modern/media/image/command/OrderImageEvidenceTest.kt`
- `src/test/kotlin/com/model_store/modern/media/image/command/OrderImageEvidenceIntegrationTest.kt`
- `src/test/kotlin/com/model_store/modern/media/image/command/OrderImageEvidenceLegacyContractTest.kt`

New-base verification: exit **0**, `BUILD SUCCESSFUL in 4m 17s`; **26 tests in 6 classes, 0 failures/errors/skips**, **bootJar** rebuilt successfully on parent/base `313dc520de0ab2c0ae2ce7c48b706411e9b03f7d`. Log: `build/migration-12-2-current-base.log`; completed XML counters (read after exit 0): `build/migration-12-2-current-base-summary.json`. Class counts are the current 8/4/2/7/4/1 shown above, all verified together in this single run.

New-base command: `gradlew --offline --no-daemon test --tests com.model_store.modern.media.image.command.OrderImageEvidenceTest --tests com.model_store.modern.media.image.command.OrderImageEvidenceIntegrationTest --tests com.model_store.modern.media.image.command.OrderImageEvidenceLegacyContractTest --tests com.model_store.modern.media.image.command.ImageCommandsTest --tests com.model_store.modern.media.image.command.ProductImageAttachmentTest --tests com.model_store.modern.media.image.read.JdbcImageReadPortTest bootJar`. JDK/Gradle cache/test-only JAVA_TOOL_OPTIONS remain as specified above; full Spring uses private PostgreSQL/all Flyway plus mocked SMTP/S3, and actual legacy SQL uses a separate private PostgreSQL.

Publication destination is the existing `origin`, `https://github.com/skuratov-sa/model-store.git`, and only `git push origin HEAD:refs/heads/codex/migration-12-2-order-evidence` is authorized. No force/default/all/mirror push or master destination is used. Exact commit SHA, actual parent, checked remote SHA, file list, verification counters and publication command/results are saved after publication in `build/migration-12-2-publication.json`. That receipt is generated outside the committed files so recording the commit's own SHA does not change it. Dual-mode JAR process smoke remains assigned to the coordinator's shared test on the combined artifact.
