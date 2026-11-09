# Migration 28 — reviews and seller rating

Stage 1 implementation and verification complete, 09 November 2026. Separate
requested audit completed the same day (see final section). No commit, push,
master change or plan checkbox update.

## Initial inventory and exact scope (09 November 2026)

Base/HEAD: `68247f3df439923a431300e7c39c175357e8d225`, verified before edits.
Managed worktree: `C:/Users/skura/.codex/worktrees/b52b/model-store`.
Own branch: `codex/migration-28-reputation`, created explicitly from this local
accepted SHA, not origin/master. Dependencies 25 (`e24ac524`), 11, accepted owner
contracts 12.2/12.3 are present. No agents spawned.

Exact planned new files:

- `src/main/kotlin/com/model_store/modern/reputation/domain/Review.kt`
- `src/main/kotlin/com/model_store/modern/reputation/application/ReviewPorts.kt`
- `src/main/kotlin/com/model_store/modern/reputation/application/Reviews.kt`
- `src/main/kotlin/com/model_store/modern/reputation/infrastructure/JpaReviewTransaction.kt`
- `src/main/kotlin/com/model_store/modern/reputation/infrastructure/JdbcReviewStore.kt`
- `src/main/kotlin/com/model_store/modern/reputation/infrastructure/JdbcReviewRead.kt`
- `src/main/kotlin/com/model_store/modern/reputation/api/ReviewController.kt`
- `src/main/kotlin/com/model_store/modern/reputation/api/ReviewErrors.kt`
- `src/main/kotlin/com/model_store/modern/ordering/lifecycle/application/ReviewOrderEligibility.kt`
- `src/main/kotlin/com/model_store/modern/ordering/lifecycle/infrastructure/JdbcReviewOrderEligibility.kt`
- `src/test/kotlin/com/model_store/modern/reputation/ReviewHttpTest.kt`
- `src/test/kotlin/com/model_store/modern/reputation/ReviewConcurrencyTest.kt`
- `MIGRATION_28_REPORT.md`

The coordinator explicitly approved the two **new** ordering owner files in a
follow-up in this chat, with participant ASC -> order -> rating -> review locking.
Existing lifecycle/case files remain unchanged. Automatic approval review rejected
an earlier attempted cross-chat proposal message because it did not find explicit
human authorization for messaging. No retry or workaround was used; approval then
arrived as an incoming coordinator message.

Read actual Java ReviewController, ReviewServiceImpl, SellerRatingServiceImpl,
ReviewRepository, mapper and request/response/base models, ParticipantService and
name SQL, ImageService/ImageRepository main-image ordering, WebSecurityConfig and
AgentAccountAccessFilter. Read full migration plan and accepted lifecycle25,
media12.2/12.3 reports and actual lifecycle locks/JPA guard and product/profile
rating readers. Schema: V1.0 review/seller_rating; V1.2 rating trigger; V1.16 external
product orders; V1.21 removes product foreign key while keeping historic IDs.

Legacy: all /reviews routes require authentication, with no pagination parameters.
Create rejects existing (order,reviewer) first, hides foreign/missing orders as
404 ORDER_NOT_FOUND, accepts COMPLETED/FAILED only; rating 1..5, comment optional.
Delete is an idempotent no-op for absent/foreign review, including ADMIN.
Seller list is created_at DESC; product list uses unknown name fallback. Main
avatar is the oldest ACTIVE PARTICIPANT image. No new account-status policy.
V1.2 is the sole rating writer: round AVG to two decimals, COUNT includes all
reviews (nullable stored rating ignored by AVG), delete rating row after final
review. Profile missing rating remains null; product projections coalesce to zero.

## Transaction and owner contract

Reputation writes only review; existing DB trigger writes seller_rating in that
same transaction. No participant rewrite, order write, JPA association, or foreign
domain/infrastructure import in reputation. Ordering application port exposes only
IDs and status string. Observe all parties, include historic review reviewer/seller
for deletion, lock distinct participant IDs ASC using accepted LifecycleStore
capability, lock order FOR UPDATE, recheck customer/seller/product association.
Never acquire a newly discovered participant after this frontier. Recheck status
for creation only; delete remains permitted after subsequent order state changes.
Then take reputation's two-int advisory namespace 28 seller lock, recheck duplicate
or lock/recheck review, and mutate. Hash collisions serialize extra sellers safely;
this namespace is separate from existing single-bigint advisory locks. Participant
locks also cover implicit FK locks in INSERT/trigger and serialize seller writers.
This protocol covers modern callers; no stronger guarantee is claimed for raw
legacy/reactive/direct SQL writers that do not follow it.

Both mutation adapters reject missing/read-only/nonmatching JPA resources before
locking or writing. They require the expected joined EMF and bound DataSource,
without starting/replacing/committing transactions or retrying. No Flyway/schema,
Java/reactive, shared/build/config, Docker, plan or user files changed.

## Implementation compatibility findings

The existing shared JWT decoder authenticates before the controller. Reputation
does not add a USER/ADMIN-only or ACCESS-only rule: the actual legacy routes accept
an ordinary participant with another signed role or `agent_access`, and an ordinary
BLOCKED participant's already issued token. Legacy AgentAccountAccessFilter does
reject an `is_agent` actor on every reviews route, including invalid POST bodies,
with an empty 403. Reputation reproduces that existing restriction using a scalar
is_agent read and an API model-attribute guard before body validation; the use case
also guards direct callers. ADMIN never bypasses author/customer checks. No new
ACTIVE, seller status, role revocation or token parsing policy was introduced.
Malformed-claim/token compatibility remains governed by accepted shared security.

Actual legacy null reviewer full_name produces an empty seller review list rather
than an "unknown" row; the seller projection preserves this suppression, while
the product projection uses the existing "unknown" fallback. Stored nullable
ratings map to primitive DTO zero. PostgreSQL AVG ignores null but COUNT still
counts its row; an all-null aggregate is 0.00. Both HTTP profiles test this.

Jackson's missing primitive constructor argument initially produced BAD_REQUEST
for `{}` in modern instead of legacy VALIDATION_ERROR. The transport input now
uses ordinary bean properties and a local null-skip for rating; absent/null rating
stays zero and is rejected by the same Min/Max validation. No common mapper config
was edited. Null/empty comments are kept, 500-character comments accepted, and a
501-character SQL length failure maps to actual legacy 500 INTERNAL_ERROR with
null details and no committed review/rating.

## Completed intermediate checks (historical)

All commands use JDK `C:/Users/skura/.jdks/temurin-21.0.11`, offline/no-daemon
Gradle and `C:/Users/skura/.gradle`, with JAVA_TOOL_OPTIONS containing only the two
test RSA key paths. Private embedded PostgreSQL, mocked S3/SMTP for Spring HTTP;
no production keys, DB, Docker, or external services. Initial restricted wrapper
access failed before Gradle because its cache lock was outside writable roots;
the same local-cache operation was then approved. No dependency network fetch.

- `http-attempt1`: wrapper exit 1, no tests (cache access).
- `http-attempt2`: exit 1; 8 tests failed at fixture setup (required address/transfer
  missing), not production behavior. Completed XML copied before the next run.
- `attempt3`: exit 1; 15 tests, 6 failures. Product fixture reused a unique seller/name;
  duplicate race observer counted only direct waiters rather than the full tuple
  wait chain, and modern missing-rating input produced BAD_REQUEST. These fixture /
  observer / transport issues were corrected. Cleanup now releases holder before
  awaiting workers; the observer uses recursive pg_blocking_pids, bounded waits.
- `attempt4`: exit 1; 15 tests, 1 failure, remaining primitive-constructor validation.
- `attempt5`: exit 0; **17 tests, 0 failures/errors/skips**, own real RSA HTTP
  legacy/modern plus concurrency/rollback/readers. Historical source version:
  afterwards tests were strengthened with real lifecycle completion, observed
  Future outcomes, foreign EMF rejection, held replacement-owner non-following,
  pg_stat snapshot clearing, and nullable-rating HTTP cases.

Logs/exit files and completed per-attempt XML are in `build/migration-28/`.
These results are not represented as final-source success; current-source results
are recorded separately below. XML was read only after each process completed.

## Final verification inputs

Stage 1 source manifest: `build/migration-28/final-source-hashes.json` (12 Kotlin
files, all own files and exactly the two approved new owner files). HEAD preserved
in `final-head.txt`. No merge/rebase to the coordinator's newer plan-only master.
Source hashes rechecked unchanged during final regression.

Separate `bootJar` completed exit **0**, log `final-bootJar.log` / exit file.
Artifact SHA-256: `50c89205e8a0404336b0bacb9ac5b53178c706ff9f5d3eae3460897cc07c2b32`.
Artifact manifest: `final-artifact-hash.json`. Final test command sets
`RUN_BOOT_JAR_SMOKE=true` and selects reputation, JdbcProductDetailReadPortTest,
ParticipantJpaIntegrationTest, OrderLifecycle*HttpTest, OrderImageEvidence*Test,
MediaLockOrderIntegrationTest and BootJarDualModeSmokeTest. The accepted smoke
freezes this one artifact and runs both profiles with isolated PostgreSQL and
loopback local S3/SMTP; it is a process smoke, while route-specific review
compatibility is exercised through actual Spring HTTP in the own tests.

## Stage 1 final-source results and stop boundary

`final-regression.log`: **exit 0, BUILD SUCCESSFUL in 4m40s**. Completed XML copied
to `build/migration-28/final-results/` only after process completion; class counters
saved in `final-summary.json`. **93 tests in 12 classes, 0 failures, 0 errors,
0 skipped**:

| Class | Tests |
| --- | ---: |
| ReviewLegacyHttpTest | 6 |
| ReviewModernHttpTest | 6 |
| ReviewConcurrencyTest | 7 |
| JdbcProductDetailReadPortTest | 4 |
| ParticipantJpaIntegrationTest | 10 |
| OrderLifecycleLegacyHttpTest | 9 |
| OrderLifecycleModernHttpTest | 21 |
| OrderImageEvidenceTest | 8 |
| OrderImageEvidenceIntegrationTest | 4 |
| OrderImageEvidenceLegacyContractTest | 2 |
| MediaLockOrderIntegrationTest | 15 |
| BootJarDualModeSmokeTest | 1 |

The 19 own tests verify actual RSA-signed HTTP responses in both profiles: complete
review JSON fields/order/timestamps, rating 1 and 5 / rounded 3.67, nullable stored
rating -> zero / aggregate 0.00 with count 1, empty final aggregate deletion,
oldest ACTIVE avatar, null full_name suppression, comments null/empty/500/501,
missing/null order/rating validation, every nonterminal status, FAILED acceptance,
foreign/missing order, duplicate error precedence, foreign ADMIN deletion no-op,
own ADMIN create/delete, guests and refresh rejection, arbitrary signed role and
ordinary agent_access, existing BLOCKED ordinary token and bot empty-403 behavior,
historic product ID after product deletion, and ignored pagination parameters.

Deterministic concurrency observes actual PostgreSQL blocking graphs rather than
assuming overlap from sleeps: two creates for one seller preserve 3.00 / count 2;
duplicate requests yield exactly one Long and one typed ALREADY_EXISTS; create vs
delete leaves only the surviving order's 5.00 / count 1. An actual accepted
lifecycle completion holds participant locks, obtains the next participant/order
without a reverse cycle while review waits, then commits and allows the review;
the Future result is checked, not merely executor shutdown. Foreign-owner
replacement is held independently while a stale owner snapshot returns null
without following that participant. Historical review party IDs are locked before
deletion. Creation/deletion rollback restores both review and rating, and a caller
JPA txid remains unchanged. No-JPA, read-only, JDBC-only and foreign EMF guards
are tested before writes; the owner port separately rejects missing/foreign JPA.
Existing profile/product readers are called unchanged after create, nullable
rating and final delete to verify null-versus-zero conventions and counts.

Actual process smoke ran, not skipped. Its frozen artifact hash is exactly
`50c89205e8a0404336b0bacb9ac5b53178c706ff9f5d3eae3460897cc07c2b32`, identical to
the pre-test build manifest and the final workspace JAR. Both child process logs
were copied to `build/migration-28/migration-20-bootjar-{legacy,modern}.log`.
Smoke XML records successful S3/SMTP startup, OpenAPI/security and eight negative
JSON cases in each profile and both child JVMs stopped. This does not claim that
every reviews route was tested in a standalone JAR: reviews contract tests use
real HTTP Spring applications against their private Flyway DB.

Two read-only thread diagnostics (`thread-6692*.txt`) confirm the long final run
progressed from embedded media PostgreSQL initialization to the actual JAR smoke;
no test was killed, duplicated, skipped or made green by retrying a live run.
Own final source hashes remain unchanged from the pre-build manifest. Original
tracked files remain unchanged; exactly the 13 new files listed above are the
deliverable. `git diff --check` passes. HEAD/base and branch are unchanged.

Limitations: the full project suite was not run. Guarantees apply to the modern
transaction/lock protocol; unsynchronized legacy/reactive/SQL writers and raw
order/participant cascade deletes may retain the legacy aggregate race. No schema
constraint or trigger rewrite was added. Advisory hash collisions may serialize
unrelated seller IDs. No automatic retry follows changed association frontiers.
No new role/status policy or foreign owner write was added. General malformed JWT
claim behavior is the accepted shared security contract, outside this task.

No actual context-compaction command or context-window usage counter is exposed
in this chat. No summary has been represented as real compression. Per the stage
instructions, implementation stops here: coordinator separately requests the
compression/unavailability step, then a fresh independent audit, then a local
commit. No audit, commit, push, remote update, master modification, plan checkbox,
next migration task or subagent was performed as part of this stage.

## Separate security / plan audit — 09 November 2026

After the implementation report, the coordinator separately requested genuine
context compression. This chat explicitly confirmed that neither a real
compression command nor a context-window usage counter is available, without
representing a summary as compression. Only then was this fresh audit requested.

The audit reread all 13 deliverable files in full, actual legacy controller,
ReviewServiceImpl / SellerRatingServiceImpl, mapper, repositories, request and
response/base DTOs, real V1.0/V1.2/V1.16/V1.21 schema and trigger, all later
review/rating migration references, accepted lifecycle JPA guard/participant/order
locks and command frontier, media ORDER/PRODUCT locking, profile/product readers,
JWT/access filter and name/avatar enrichers. It also rechecked the saved stage 1
source/artifact manifests, exit codes and completed 93-test / actual-JAR evidence.
No report-only inference replaced inspection of the implementation.

### Findings and fixes

1. **Concurrent observer had an avoidable false-positive gap.** The original
   recursive blocker observer counted descendants of a known holder PID, but did
   not assert the two worker identities or their active SQL. Each worker now
   captures its actual backend PID inside its caller writable JPA transaction
   before the command. Every checkpoint requires those exact distinct PIDs to be
   descendants of the exact holder in `pg_blocking_pids` and to be executing
   `SELECT id FROM participant WHERE id=$1 FOR UPDATE`. It records PID/SQL evidence
   in XML output. Unrelated waiters cannot satisfy the assertion. Polling refreshes
   PostgreSQL diagnostic snapshots; it never retries an application operation.
2. **Reverse-ID / historic-reviewer / media interaction coverage was weaker than
   the lock contract.** The seller race now executes with seller both below and
   above its buyers' IDs. The historical deletion fixture changes both current
   order customer and seller, holds the old reviewer's participant independently,
   proves the real deletion is waiting on that exact backend, and obtains the
   order `FOR UPDATE NOWAIT` while the reviewer is still held. The original stage 1
   historical seller hold is retained as saved passing evidence; the new fixture
   additionally proves the historic reviewer is included before the order frontier.
   The lifecycle overlap now calls the real accepted media ORDER insert port
   while review is blocked, asserts one inserted image, then completes through the
   real lifecycle use case and verifies the review Future and final rating. This
   extends the accepted cross-context race, without editing foreign code.
3. **New worker wrapper initially caught a duplicate inside an outer JPA.** The
   first audit test run exposed an UnexpectedRollbackException: the strengthened
   PID-capturing helper adds a caller JPA, so catching an expected ReviewFailure
   inside it hid the typed error behind rollback-only completion. The test now
   observes that specific ReviewFailure from the Future only after the whole
   transaction rolls back. Any other ExecutionException is rethrown. There is no
   catch-all, 40P01 allowance, application retry or skip. Production was unchanged.

No new production security, IDOR, owner-write, transaction or legacy contract
defect was identified in this scoped audit. The only changed deliverables during
audit are `src/test/kotlin/com/model_store/modern/reputation/ReviewConcurrencyTest.kt`
and this report. All 10 production Kotlin files and the HTTP test file are
byte-identical to the successful stage 1 inputs. The complete final deliverable
list remains exactly the 13 new files in the initial scope section; no additional
file, existing ordering/case file, Java, Flyway, build/shared/config or plan changed.

### Reviewed invariants and boundaries

- Create derives reviewer from the verified Actor, hides foreign/missing orders,
  checks duplicate with legacy precedence, then checks the locked current customer
  and COMPLETED/FAILED state. Rating transport validation precedes the use case;
  no client seller/reviewer/product IDs can replace the owner snapshot.
- Delete returns empty success for unknown/previously deleted/foreign IDs; ADMIN
  does not bypass ownership. It captures order and historical review parties
  before locking, includes all of them in one distinct ascending participant set,
  then locks/rechecks the current order association. It does not follow changed
  customer/seller/product IDs, review order/party changes or a new participant.
  Only an unchanged locked review can be removed, with no status restriction.
- For both writes the order is participants ASC -> order -> seller advisory ->
  review mutation. Same-seller commands are already serialized by their seller
  participant before either order lock; separate orders do not create a reversed
  order/advisory cycle. Delete includes historical seller for the trigger and
  historical reviewer for its review association. The owner port uses accepted
  ordering lock capabilities but exposes only scalar IDs/status. No foreign
  domain/store/entity is imported by reputation production.
- `review` has order/reviewer/seller foreign keys; V1.21 removed its product FK.
  Trigger INSERT/UPSERT on seller_rating also references participant. All those
  participants and the order are held before review mutation. At the configured
  PostgreSQL READ COMMITTED isolation, the later INSERT/DELETE statement and
  trigger aggregate run after the waiting frontier with a fresh snapshot. The
  existing trigger is the only aggregate updater; no duplicate SQL/JPA updater,
  SQL write to another owner, separate commit or REQUIRES_NEW is present.
- Missing, read-only, JDBC-only or wrong-EMF transactions are refused by actual
  resource guards before mutation/locks; matching caller JPA uses its one txid.
  Domain contains no Spring/JPA/HTTP. Own transport DTOs preserve primitive null
  rating behavior, nullable comment/avatar/time and seller-name suppression.
  No new account-status/role/token-subtype restriction or author privilege added.
  ROUND/AVG/COUNT and final rating-row removal remain the actual V1.2 behavior.

### Audit checks and current evidence

Only the changed meaningful class was run, using the same JDK/cache/offline /
test-key-only settings and private Flyway PostgreSQL with mocked S3/SMTP:
`gradlew.bat --offline --no-daemon test --tests '*ReviewConcurrencyTest'`.

| Run | Result | Evidence under `build/migration-28/audit/` |
| --- | --- | --- |
| First strengthened test helper | exit 1; 7 tests, 1 failure (new duplicate helper's rollback-only completion), 0 errors/skips | `attempt1.log`, `attempt1.exit`, `attempt1-results/` |
| Final corrected helper | **exit 0; BUILD SUCCESSFUL in 48s; 7 tests, 0 failures/errors/skips** | `verification.log`, `verification.exit`, `final-results/`, `final-summary.json` |

Both processes finished before their XML was read/copied. Current source manifest:
`audit/source-hashes.json`; unchanged inputs and unchanged JAR were verified after
the final run. Final concurrency test SHA-256:
`eb0d94f95bd67f75aa8822c95da879ecbf45b3a1bf58e0e5240425833a9c0c33`.
Final XML logs six exact blocker/worker participant-SQL checkpoints. All Future
outcomes, database row counts, rounded averages and rollback restoration are
asserted. PostgreSQL deadlocks/unexpected errors cannot be counted as success.

The production artifact remains
`50c89205e8a0404336b0bacb9ac5b53178c706ff9f5d3eae3460897cc07c2b32`.
The prior **93 successful unique tests / 12 classes** and actual frozen one-JAR
legacy/modern smoke remain valid for unchanged production and unchanged HTTP /
neighbor tests. The latest seven replace the earlier seven concurrent cases;
they are not added as 100 unique tests. No redundant complete suite, bootJar or
JAR smoke rerun was performed after a test-only change. Current profile/product
reader/nullable aggregate checks were included again in the final seven.

Audit disposition: the identified proof/coverage gaps are closed. The prior
limits remain: full suite not run, no mixed unsynchronized reactive/SQL writer or
cascade-writer guarantee, no stronger-isolation/snapshot retry guarantee, bounded
changed-association refusal and possible harmless advisory hash serialization.
Media evidence is scoped to accepted modern lock paths and the tested overlap,
not arbitrary unmigrated writers or every possible media schedule.

`git diff --check` passes; original tracked files remain unchanged. Base/HEAD is
still `68247f3df439923a431300e7c39c175357e8d225`, branch
`codex/migration-28-reputation`. Audit stops here without commit, push, remote
update, master/plan change, new task or subagent. Local commit requires the next
separate coordinator instruction.
