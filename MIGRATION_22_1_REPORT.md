# 22.1 implementation and fresh audit report — 18.11.2026

## Base and authorized file/entry-point manifest (recorded before implementation)

- Local HEAD: bf901202ceb884aadaad0a008566ce89dab6641e; clean detached managed worktree on arrival.
- Branch: codex/migration-22-1-current-admin, created from that HEAD (no origin operation).
- Accepted dependencies present: 313dc52 (22), 8a989e5 (16.2 CurrentAdminLock), 535793e (tracked JavaReleaseOrderContract).
- Production edits confined to:
  - `src/main/kotlin/com/model_store/modern/catalog/giveaway/admin/application/AdminGiveaways.kt`: history/details/update.
  - `src/main/kotlin/com/model_store/modern/catalog/giveaway/admin/application/CreateGiveaway.kt`: create.
  - `src/main/kotlin/com/model_store/modern/catalog/giveaway/admin/application/UpdateGiveawayProduct.kt`: giveaway transitions/update.
  - `src/main/kotlin/com/model_store/modern/catalog/giveaway/admin/application/GiveawayAdminAccess.kt`: new narrow authorization helper.
  - `src/main/kotlin/com/model_store/modern/catalog/giveaway/admin/api/AdminGiveawayController.kt`: verified caller mapping, scoped prebinding gate.
  - `src/main/kotlin/com/model_store/modern/catalog/product/command/application/ProductCommands.kt`: ADMIN create/update only; expected transaction boundary.
  - `src/main/kotlin/com/model_store/modern/catalog/product/command/api/ProductCommandController.kt`: ADMIN create/update prebinding and caller checks.
- New tests only, in giveaway admin test package; no existing test edits.
- Subsequently authorized narrow scope delta (coordinator, after observed regression-01): `src/test/kotlin/com/model_store/modern/media/image/command/OrderImageEvidenceIntegrationTest.kt`, only the obsolete29 Flyway rows expectation becomes30, plus exact successful V1.29/checksum assertion. This addition was recorded before editing that existing test. All other assertions/fixtures and production media remain unchanged. Total scope becomes twelve files including report.
- This report. Shared plan, identity/shared/legacy/Java/schema/build/config/key files excluded.

## Evidence policy and current state

The original R03 before-fix details/update runtime reproductions belong to task32. History, create/transitions and generic ADMIN privileges initially were source-reviewed gaps. Our fresh final tests now verify refusal of stale actual-login ACCESS for all of these entry points, including contention after an early current-state read. We did not perform a separate before-fix modern baseline run. No previous JAR or Flyway count is reused as current evidence.

At the initial source handoff heavy checks were withheld while another task held the slot. Coordinator subsequently explicitly granted the sole slot to22.1. The completed implementation runs below replace the initial pending-check state. A separate context-step confirmed that real compression command/counter were unavailable; no textual summary was passed off as compression. Coordinator then separately assigned the fresh audit recorded below. Implementation remains unaccepted and uncommitted. No push/fetch/pull/deploy, no other chats/agents/messages, no user key inspection.

## Implemented behavior

- GiveawayAdminAccess validates positive ADMIN/nondelegated caller and invokes the accepted identity CurrentAdminLock. Only CurrentAdminForbidden maps to catalog ACCESS_DENIED; transaction/lock errors propagate.
- History/details/settings/create/product transitions call it as their first application operation inside explicit writable `transactionManager` boundaries. Identity caller lock precedes dictionary, product and media writes and remains owned by that transaction.
- Generic ProductCommands create/update take CurrentAdminLock whenever using ADMIN privileges, before eligibility/store access. USER branches receive no new current-admin/status requirement.
- MVC prebinding interceptor matches only AdminGiveawayController and ADMIN calls to ProductCommandController.create/update. AgentDirectoryQuery supplies an early current-state snapshot; it is never the final authorization. Malformed query/path/body cannot mask revoked ADMIN access with a binding error. The operation still locks and checks the fresh tuple, including after a concurrent revoke commits.
- Controller-to-GiveawayCaller mapping explicitly treats any token type other than ACCESS as delegated. The existing two-value shared Actor enum and shared decoder are unchanged. Direct GiveawayCaller scalar checks reject agentAccess=true. Product ADMIN create/update separately require ACCESS and a positive ID.
- ProductCommandController's shared requireActor, delete/extend and AdminProductStatusController are unchanged. ProductCommands.changeStatus/delete/extend are unchanged. Schedule/overlap/status/owner/category/image algorithms are unchanged.
- Seven production files total (six existing plus one new helper), three new test sources, the specifically authorized existing media test delta, this report: twelve files. No further scope expansion.

## New tests (executed)

`src/test/kotlin/com/model_store/modern/catalog/giveaway/admin/CurrentGiveawayAdminContract.kt`:
generated ephemeral RSA fixture and reusable real TCP HTTP contract. Real login issues ACCESS before role/status/is_agent revocation. Covers history/details/settings, POST giveaway, PUT entering/leaving GIVEAWAY, generic ADMIN create/update, malformed ADMIN request prebinding, unchanged snapshots, active positive flows, foreign/unknown owners, issued agent USER separately from synthetic signed agent ADMIN, missing caller and nonpositive caller, refresh/guest rejection and ordinary USER with WAITING_VERIFY after login.

`src/test/kotlin/com/model_store/modern/catalog/giveaway/admin/CurrentGiveawayAdminAuthorizationTest.kt`:
nine JUnit tests, private embedded PostgreSQL and real random-port modern HTTP. Adds late category/image rollback (including image claimed before category failure); direct scalar validation and guard-error propagation; all seven application entry points under read-only/JDBC-only transactions; raw entry points without transaction and with foreign EMF/DS guard resources, asserting no catalog interactions. Concurrency cases assert PostgreSQL blocker PID, waiter PID, SQL and pg_blocking_pids, rather than treating a delay as proof: revoke-first role/status/is_agent for seven operation routes; action-first at dictionary, giveaway product and generic product frontiers; history/details retain locks until enclosing transaction completes. Waits are bounded and owned resources close in finally/use.

`src/test/kotlin/com/model_store/modern/catalog/giveaway/admin/CurrentGiveawayAdminBootJarTest.kt`:
opt-in RUN_BOOT_JAR_SMOKE test snapshots one actual built executable JAR, records its SHA-256, starts default legacy then modern against private PostgreSQL/S3/SMTP, records fresh Flyway version/checksum manifest, uses generated local RSA keys and isolated child environment, asserts legacy stale-role giveaway behavior, then the modern revoke and positive contracts. No existing RSA resource is opened by these new tests; no JWT is printed. JAR test is intentionally skipped unless explicitly enabled after bootJar.

Follow-up environment preparation was completed before the slot: every dotted Gradle property is quoted as a complete PowerShell argument; JVM options remain a single quoted argument. New Spring test and actual-JAR child explicitly set application/security/web/http/netty loggers to WARN, overriding existing application DEBUG settings without editing production configuration. JAVA_TOOL_OPTIONS also suppresses those loggers for the test runner and existing regressions. Completed artifact inspection is recorded below.

## Static verification and evidence manifest

- `git diff --check`: exit0. This checks whitespace, not compilation/security/runtime.
- `git status --porcelain=v1`: only the six enumerated existing production files plus helper, three new test sources, the explicitly approved existing media test and this report.
- `git merge-base --is-ancestor` for 313dc52, 8a989e5 and535793e: exit0; full object IDs:
  - 313dc520de0ab2c0ae2ce7c48b706411e9b03f7d
  - 8a989e588da981d85b6823274b6a5916cdb35f42
  - 535793ee29ca7b9c6809f2c1880104a21ba9ad7a
- `git ls-files --error-unmatch src/test/kotlin/com/model_store/modern/shared/JavaReleaseOrderContract.kt`: exit0.
- Runtime evidence is under `build/migration-22-1`, with original failed and successful runs retained independently. Old task22/task32 evidence is not relabelled as our run.

## Completed verification and preserved observed failures

All runs used local offline/no-daemon Gradle, one worker and in-process Kotlin compiler, Gradle heap1536m/metaspace512m; JDK21 was explicitly selected after the initial environment failure. Test worker default heap512m; actual JAR children heap512m. Both fixture paths and WARN security/web/http logging were passed through JAVA_TOOL_OPTIONS. No production services were used.

| Evidence subdirectory | Observed completed exit | XML tests / failures / errors / skipped | Result |
| --- | ---: | --- | --- |
| `compile-01` | 1 | no test execution | Initial restricted cache-lock access failed before compilation; raw log preserved. Default PATH Java was8; subsequent runs explicitly used installed Temurin21 and approved Gradle-cache access. |
| `compile-02` | 0 | no test execution | Clean compileTestKotlin; no source drift. |
| `own-01` | 1 | 9 / 1 / 0 / 0 | Eight passed. New positive test incorrectly demanded application ACCESS_DENIED for real issued agent USER rejected earlier by authority filter. Fixed only new test: exact403 at authority gate, exact ACCESS_DENIED for application refusals. Also changed nonpositive JWT caller expectation to decoder401. |
| `own-positive-02` | 0 | 1 / 0 / 0 / 0 | Point repeat of the corrected new positive contract. |
| `regression-01` | 1 | 132 / 2 / 0 / 1 | 129 passed. ModernAdminGiveawayRealHttpTest exposed changed USER-create error text; OrderImageEvidenceIntegrationTest exposed obsolete29 versus actual30 schema assertion. Optional JAR test skipped here, later executed explicitly. |
| `parity-fix-02` | 0 | 1 / 0 / 0 / 0 | Helper now preserves operation-specific existing denial messages; existing parity test unchanged. |
| `final-targeted-01` | 0 | 14 / 0 / 0 / 0 | Final own authorization/HTTP/concurrency9 + existing modern giveaway parity1 + authorized existing media integration4. |
| `bootjar-build-01` | 0 | no test execution | bootJar from final production source. |
| `actual-jar-01` | 0 | 1 / 0 / 0 / 0 | One frozen actual executable JAR, default legacy then modern, full new modern revoke/positive contract. |

Broad regression failures were not hidden and that run is not called green. Its two failing classes passed with the final source in `final-targeted-01`; the remaining broad green tests were not needlessly rerun. The broad selection covered giveaway admin/public/legacy-modern parity, product commands/status, media, bridge runtime/startup and guard23. In particular MediaLockOrderIntegrationTest15, ProductImageAttachmentTest4, ProductStatusAuthorizationTest10, LegacyScenarioSafetyGuardTest7, startup-failure6 and default/explicit-legacy/full-modern product runtime12 all passed in `regression-01`. No existing assertions were weakened: the authorized media delta now requires exactly30 successful versioned rows and exactly one successful V1.29 with checksum666162279. It changes no other fixtures/assertions/media code.

Each run directory retains `arguments.json`, started/completed UTC timestamps, observed `completed-exit.txt`, before/after HEAD/status/diff/source-SHA256 manifests, `raw.log`, and (for test runs) copied raw XML/binary results and HTML reports. `completed-runs.json` gives observed counts; `regression-01/xml-summary.json` and `final-targeted-01/xml-summary.json` retain class counts. Every run's source-SHA256 before/after comparison has zero differences. Final targeted tests, bootJar build and actual-JAR run share identical source manifests. The initial failures and raw artifacts have not been rewritten.

`final-targeted-01` own XML contains18 schema snapshots, each asserting30 successful rows and V1.29, plus41 PostgreSQL blocker observations. Those implementation-era tests selected waiter PID using a SQL fragment, so they do not independently prove an operation's pre-known PID or full SQL. That evidence weakness is explicitly superseded by the stricter fresh audit below, not relabelled. Actual-JAR XML has four before/after schema manifests across both modes, including V1.29/checksum666162279. No mixed-writer/load/production safety is inferred.

Frozen artifact: `build/migration-22-1/frozen-1806894582740370248/giveaway221.jar`.

SHA-256: `4f7be8f9062d132615b399a27db92a417a3748c66f979dacd16e686c4f6b921c`.

Its hash matches `build/libs/model-store-0.0.1-SNAPSHOT.jar` after execution. Same snapshot used by default-legacy PID39372 and modern PID5196; both observed stopped after completion. Separate immutable child logs are `default-legacy-raw.log` and `modern-raw.log` in the frozen directory. Actual default legacy startup and giveaway operations confirm the new modern-only helper/config do not break legacy bean graph; modern verifies both current-state revoke refusal and positive operations. Local PG/S3/SMTP and newly generated temporary RSA keys only.

Credential-fragment scan:232 preserved log/XML/HTML/TXT artifacts, zero matches for JWT-like `eyJ` prefixes with at least eight following base64url characters. New contexts and child logs explicitly suppress DEBUG security and HTTP loggers. No JWT was printed; no user RSA file was read/copied/hashed. `evidence-sha256.txt` hashes preserved artifacts (including frozen JAR, not user keys), and `final-source-sha256.txt` records final source identity.

## Reproduction command options

PowerShell, repository root; execute sequentially, one process, no build-file changes:

Coordinator's subsequent environment clarification is incorporated: both standard test fixture paths must be present in JAVA_TOOL_OPTIONS for all Gradle/test/JAR launches. New tests additionally override application key properties with their own generated temporary fixture pair (DynamicPropertySource / JAR command-line arguments); no user RSA resource is required. The isolated JAR child explicitly retains the two required JAVA_TOOL_OPTIONS settings rather than inheriting arbitrary environment variables.

```powershell
$taskPreviousJavaToolOptions = $env:JAVA_TOOL_OPTIONS
$taskPreviousJavaHome = $env:JAVA_HOME
$env:JAVA_HOME='C:/Users/skura/.jdks/temurin-21.0.11'
$env:JAVA_TOOL_OPTIONS='-Dapp.public-key-path=keys/test_public_key.pem -Dapp.private-key-path=keys/test_private_key.pem -Dlogging.level.root=WARN -Dlogging.level.com.model_store=WARN -Dlogging.level.org.springframework.security=WARN -Dlogging.level.org.springframework.web=WARN -Dlogging.level.org.springframework.web.server=WARN -Dlogging.level.org.springframework.http=WARN -Dlogging.level.reactor.netty=WARN -Dlogging.level.io.netty=WARN -Dspring.test.context.cache.maxSize=2'
try {
.\gradlew.bat compileTestKotlin --offline --no-daemon --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m' '-Pkotlin.compiler.execution.strategy=in-process'
.\gradlew.bat test --offline --no-daemon --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m' '-Pkotlin.compiler.execution.strategy=in-process' --tests '*CurrentGiveawayAdminAuthorizationTest'
.\gradlew.bat test --offline --no-daemon --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m' '-Pkotlin.compiler.execution.strategy=in-process' --tests 'com.model_store.modern.catalog.giveaway.*' --tests '*GiveawayHttpParityTest' --tests '*ProductCommandHttpTest' --tests '*ProductCommandLegacyHttpTest' --tests '*ProductStatusAuthorizationTest' --tests 'com.model_store.modern.media.*' --tests 'com.model_store.bridge.config.*'
.\gradlew.bat test --offline --no-daemon --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m' '-Pkotlin.compiler.execution.strategy=in-process' --tests '*CurrentGiveawayAdminAuthorizationTest' --tests '*ModernAdminGiveawayRealHttpTest' --tests '*OrderImageEvidenceIntegrationTest'
.\gradlew.bat bootJar --offline --no-daemon --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m' '-Pkotlin.compiler.execution.strategy=in-process'
$env:RUN_BOOT_JAR_SMOKE='true'
try {
  .\gradlew.bat test --offline --no-daemon --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m' '-Pkotlin.compiler.execution.strategy=in-process' --tests '*CurrentGiveawayAdminBootJarTest'
} finally { Remove-Item Env:RUN_BOOT_JAR_SMOKE }
} finally {
  if ($null -eq $taskPreviousJavaToolOptions) { Remove-Item Env:JAVA_TOOL_OPTIONS }
  else { $env:JAVA_TOOL_OPTIONS = $taskPreviousJavaToolOptions }
  if ($null -eq $taskPreviousJavaHome) { Remove-Item Env:JAVA_HOME }
  else { $env:JAVA_HOME = $taskPreviousJavaHome }
}
```

These show the required quoting/environment, not a request to repeat completed runs. Actual per-run selection and arguments are authoritative in each `arguments.json`; broad regression deliberately selected Kotlin/private-PG giveaway classes rather than the unrelated Java integration fixture. Do not run subsequent commands after a failure without inspecting and fixing the cause. Only the explicitly approved existing media-test schema assertions were edited.

## Limitations and handoff

Implementation and the separately assigned fresh audit below are complete; sole heavy slot is explicitly released. No active owned Gradle/JAR/PG validation remains. We do not claim a single all-green132-test final-source run: failed broad history, focused fixes and audit repeats are distinguished. No mixed-writer/production/load proof. Stop after this audit report. Local commit requires a separate coordinator instruction. No checkbox, commit, push or deployment.

## Separately assigned fresh security / plan / evidence audit

Freshly reread all twelve agreed files, plan22.1 and18.11 scope additions, verified Actor/decoder, CurrentAdminLock/JdbcCurrentAdminLock, AgentDirectoryQuery, JPA persistence configuration, actual giveaway/product JDBC stores, product media attachment/translation and legacy giveaway service behavior. No production change was necessary during this audit. Audit edits are confined to the new CurrentGiveawayAdminAuthorizationTest and this report; all twelve-file scope boundaries remain intact.

### Security and transaction review

- All three AdminGiveawayController actions map only a verified ACCESS principal to the nondelegated caller contract. GiveawayAdminPrebinding matches exactly that handler class; its three current methods are history/details/update. For ProductCommandController it matches only create/update with ADMIN role, so it does not add current-admin requirements to USER or delete/extend/status routes. An absent/invalid principal still follows existing security. Early AgentDirectoryQuery is only a current-state read; it never substitutes for the owner lock.
- Every giveaway operation calls GiveawayAdminAccess first inside its writable named JPA transaction; generic product create/update call the same identity-owned lock for ADMIN only. Caller locking precedes dictionary/product/media access, remains until enclosing transaction completion and rechecks the database tuple after waiting. No target-owner participant lock is added after the frontier. Routing handles() is a pre-operation subtype read; the final product lock rechecks subtype/ownership and refuses a race rather than bypassing authorization. Only exact CurrentAdminForbidden is translated; lock/transaction exceptions propagate.
- Product generic application methods retain their existing trusted ID/role scalar contract; verified token type is enforced at the Actor/controller boundary. GiveawayCaller is also a trusted scalar produced by the controller; true agentAccess is rejected directly. No fakeActor, catalog identity SQL, REQUIRES_NEW, retry or manual commit was added to production. Real /auth/login ACCESS and actual issued agent USER are exercised separately from synthetic signed agent ADMIN; zero-ID/refresh are decoder401, real USER authority gate403 is distinct from application ACCESS_DENIED.
- Exact static block comparisons against HEAD confirm ProductCommands.delete/extend/changeStatus, controller delete/extend, requireActor and AdminProductStatusController unchanged. Giveaway applySettings/effective/Telegram validation companion is unchanged. Owner visibility, dictionary-before-product activation, overlaps/orders/status transitions, category/image ownership and transactional rollback remain on the accepted algorithms. New own rollback tests and the previous focused parity/media results cover their concrete exercised branches. Proof is bounded to those scenarios.
- Modern helper/config profiles remain explicit. Fresh actual-JAR execution confirms default legacy bean graph and operations, rather than inferring it solely from annotations. No identity/shared/Java/config/schema production edits were made.

### Evidence weaknesses found and corrected

The implementation test accepted TransactionException, which was too broad. The new test now requires **exact CannotCreateTransactionException**, exact message `Could not open JPA EntityManager for transaction`, an IllegalStateException cause specifically reporting an already-bound JDBC ConnectionHolder, and no deeper cause. This is the observed JDBC-only resource-binding failure on the expected JPA service boundary; it is not a generic RuntimeException or acceptable deadlock/timeout. Seven application entry points compare complete owned product/image/category state, and raw operations assert no catalog/seller/media mock interaction. Managed-port no-transaction calls require exact IllegalTransactionStateException with mandatory-propagation reason. Raw guard no-tx/foreign EMF/DS and real separately constructed foreign JPA manager/EMF require CurrentAdminTransactionRequired before catalog interaction. A stale managed ParticipantEntity versus external committed role revoke now explicitly proves the fresh owner tuple wins.

The implementation blockedBy() chose a waiter from SQL-fragment results. It was replaced by a pre-known PID protocol: a Mockito spy on the **real managed identity owner target** only observes `pg_backend_pid()` on that operation's existing transaction and then calls the real owner method. The test registers the unique caller's queue before dispatching real HTTP, obtains that worker PID before the lock wait and never chooses it from pg_stat_activity. Revoker PID is independently captured from its exact connection before UPDATE. Await succeeds only for that expected PID, the expected blocker, Lock wait type and complete SQL equality after whitespace normalization. Query parameters remain PostgreSQL $1/$2 placeholders; no LIKE, SQL fragment or first matching PID can prove success. Expected SQL includes the full participant/dictionary/giveaway-product/generic-product query at the respective frontier.

Audit-own-01 deliberately preserved two failing stricter assertions: JDBC had the concrete CannotCreateTransactionException cause rather than the initially guessed IllegalTransactionStateException; pg_stat_activity could briefly show the preceding PID query while pg_blocking_pids already reflected the subsequent lock. The final observer polls until **all** identity/blocker/full-SQL conditions agree; mismatched SQL is never accepted, and deadline expiry fails. This handles the observation race without changing authorization, adding lock retries or accepting an arbitrary exception. Real Future.get results and exact HTTP200/403 bodies / update counts are required after releasing locks; timeout, SQL40P01, SQL55P03 or ExecutionException cannot count as success. Executors must terminate, and revoke-first finally also drains its submitted Future.

### Fresh audit runs and input identity

| New evidence directory | Observed completed exit | Tests / failures / errors / skipped |
| --- | ---: | --- |
| `audit-own-01` | 1 | 9 / 2 / 0 / 0 |
| `audit-own-02` | 0 | 9 / 0 / 0 / 0 |
| `audit-actual-jar-01` | 0 | 1 / 0 / 0 / 0 |

All new run directories are separate; original295 artifact hashes still match exactly. All new runs have before/after source manifests with zero drift. Final own and JAR audit input manifests match each other. Audit changes only one test source relative to implementation final source; **all production source hashes** match the production inputs used to build the earlier JAR, whose SHA also matches. Therefore no unnecessary production rebuild was performed; the same executable was copied to new frozen evidence and actually run again.

Fresh frozen artifact: `build/migration-22-1/frozen-4595573792406080849/giveaway221.jar`, SHA-256 `4f7be8f9062d132615b399a27db92a417a3748c66f979dacd16e686c4f6b921c`. Default-legacy child PID28740 and modern child PID7640 completed and were observed stopped. Fresh own XML records41 pre-known exact PID/full-SQL/blocker observations (21 revoke-first,18 action-first graph edges,2 read-lock retention) and18 schema snapshots; fresh JAR XML records four more schema snapshots across both modes. Each requires the current30-row schema with V1.29; raw manifests retain the exact checksums.

### Unique checks, provenance and limits

Recomputed by `(fully qualified class, test case name)` rather than summing repeats: **132 unique test identities across28 classes**. The broad132 run remains historical exit1 /2 failures /1 skipped. The final14 implementation tests are own9 + modern giveaway parity1 + media4; actual JAR adds one distinct test. Audit9 and audit JAR1 repeat those same identities and add zero unique tests. The117 other identities in24 classes retain earlier green **branch-specific** evidence, not a claim of a final-source all-green suite.

The broad runtime manifest is not identical to final source: three production files changed after broad execution (helper parameterized denial message and the CreateGiveaway/UpdateGiveawayProduct call-site messages), plus JAR-test artifact preservation and the explicitly approved media schema assertion. None changes owner-lock logic or successful owner/schedule/media branches. The exact message/body mismatch was rerun with the final helper in modern parity; own and JAR scenarios ran with final production as well. Other earlier evidence is reused only for unchanged bodies/branches such as delete/extend/changeStatus, media/store logic, public/legacy behavior and their previously asserted codes/states. It does not establish a newly changed error message or independently strengthen an older PID assertion. During this audit production changed zero files; only the own test was strengthened, so those neighboring branches needed no broad repeat.

| Class (unique short name; full names in JSON) | Unique tests | Latest applicable run |
| --- | ---: | --- |
| LegacyScenarioSafetyGuardTest | 7 | regression-01 |
| ProductGiveawayDefaultHttpTest | 4 | regression-01 |
| ProductGiveawayExplicitLegacyHttpTest | 4 | regression-01 |
| ProductGiveawayFullModernHttpTest | 4 | regression-01 |
| ProductGiveawayStartupFailureTest | 6 | regression-01 |
| AdminGiveawayHttpTest | 13 | regression-01 |
| CurrentGiveawayAdminAuthorizationTest | 9 | audit-own-02 |
| CurrentGiveawayAdminBootJarTest | 1 | audit-actual-jar-01 |
| LegacyAdminGiveawayRealHttpTest | 1 | regression-01 |
| ModernAdminGiveawayRealHttpTest | 1 | final-targeted-01 |
| PublicGiveawayHttpTest | 1 | regression-01 |
| PublicGiveawayTest | 2 | regression-01 |
| ProductCommandHttpTest | 7 | regression-01 |
| ProductCommandLegacyHttpTest | 1 | regression-01 |
| ProductStatusAuthorizationTest | 10 | regression-01 |
| ImageCommandControllerTest | 1 | regression-01 |
| ImageCommandsTest | 7 | regression-01 |
| MediaLockOrderIntegrationTest | 15 | regression-01 |
| OrderImageEvidenceIntegrationTest | 4 | final-targeted-01 |
| OrderImageEvidenceLegacyContractTest | 2 | regression-01 |
| OrderImageEvidenceTest | 8 | regression-01 |
| ProductImageAttachmentTest | 4 | regression-01 |
| ImageReadControllerTest | 1 | regression-01 |
| JdbcImageReadPortTest | 1 | regression-01 |
| ReadImagesTest | 7 | regression-01 |
| S3ObjectStorageTest | 7 | regression-01 |
| LegacyGiveawayHttpParityTest | 2 | regression-01 |
| ModernGiveawayHttpParityTest | 2 | regression-01 |

Audit outputs: `build/migration-22-1/audit/{source-review,verification-summary,completed-runs,unique-checks,unique-checks-by-class}.json`, `audit/final-source-sha256.txt`, and new audit artifact hash manifest. Original `evidence-sha256.txt` (295 entries) remains unchanged. Expanded privacy scan checks even a literal short `eyJ`: zero matches across295 preserved text log/XML/HTML/TXT artifacts at audit completion, including old and new artifacts; no sensitive match text was printed. This is separate from the original weaker prefix-length scan. No user RSA data was read/copied/hashed.

Audit conclusion: the identified evidence weaknesses are corrected and fresh minimal checks pass. No remaining defect found in the audited22.1 scope. This is a same-agent fresh self-audit, not an independent reviewer, full-suite, mixed-writer, load or production validation. Heavy slot released; stop pending separate local-commit authorization.

## Source SHA-256 manifest

Only the eleven scoped production/test source files are hashed below; report excluded to avoid self-reference. The initial ten-source scope was extended solely by the specifically approved existing media test. This is source provenance, separate from the runtime evidence above.
- `src/main/kotlin/com/model_store/modern/catalog/giveaway/admin/api/AdminGiveawayController.kt` — `f654d4c41ac46b4cacf00d01f6ba4f6e240734a730bf8e6eff29260fdc4a2fa7`
- `src/main/kotlin/com/model_store/modern/catalog/giveaway/admin/application/AdminGiveaways.kt` — `c3b7d849066dee22b17459b29fb7c6e44fa75e735ebbfaa3bdd1465cc27e48b1`
- `src/main/kotlin/com/model_store/modern/catalog/giveaway/admin/application/CreateGiveaway.kt` — `4d70b655064a5ad473ce30e3c5728e9bcdcd06539887d478f4342788d73223ef`
- `src/main/kotlin/com/model_store/modern/catalog/giveaway/admin/application/GiveawayAdminAccess.kt` — `e58d879e8f0b916a88a662979dae4ebd50f0ee44069760eadb1d734df5d5fe98`
- `src/main/kotlin/com/model_store/modern/catalog/giveaway/admin/application/UpdateGiveawayProduct.kt` — `9a1a2c459213c4ab886c4a08e71eaf54bf359acc6626f8c0c2de120f8aa6eafb`
- `src/main/kotlin/com/model_store/modern/catalog/product/command/api/ProductCommandController.kt` — `96f284c8fbb51d2c7ecccf259c25c3c8fee1750541e8e1b92750a326753d970b`
- `src/main/kotlin/com/model_store/modern/catalog/product/command/application/ProductCommands.kt` — `b189cbe01328d1843be65471531e0b7e5c99094a6ae27b7682d71ff856381f26`
- `src/test/kotlin/com/model_store/modern/catalog/giveaway/admin/CurrentGiveawayAdminAuthorizationTest.kt` — `560f6a3ff14232182fa38967939cf323de5f6e1158a209dde1b391f60cd0774c`
- `src/test/kotlin/com/model_store/modern/catalog/giveaway/admin/CurrentGiveawayAdminBootJarTest.kt` — `fdadf0432c148a4756b78d15a6d8394a303bf6662153ff689f98dce5e4d85b8e`
- `src/test/kotlin/com/model_store/modern/catalog/giveaway/admin/CurrentGiveawayAdminContract.kt` — `667803aa64277ee3863b96607ca5c7845edacbb1be28a4a9a98025d0aec98c67`
- `src/test/kotlin/com/model_store/modern/media/image/command/OrderImageEvidenceIntegrationTest.kt` — `77748cec3ffe1b45023a90d2709bd5e344363752dc6a7f429dd9722801cc55de`
