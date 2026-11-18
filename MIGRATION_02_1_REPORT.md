# 02.1 — current ADMIN for category commands

Implementation completed; separate context step confirmed compaction unavailable. Fresh independent audit on 18 November 2026 found test/evidence gaps and corrected the two owned tests. Production code is unchanged by the audit. The separately authorized9-check audit validation completed with observed exit0; final current evidence is described at the end. No commit or checkbox change has been made.

## Base and scope

Worktree `C:/Users/skura/.codex/worktrees/0be2/model-store`, branch `codex/migration-02-1-category-admin-current`.
Initial clean detached base was `3c3cd61d8af06b6f60596a2f79c4b806e9895fe4` on 11 November. The four own partial files were saved under `build/migration-02-1/resume-backup/` with SHA-256 manifest, the one tracked own file was restored from HEAD, and the branch fast-forwarded to local master `a4796f653bce72e1de2bce0fe20aca65d486d51c`. All four files were restored and their hashes compared with the backup; no conflicts or losses occurred. New base includes user commit `5444d05` and tracked helper `535793e` without edits by this task.

No fetch/pull/push, publication, deployment, production access, new chats or subagents. `MIGRATION_PLAN.md`, Java/reactive, Flyway, shared/identity, bridge23, domain/JPA/core category use cases, Gradle, configuration, compose and other user files are unchanged relative to the resumed base. No repository AGENTS.md was found; `C:/Users/skura/.codex/AGENTS.md` was empty. Plan 02.1 and accepted owner-port/security/bridge/runtime contracts were read.

Owned files:

- `src/main/kotlin/com/model_store/modern/catalog/category/api/CategoryController.kt` — verified ACCESS/ADMIN/positive caller gate, guarded facade calls, existing 403/ACCESS_DENIED envelope for current-admin denial.
- `src/main/kotlin/com/model_store/modern/catalog/category/application/AdminCategoryCommands.kt` — new modern-only writable JPA facade for create/rename.
- `src/test/kotlin/com/model_store/modern/catalog/category/CategoryAdminAuthorizationTest.kt` — new isolated HTTP/transaction/concurrency/schema tests.
- `src/test/kotlin/com/model_store/modern/catalog/category/CategoryAdminBootJarTest.kt` — new frozen dual-mode actual JAR test.
- `MIGRATION_02_1_REPORT.md` — this implementation report.

No existing tests required a scope extension: CategoryPersistenceTest's fixed id=1 already refers to the ACTIVE ADMIN created by Flyway. It remains unchanged.

## Resulting contract

The original method/path/query parameters and responses are retained: POST `/admin/actions/categories?name=...&parentId=...` returns a numeric ID; PUT with categoryId/name returns an empty successful response, including the historical unknown-ID no-op. Existing reference/validation/error advice remains in force.

A controller-local prebinding hook gates only mapped POST/PUT methods. Public GET `/categories` is untouched and is verified anonymously. Direct controller calls also repeat the same gate; no request-supplied caller ID, raw JWT decoder or fake Actor was introduced. The shared decoder verifies signatures/token claims and rejects refresh, malformed and nonpositive IDs before the controller (401); a signed synthetic agent_access+ADMIN with issuedBy=admin is valid to that decoder but rejected by this controller (403), including before malformed administrative query binding. This synthetic fixture is explicitly adversarial and is not normal issuer13 output.

Both facade methods require a caller ID, reject nonpositive IDs, and open/join explicit writable `transactionManager` JPA transactions. Existing identity `CurrentAdminLock` runs before core category commands; its mandatory owner implementation checks the current locked DB tuple for ACTIVE/nonagent/ADMIN and retains the caller row lock until the same transaction commits/rolls back. The existing proxied CategoryUseCases methods join this transaction. No pre-read authorization outside the write transaction, REQUIRES_NEW, additional unguarded admin overload or participant SQL in catalog was added.

Lock order is caller participant → category write. This operation has only one participant; there are no target participant/foreign owner locks or reverse lock edges. Unknown rename still authorizes/locks the caller before becoming a no-op. Category tree/link/core command and legacy bridge behavior remains available unchanged.

Intentional modern tightening: stale ADMIN ACCESS after role/status/is_agent revoke is denied with 403/ACCESS_DENIED and no category writes. Legacy code remains unchanged: actual JAR assertions preserve its existing role/status behavior and its existing rejection of a normal token after is_agent=true.

## Verification and provenance

JDK `C:/Users/skura/.jdks/temurin-21.0.11`, Gradle cache `C:/Users/skura/.gradle`, offline/no-daemon, one build at a time, one worker, in-process Kotlin compiler, Gradle heap1536m/metaspace512m. Test limits are supplied only through an untracked init script in build: heap640m, maxParallelForks=1. Actual JAR child heap512m/metaspace256m; no repository build configuration edits. Embedded PostgreSQL only, fixture test_public_key.pem/test_private_key.pem and mock S3/mail in context tests; actual JAR uses loopback S3/SMTP fixtures and private PostgreSQL, sequential legacy/modern processes with cleared inherited credentials/environment.

Historical attempts are not final evidence:

- Initial Gradle wrapper cache access denial and metadata write denial were retried with authorized local access.
- Pre-resume XML has 7 tests, 1 failure: invalid ID expected403/actual401. The expectation was corrected, but no success from that older source/base was claimed. Preserved as `build/migration-02-1/pre-resume-failed.xml`.
- Resumed attempt1 exited1 before compilation/tests: PowerShell split the unquoted dotted `-P` parameter into a task name. Log and observed exit are `resumed-own-1.log` / `.exitcode`. Correctly quoted retry follows.

Current own run: `resumed-own-2.log`, `.exitcode`, `resumed-own-2-results/`: observed exit0, BUILD SUCCESSFUL, 8 tests/1 class, 0 failures/errors/skips. It covers:

- Actual HTTP login while DB ACTIVE/nonagent ADMIN, then role USER / BLOCKED / DELETED / WAITING_VERIFY / is_agent=true revoke: both POST/PUT deny and full category snapshots remain equal.
- Signed synthetic agent_access+ADMIN separately, USER claims, refresh/malformed/expired tokens, missing caller and invalid IDs; both methods, denied malformed admin params before binding.
- Allowed HTTP login/admin create parent+child/rename, legacy numeric/empty responses, unknown rename no-op, INVALID_REFERENCE and binding BAD_REQUEST, anonymous public tree with childs JSON.
- Existing port no-tx/JDBC-manager/read-only/foreign EMF/foreign DS guards; both facade commands reject read-only/JDBC manager; invalid caller IDs deny. Joined writable JPA and forced flush before external rollback preserve full category snapshots for both commands.
- Stale managed ADMIN entities cannot override the freshly locked database tuple in either command.
- Revoke-first and action-first for role/status/is_agent × POST/PUT. Actual login precedes revoke. Revoke-first waits on the fixture participant update and rejects after its commit. Action-first blocks category INSERT/UPDATE after caller authorization; revoker waits on that exact action backend until category commit, then the next HTTP request denies. `locks.jsonl` contains 18 observed exact SQL/PID/blocker records. No sleep-based claim of serialization.
- New schema inspection verifies V1.29 success. `schema.json`: 30 actual Flyway history rows, 162 columns, 68 constraints, 41 indexes; V1.29 checksum666162279. Source files alone are not presented as proof of applied migrations.

Current source identity: `build/migration-02-1/resumed-source-manifest.json` contains 611 source/build inputs with SHA-256. All 611 were rechecked after the completed runs: zero mismatches. No runtime or test source edits occurred between these runs.

Regression/JAR run: `resumed-regression-1.log`, `.exitcode`, `resumed-regression-1-results/`: observed exit1, 50 tests/12 classes, 46 passed and 4 failed, zero skipped. All four failures belonged to unchanged CategoryPersistenceTest: its context specifies only the public fixture key and could not instantiate RsaAgentTokenSigner because the default `keys/private_key.pem` was absent. No category assertion failed in that class; the context never became ready. This failed run is retained, not described as a green 50-test run.

Only those four checks were repeated with explicit fixture public/private paths through the untracked `persistence-fixtures.gradle` launch script. `resumed-persistence-1.log`, `.exitcode`, `resumed-persistence-1-results/`: observed exit0, BUILD SUCCESSFUL, 4 tests/1 class, zero failures/errors/skips. No existing source/test/configuration file changed. That unchanged persistence class mocks S3 and performs no mail operation; SMTP behavior is covered by loopback fixtures in the actual JAR test, not claimed for persistence itself.

The successful selected evidence therefore comprises **58 unique checks in 13 classes**: own8 + regression46 + persistence4. This is a composition of three runs, not one 58-test execution. The regression's four unsuccessful context attempts remain visible. `result-suites.json` preserves every suite count; `implementation-summary.json` records observed exits0/1/0, source identity, selected totals and schema/JAR hashes.

Successful regression classes: CategoryTreeTest3, CategoryLegacyBridgeTest2, CategoryOptInBridgeTest1, CategoryAdminBootJarTest1, LegacyScenarioSafetyGuardTest7, ProductGiveawayDefaultHttpTest4, ProductGiveawayExplicitLegacyHttpTest4, ProductGiveawayFullModernHttpTest4, ProductCommandHttpTest7, ProductStatusAuthorizationTest10 and MvcApiErrorContractTest3. The repaired launch of CategoryPersistenceTest4 verifies the original HTTP/core/link tests without modifying their fixture or assertions.

The actual dual-mode JAR class passed in the regression run. It copied/froze the artifact, started sequential child JVMs, performed real HTTP login in each profile, checked allowed create/rename, both operations after role/status/is_agent revoke, preserved legacy behavior, checked synthetic signed agent_access+ADMIN separately in modern, and anonymously read the public category tree. Both child processes and services closed successfully. JAR SHA-256 in the frozen copy, legacy evidence, modern evidence and current file agrees:

`aeb3ae656300a70dc4ba61b224a3d6d428d8ec9e96c70e77595a8349da1e453a`

`jar-legacy.json` and `jar-modern.json` each contain 30 applied Flyway rows; all versions/checksums match the context schema manifest, including V1.29. Child logs are copied to `build/migration-02-1/category021-bootjar-legacy.log` and `category021-bootjar-modern.log`. They concern test fixtures only. Final owned-file hashes and the evidence file hashes are preserved in `final-owned-manifest.json` and `final-evidence-manifest.json` respectively.

At implementation completion the process inventory showed no Java/PostgreSQL entries. No build/JAR/embedded database process remains from these checks; the exclusive heavy-test slot is released. Remaining work is the coordinator's separate context step, fresh audit and local commit instruction.

## Remaining limits and next stage

No full project suite, load test, mixed legacy/modern writer deployment or production verification is claimed. This task does not accept unrelated user Java/schema changes as a complete runtime release and does not address 04.6/22.1/32. The selected regression checks exercise the updated base with the current schema.

A context usage counter and a true compaction command are unavailable in the exposed tools. No summary has been represented as actual compaction. The separate context step was completed by confirming that unavailability. Audit validation is complete; a later commit instruction remains pending.

## Separate fresh security / plan / evidence audit — 18 November 2026

This audit reread both owned production files, both complete tests and this report; exact plan02.1; CurrentAdminLock and JdbcCurrentAdminLock; CategoryUseCases/JpaCategoryPort; Actor, real decoder/converter and security filter chains; explicit transaction selector/JPA configuration; real AuthUseCases/RsaAuthTokens issuer; ModernModeLegacyFilter, CategoryServiceBridge and guard23; accepted16.2/23 reports; stored XML/exits/schema/locks and the frozen JAR evidence. No Gradle, JAR process, PostgreSQL, test compilation or other heavy validation was started. Slot04.6 was not used.

### Production and contract assessment

No new production authorization defect or scope/DDD deviation was identified. The sole production callers of the new facade are the two existing controller methods. Neither accepts caller ID from query/body. Actor comes from the shared verified converter; no decoder copy or constructed Actor exists in the new facade. The facade has no overload omitting caller authorization. Core category commands remain trusted internal/legacy-bridge operations as expressly required by02.1; they are not an additional modern HTTP entry. ModernModeLegacyFilter excludes the old Java controller graph in modern; CategoryServiceBridge is `!modern`, and guard23 still rejects unsafe category modern opt-in in legacy. None was changed.

Error precedence was reviewed explicitly:

- Invalid/refresh/nonpositive-ID JWTs fail shared decoding with401; USER claims fail the existing admin security rule with403. Their shared security-layer body is not replaced by category advice.
- A decoder-valid signed agent_access+ADMIN fails the category controller's ACCESS check with403/ACCESS_DENIED before query binding. The model hook is controller-local and checks only its POST/PUT methods; all current such mappings are administrative. Public GET is not gated.
- A stale but verified ACCESS ADMIN passes the claims gate; malformed query binding can still return400 before application invocation. Once inputs bind, current DB authorization runs before parent validation or unknown-rename no-op, producing403/ACCESS_DENIED without a category write. No claim is made that current DB authorization precedes Spring input binding.
- Current allowed ADMIN retains the original parent validation and binding errors, numeric create result, empty rename result, and successful unknown-ID no-op. Existing error advice continues to apply to the same controller.

The two public facade methods have explicit writable `transactionManager`; their managed owner port is MANDATORY and checks actual transaction/read-only/joined expected EMF/bound expected DS. The facade invokes the separate managed core bean; there is no self-invocation transaction split. CurrentAdminForbidden reaches the existing envelope via category advice and marks the joined transaction rollback-only. Directly constructing the owner implementation does not remove its resource checks. The actual participant id/role/status/is_agent columns in recorded schema are all NOT NULL, so the boolean read does not authorize a nullable unknown flag.

Current permissions are read by JDBC from the tuple locked by SELECT FOR UPDATE, rather than the EntityManager identity map. In READ COMMITTED the select after a lock wait uses the committed current tuple. Lock is held through core identity INSERT or deferred UPDATE flush/commit. Caller participant precedes category/FK locks; there is no later participant lock, foreign target owner discovery or agent-owner port. Existing outer JPA rollback is exercised after a forced DB flush. Public tree/link/legacy behavior and all owner/shared files are unchanged. User5444d05/V1.29 are assessed only within the already selected checks, not as a release acceptance.

### Findings and prepared corrections

1. **Token fragments in fixture artifacts.** Inherited `org.springframework.web` and `.web.server` DEBUG settings logged truncated token-response map fragments: one in each actual JAR child log and18 in the own HTTP test XML. Scan of those four primary artifacts found zero complete compact JWTs, zero Bearer JWTs and zero private-key markers, but absence of token material could not honestly be claimed. Counts only are saved in `audit-historical-log-scan.json`; tokens were not printed or copied into the report. Historical logs remain unmodified. Both new tests now override both web logger levels to WARN; the JAR test also asserts no token/fragment appears in either child log. Application-wide logging configuration is unchanged and no global logging-policy fix is claimed.

2. **Duplicate tree assertion.** The own public tree test checked `childs` twice; it did not assert that the renamed name appeared in public JSON. The second assertion is restored to `renamed021`.

3. **Insufficiently strict evidence assertions.** The prior manager-negative assertion accepted any TransactionException, while SQL checks accepted prefixes and any blocker set containing a PID. The audit prepares an exact CannotCreateTransactionException assertion for the JPA/JDBC-manager resource collision, plus exact mandatory no-tx assertions through a directly constructed facade using the real managed owner port. The tightened manager expectation is not claimed observed until the queued rerun. Lock assertions now compare the entire normalized SQL, exact singleton blocker set, exact expected revoker PID and scenario/method labels. Existing Future completion checks and bounded gets remain; no early Future success is accepted.

4. **Issuer and precedence evidence.** Real HTTP login already supplied positive tokens. The tests now additionally verify id/ADMIN/access/no issuedBy from that issued token (actual decoder in the context test; payload inspection plus actual protected HTTP verification in the JAR test). Synthetic agent ADMIN remains a separately signed adversarial fixture. Current-role/status/is_agent rejection is also asserted before invalid-parent domain validation or unknown rename no-op; anonymous public GET stays covered.

Only the two owned test files and this report changed during audit. The two owned production files retain their implementation hashes. Future schema/lock/JAR JSON/child logs now target `build/migration-02-1/audit-validation/`, preserving all previous artifacts instead of overwriting them.

### Evidence integrity and queued minimum validation

All35 historical evidence files still match the original final-evidence manifest. Original five-file contents were copied, SHA-256 verified, into `audit-baseline/` before any edit. Reconstructing all611 historical source inputs using those baseline copies gives zero mismatches. Current sources differ from the implementation source manifest in exactly the two owned tests; production/build/schema sources are unchanged. `audit-identity-check.json` records this distinction. The old `final-owned-manifest.json`, `final-evidence-manifest.json`, `implementation-summary.json`, source manifest, logs, XML and observed exits were not rewritten.

XML/exits were recounted independently: own8/1class exit0; regression50/12classes exit1 with four context failures; persistence4/1class exit0. Successful composition remains8+46+4=58 unique checks/13classes on the historical tests, not a green58-run and not validation of the new assertions. Current JAR hash still equals both historical profile hashes, `aeb3ae656300a70dc4ba61b224a3d6d428d8ec9e96c70e77595a8349da1e453a`. Both applied30-row migration histories agree with all context version/checksum records, including V1.29 checksum666162279.

**Pending minimal slot request:** compile the changed tests and execute only CategoryAdminAuthorizationTest8 plus CategoryAdminBootJarTest1, with the existing offline/no-daemon/in-process/one-worker/heap limits and `RUN_BOOT_JAR_SMOKE=true`. Reuse the unchanged JAR only after verifying its hash. Record new observed exit and complete XML/source identity under audit-validation, scan new XML and child logs for full/partial tokens without printing matches, verify fresh30-row schema and exact SQL/PID/blocker records. The unaffected46+4 checks do not need a formal repeat. If the tightened concrete manager exception differs on the current Spring version, record the actual type/cause and resolve that assertion rather than accepting a broad failure. No runtime result for this queued9-test subset exists yet.

`audit-source-manifest.json`, `audit-owned-manifest.json` and `audit-status.json` describe the changed source and pending validation. Static `git diff --check` passes. No additional scope was needed, no commit was made, and the audit is not ready for final acceptance before this minimal validation.

### Completed separately authorized audit validation — 18 November 2026

The preceding pending-slot assessment is retained as audit history; the coordinator subsequently released slot04.6 and explicitly assigned this minimum run. Only the two changed classes were compiled/executed, in one offline/no-daemon/in-process/one-worker Gradle invocation with heap1536m/metaspace512m, test heap640m, maxParallelForks1. Both absolute fixture key paths were supplied through JAVA_TOOL_OPTIONS; no key contents or production credentials were used. Launch-only overrides set web, web.server, security and application logging to WARN. The JAR children retain their isolated explicit keys/environment and WARN HTTP settings.

`audit-validation/run-1.log` and `.exitcode` record directly observed **exit0, BUILD SUCCESSFUL in1m51s**. Complete raw XML in `audit-validation/run-1-results/` contains **9 tests/2classes, zero failures/errors/skips**: CategoryAdminAuthorizationTest8 and CategoryAdminBootJarTest1. In particular, the previously unobserved exact CannotCreateTransactionException expectation passed for both commands, as did exact no-tx mandatory failures, issuer claims, stale validation/no-op precedence, public renamed JSON, and full SQL/singleton blocker/revoker PID assertions.

There was no broad repeat or bootJar rebuild: production compilation, resource processing and the plain jar task were UP-TO-DATE; bootJar was not invoked. The existing artifact was hash-verified before testing, copied/frozen by the actual JAR test and exercised sequentially in legacy and modern. Current artifact and both new profile records again agree on SHA-256 `aeb3ae656300a70dc4ba61b224a3d6d428d8ec9e96c70e77595a8349da1e453a`. Both real HTTP login/command sequences and both child shutdown assertions passed.

`audit-validation/privacy-scan.json` scans the new raw Gradle log, both child logs and both raw XML files (5 files), decoding XML/HTML entities before examining text. It found **zero** complete compact JWTs, compact JWT fragments, labelled access/refresh-token fragments, Bearer JWTs or private-key markers. The tests also directly assert token/fragment absence in both JAR logs. Historical truncated fragments remain recorded in their immutable artifacts; this new zero-result is not retroactively claimed for old logs or for global production logging policy.

The fresh schema manifest again has30 applied Flyway rows, including successful1.29/checksum666162279; all schema version/checksum records agree with both actual new JAR profiles. The new lock log contains18 records across6 POST/PUT × role/status/is_agent scenarios, with exact SQL, scenario labels, singleton blocking PID sets and exact expected revoker PIDs. `lock-scenarios.json` records three lock observations per scenario.

`source-before.json` and `source-after.json` cover all611 inputs with **zero changes during the run**. `implementation-input-comparison.json` identifies every compared input:609 remain unchanged from implementation, and exactly the two own test sources differ. **All477 runtime/build inputs are unchanged**, including both owned production files, neighboring Java/Kotlin/runtime resources, V1.29 and Gradle configuration. No neighboring test source changed. Thus the unchanged successful neighboring checks remain applicable without a formal rerun.

The current successful composition is **audit9 + unchanged regression45 + unchanged persistence4 =58 unique checks/13classes**. The audit's8 own checks and1 JAR check replace the previous8+1, rather than being added as new unique checks. Historical regression exit1 and its four startup failures remain visible; the separate persistence4 exit0 resolves those launch failures. This is not a single green58-run, and not a full-suite/load/production acceptance.

All35 historical evidence files still match their original SHA-256 manifest. Audit validation raw files, source identities, XML, privacy scan, schema, locks, summary and their final hashes are separate under `audit-validation/`; no historical artifact was overwritten. `audit-validation/summary.json` and the updated `audit-status.json` reflect completed validation. At completion the read-only process inventory showed no Java/PostgreSQL entries from these checks; the heavy-test slot is explicitly released. No commit, push, deploy or checkbox change was made. Fresh audit findings are resolved within the five-file scope; local commit awaits a separate coordinator instruction.
