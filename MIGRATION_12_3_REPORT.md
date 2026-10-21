# 12.3 — media participant/product/image lock order

## Scope and base

Implementation and separately requested independent audit; no commit/push. Branch:
`codex/migration-12-3-media-lock-order`, managed worktree `7330/model-store`.
Initial HEAD and local master: `efbda16ca3973baa5b1be69a026174e29de6f525`.
Confirmed ancestors: 12 `ac165c4`, 12.1 `a148f54`, 12.2 `99977fa`, 04.5 `f788303`.
No dependency on unaccepted 29.1. Its transaction test was read only as permitted.

Production behavior scope: `JdbcImageCommandRows.kt` only. New regression:
`MediaLockOrderIntegrationTest.kt`. The coordinator subsequently transferred exactly
the opposing-order test and synchronization helpers in `ProductImageAttachmentTest.kt`,
and authorized a comment-only correction in `JdbcProductImageAttachment.kt` (no port
code/contract changes). Other owner contexts, Java/reactive, migrations,
build/shared/security settings and MIGRATION_PLAN are unchanged.

## Defect and deterministic evidence

The regression uses actual Spring/JPA transactions and the accepted ParticipantStore
and AgentAccessLock. It creates agentId < adminId, holds the agent through
ParticipantStore.findForUpdate, starts media upload/delete, confirms the media backend
is blocked behind the owner using pg_blocking_pids, then invokes
AgentAccessLock.lockActiveAdminAndAgent in the holder's transaction. The original
media implementation holds admin and waits for agent; the accepted access lock holds
agent and waits for admin. PostgreSQL detects the cycle.

Baseline bootJar exited 0. Baseline test exited 1: both success assertions failed,
and both logged `[null, 40P01]` outcomes. This is evidence of a defect, not a passing
repair test. Logs/XML/JSON are frozen under `build/migration-12-3/before/`.
All database instances are private embedded PostgreSQL; no shared database or S3
localhost:9000 is used. Latches, finite SQL/future deadlines and pg_blocking_pids
establish the races; no sleep establishes ordering. Workers and embedded servers
are closed independently of assertion outcomes.

## Protocol and authorization

Discovery reads take no locks. PRODUCT upload discovers the product owner; batch
PRODUCT delete first discovers the entire image/product/owner set. The command then
locks distinct participant IDs ascending (including actor), product IDs ascending,
and finally image IDs ascending. Product locks do not join/lock participant rows.
No participant is acquired after product/image locks. ORDER upload needs the actor
participant followed by the order; image insertion comes last.

Participant role/status/is_agent are scalar JDBC values obtained under row locks,
independent of already managed JPA snapshots. Product status/availability/owner and
ORDER parties/status are read again under their row locks. A changed product owner
compared to discovery is denied, without extending the participant lock set. Delete
reads and validates every image again under locks and denies changed tag/entity
associations rather than following a new product after taking image locks. Status
and whole-batch existence checks remain mandatory.
The unlocked upload preflight does not grant durable permission: insertion repeats
the checks under locks after storage work; the existing compensation handles denial.

The existing task 12 rules remain: actor must exist and be ACTIVE; PARTICIPANT target
must be actor; SYSTEM upload needs ADMIN; unassigned PRODUCT/ORDER upload remains
TEMPORARY; PRODUCT owner edits require ADMIN or ACTIVE/non-GIVEAWAY, and foreign
edits require ADMIN plus owner.is_agent. DELETED products are denied. Owner status
and actor.is_agent introduce no additional restrictions. ORDER upload requires a
current party and rejects COMPLETED/FAILED; ORDER/SYSTEM deletion is denied before
row acquisition. ACTIVE delete remains whole-batch and tag/ownership checked.

TransactionTemplate retains REQUIRED behavior with the configured production JPA
manager. There is no REQUIRES_NEW, manual commit, transaction retry or caught SQL
deadlock. Association conflicts use the existing ImageCommandDenied error and
roll back the transaction. ImageCommands retains its existing S3 compensation on
an insert rejection; no S3 calls or owner-table writes were added to rows.

Accepted ProductImageAttachment and OrderImageEvidence code is unchanged, including
NOWAIT/55P03, uploader/tag/status/entity validation and caller-owned JPA boundaries.
ORDER evidence null/empty case -> 0 and singleton null proof -> error are unchanged.

## Verification

Commands use JDK `C:/Users/skura/.jdks/temurin-21.0.11`,
GRADLE_USER_HOME `C:/Users/skura/.gradle`, `--offline --no-daemon`, test RSA paths
through JAVA_TOOL_OPTIONS, and RUN_BOOT_JAR_SMOKE=true. bootJar precedes tests.

| Run | Result | Frozen evidence |
| --- | --- | --- |
| Baseline bootJar + two repro tests | bootJar exit 0; test exit 1; 2 failures, both real 40P01 | `build/migration-12-3/before/` |
| First fix attempt | 28 tests, one new fixture failure from duplicate active product name for the same owner; other 27 passed | `build/migration-12-3/fix-attempt-1/` |
| Verified bootJar | exit 0 | `build/migration-12-3/verified-bootjar.log`, `.exitcode` |
| Expanded regression, 21 October 2026 | exit 1; 104 tests in 18 classes, 1 failure, 0 errors, 0 skipped | `build/migration-12-3/regression-1/` |
| Revised lock-order test against original production | bootJar exit 0; test exit 1; independent image NOWAIT probe fails as required | `build/migration-12-3/revised-test-before/` |
| Stage 1 final fixed bootJar and targeted regression/smoke | bootJar exit 0; test exit 0; 17 tests, 0 failures/errors/skipped | `build/migration-12-3/final/` |

The first fix run completed during the usage interruption; its PTY session was no
longer available on resume. The saved log says BUILD FAILED; the JSON deliberately
leaves its process exit code null. Subsequent runs write an exit-code file as well
as returning the actual process exit code. XML was copied only after completion,
before the next Gradle test run. The fixture now generates a unique product name.

All 12 MediaLockOrderIntegrationTest cases passed. Both original race scenarios now
log `[null, null]` outcomes, with no SQL error. Additional checks cover all-owner
batch discovery, changed product owner and image association, stale JPA actor load
and role/status revocation, changed owner is_agent/product/image status, accepted
BLOCKED-owner/agent-ADMIN behavior, caller rollback and caught-error rollback,
actual reservation/ProductImageAttachment contention, ascending image acquisition,
and ORDER party/status changes while waiting.

The expanded run also passed ImageCommands (7), command HTTP (1), read HTTP/JDBC/use
cases (9), S3 storage (7), ORDER evidence unit/integration/legacy (14), accepted
AgentAccessLock (11) and AgentProfileCommands (8), product modern/legacy HTTP (8),
ProductReservation (10), OrderCreateIntegration (12), and real dual-mode JAR smoke
(1). ProductImageAttachment passed its ownership/validation, rollback and independent
NOWAIT/55P03 checks (3). No accepted port implementation was modified.

The successful smoke used one frozen JAR with SHA-256
`31716f167ee3643db1f78be75381b53fc24843ff10ab91e9491bbe50bd6b9826`.
Its isolated S3/SMTP and PostgreSQL fixtures and negative HTTP scenarios ran in both
legacy and modern without skips. Child logs are copied into regression-1.

### Coordinated correction of the existing lock-order test

The sole expanded-regression failure is the existing
`ProductImageAttachmentTest.opposing media and product lock order resolves without deadlock`.
It explicitly waits for media to lock an image while blocked on a product, then
expects the owner port's NOWAIT conflict. The new required order intentionally
prevents that image lock, so awaitImageLock fails. This assertion must be updated,
not restored in production. The separate independent contended-image 55P03 test
already passed. The new successful
`reservation and product image deletion finish before waiting media acquires images`
test checks the corrected interaction with real caller JPA, the accepted reservation
and media owner ports, and a normal not-found outcome for the later delete.

The task's explicit scope required proposing this existing file before editing.
The coordinator approved the exact test and synchronization helpers on 21 October.
The replacement observes pg_blocking_pids, then uses a separate connection and
FOR UPDATE NOWAIT to prove that the image is free while media waits for product.
The holder deletes through ProductImageAttachment; after its commit the waiting
media delete returns the existing ImageCommandNotFound result. Final image status,
association, uploader and product state are checked.

The replacement also explicitly holds a second image in an independent connection,
calls markProductImagesDeleted after an earlier product write, requires typed
IMAGE_BUSY, and checks that the product write rolled back and every image retained
its expected state/count. The separate accepted claim contention test is unchanged.
No 55P03 coverage is removed. The old awaitImageLock/sleep helper is replaced by a
bounded pg_blocking_pids observer. The owner port's sole change is the stale comment
about the old media image-before-product order.

The revised test was run against the original production rows implementation:
bootJar exited 0, the selected test exited 1 because its separate connection could
not lock the image while media waited for product. The original production file
was used only for this negative proof and restored in finally. Its restored fixed
source hash matches the expanded regression (`EE068436D2FDF85CAB6958877C0F75FCE11D63153F0B73093441D2CCFC06BF4E`).

Stage 1 final verification rebuilt bootJar and ran
`test --tests '*ProductImageAttachmentTest' --tests '*MediaLockOrderIntegrationTest' --tests '*BootJarDualModeSmokeTest'`.
Both commands exited 0. All 17 tests passed: 4 accepted PRODUCT-port tests including
the coordinated replacement, 12 new race/transaction tests and 1 actual dual-mode
JAR test, with no skips. Both original 40P01 scenarios again logged `[null, null]`.
The final JAR SHA-256 is identical to the expanded regression's successful smoke,
so the other 103 successful cases refer to the same production artifact. The only
failed obsolete assertion was replaced and passed in the final run; the full
104-test expanded selection was not redundantly rerun after the test/comment-only
correction. The before/after XML, per-run logs, exit codes, JSON summaries, child
logs and final source/JAR hashes are preserved in the directories above.

Stage 1 completed locally. Afterwards, the lack of an available real context
compression command was explicitly confirmed in its own turn; no summary was
presented as compression. The coordinator then separately requested the audit below.

## Independent audit — 21 October 2026

The audit re-read the base and final implementation, actual Java ImageController /
ImageServiceImpl / JwtServiceImpl behavior, task 12/12.1/12.2 contracts, 04.5,
ordinary product and reservation callers, HTTP security/advice, every changed test,
and saved XML/exit codes/input hashes. No additional production defect or new
authorization expansion was found. This is a scoped review, not a claim about
unmigrated writers or all application security.

### Findings and corrections

1. Read-only/foreign-manager behavior lacked an explicit media-rows regression.
   Added an actual Spring/JPA test for both insert and delete under an outer
   read-only transaction: PostgreSQL must reject FOR UPDATE with SQLSTATE 25006,
   leaving rows unchanged. A caller DataSourceTransactionManager must fail to open
   the production JPA boundary with CannotCreateTransactionException; row count,
   status and association remain unchanged. No new transaction policy was added.
2. The old media MVC test used a supplied Actor and fake rows, so it did not prove
   real token/DB checks. Added RSA-signed requests through the actual security
   filter chain and media commands: guest/refresh refusal, own avatar upload with
   null entity normalized to actor, metadata read, foreign-avatar refusal, successful
   delete and repeated-delete 404, ORDER/SYSTEM delete denial, and a previously
   authorized ADMIN token rejected after its DB role is revoked. The foreign
   product is agent-owned and preflight succeeds before revocation, preventing a
   false-positive authorization test.
3. The changed-owner test denied a new lower owner, but did not prove it avoided
   acquiring that participant. It now holds the replacement participant independently
   with FOR NO KEY UPDATE (FK-compatible). The media denial must complete while that
   row is still held; a late FOR UPDATE on the replacement would block/fail the test.
   This strengthens the existing test; production code is unchanged.
4. Historical results needed a clear stage boundary after adding audit tests. The
   stage 1 104/17 counts above are labelled historical; latest audit results and
   input hashes are recorded separately below. Old XML is never read as a new run.

The first audit run executed 58 tests, with 2 failures caused by incorrect new test
expectations, not production changes: media already accepts signed agent_access as
that actor (the test wrongly expected the separate product API's 403 policy), and
the foreign-manager wrapper is CannotCreateTransactionException rather than
IllegalTransactionStateException. The completed failed run and all XML were frozen
under `build/migration-12-3/audit/attempt-1/` before editing/retesting. The corrected
HTTP fixture now uses an actual is_agent participant, requires 200 for its own
avatar, and verifies entity_id/uploaded_by remain that agent. This preserves the
accepted contract instead of importing a new agent restriction.

### Reviewed boundaries and remaining limits

- All product-owner discovery completes before any participant lock. Participant
  IDs are distinct/ascending, products lock by separate ascending-ID statements,
  and images use ORDER BY id before FOR UPDATE. No new participant is acquired
  after product/image locks. ORDER upload needs actor then order; forbidden ORDER
  deletion never follows image/order associations.
- JDBC reads under locks do not reuse managed JPA snapshots. Changed product owner
  is rejected; changed image entity/tag is never followed to a new lock set. All
  images are validated before the first batch UPDATE. Existing caller rollback and
  caught-error rollback tests remain, with no own commit, REQUIRES_NEW or SQL retry.
- Role/status/is_agent rules match the accepted media 12 base. In particular,
  agent_access is usable for images as that actor; SYSTEM upload still requires
  the actor's current ADMIN DB role. Foreign PRODUCT access still requires current
  ADMIN and owner.is_agent. Owner status and actor.is_agent add no new restriction.
- Raw legacy has previously accepted differences: it allows SYSTEM upload through
  isActualEntity, permits null participant binding and may filter invalid IDs in a
  delete batch. Media 12 already tightened these, validates ACTIVE actor/status,
  binds participant uploads to self and requires an entire valid batch. Task 12.3
  preserves the accepted media behavior; it does not claim these old differences
  disappeared or restore raw legacy's weaker checks.
- Only image is written. Participant/product/order access is parameterized scalar
  SELECT; private infrastructure records do not cross a port. There are no foreign
  domain/JPA/auth imports, password/mail reads or owner-table writes in production.
  Tests alone use the accepted ParticipantStore to establish locks/stale JPA state.
- ProductImageAttachment code is still comment-only changed. Independent claim
  NOWAIT and delete IMAGE_BUSY/rollback checks remain explicit. OrderImageEvidence
  implementation is unchanged, including strict caller-JPA/read-only/foreign-manager
  rejection, null/empty case -> 0, singleton-null proof error and atomic claims.
- No transaction retry or S3 operation was added to rows. Existing upload compensation
  covers a failed insert; a later outer-caller rollback after a successful upload,
  process death or failed S3 cleanup can still require reconciliation as in task 12.
  This audit does not introduce a distributed transaction or silently narrow the
  caller-managed DB transaction boundary.

### Audit verification and disposition

| Audit run | Actual result | Evidence |
| --- | --- | --- |
| All media + dual-mode JAR, first attempt | bootJar exit 0, test exit 1; 58 tests, 2 new-test expectation failures, 0 errors/skips | `build/migration-12-3/audit/attempt-1/` |
| Final current-file targeted audit | bootJar exit 0, test exit 0; 20 tests, 0 failures/errors/skips | `build/migration-12-3/audit/final/` |

The 58-test attempt is not represented as a successful run. Its 56 successful
cases include all 14 OrderImageEvidence unit/integration/legacy cases, command and
read HTTP/use-case/JDBC regressions, storage/compensation tests, PRODUCT-port tests
and actual dual-mode smoke. Only two expectations in the new audit tests changed
after it. The final targeted run re-executed the entire current
MediaLockOrderIntegrationTest (15, comprising the original 12 with strengthened
owner-race proof plus 3 audit cases), ProductImageAttachmentTest (4), and the actual
BootJarDualModeSmokeTest (1). These 20 final tests all passed. Unchanged classes were
not rerun a second time solely to produce a single larger green count.

The final audit JAR is still
`31716f167ee3643db1f78be75381b53fc24843ff10ab91e9491bbe50bd6b9826`.
Production rows hash remains
`EE068436D2FDF85CAB6958877C0F75FCE11D63153F0B73093441D2CCFC06BF4E`;
the existing PRODUCT test remains
`B71A3D245B54A62271B15B7F33AED80D8D5FAEEB14F32F0B3FEBA60306D139BC`;
the final expanded media test is
`BCD2D9158A432B7217A1CF0D62D58F876973DFF8A04F28904A7C77C8F09ED56F`.
Both original upload/delete repros again produced `[null, null]` outcomes. Each
audit run has its own completed log, exit-code file, copied XML and JSON summary;
the final manifest and both child JAR logs are stored with final evidence.

Audit disposition: the identified coverage/documentation gaps are closed. No new
production vulnerability, DDD owner-write violation or deviation from the accepted
12.3 lock protocol was found within this review. Production behavior was not changed
during audit. At audit completion the state was uncommitted and unpublished; no
commit or push was performed as part of implementation or audit.

## Publication handoff

On 21 October 2026 the coordinator separately authorized committing these five
files and publishing only `HEAD:refs/heads/codex/migration-12-3-media-lock-order`
to the verified origin push URL `https://github.com/skuratov-sa/model-store.git`.
The parent is `efbda16ca3973baa5b1be69a026174e29de6f525`; accepted dependencies
12/12.1/12.2/04.5 were rechecked as ancestors. Final source/JAR hashes still matched
the successful audit manifest, and diff --check passed. The full commit SHA and
post-push remote SHA are reported in the publication message. Local/shared master
and origin/master are not publication targets; integration acceptance belongs to
the coordinator.

## Limits

There is no automatic retry on association churn; callers receive a safe existing
denial and may issue a new request. Arbitrary callers must acquire prior locks in
the documented participant -> order/product -> image order. No claim is made for
unmigrated reactive writers that do not follow that protocol. Existing S3/DB
compensation limitations from task 12 remain; no distributed transaction is added.
Full project testing and migration 29.1/25 acceptance belong to their own scopes.
