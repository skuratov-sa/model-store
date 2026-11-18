# 04.6 — current ADMIN for participant status

Implementation and verification complete, 18 November 2026. Exclusive test slot released.
The separately assigned postimplementation context step and fresh independent audit are
now complete; see the appended audit section. No commit, publication, push/fetch/pull, deployment, new chat or subagent.

## Full owned scope

1. `src/main/kotlin/com/model_store/modern/identity/participant/application/ParticipantUseCases.kt` — only BlockParticipant plus TokenType/Profile imports; modern-only service wiring.
2. `src/main/kotlin/com/model_store/modern/identity/participant/application/AdminParticipantStatusLock.kt` — approved new ID-only Unit-returning port.
3. `src/main/kotlin/com/model_store/modern/identity/participant/infrastructure/JdbcAdminParticipantStatusLock.kt` — approved new guarded owner adapter.
4. `src/test/kotlin/com/model_store/modern/identity/participant/ParticipantUseCasesTest.kt` — affected BlockParticipant constructor/port fixtures only.
5. `src/test/kotlin/com/model_store/modern/identity/participant/ParticipantStatusAuthorizationTest.kt` — new actual HTTP/JPA/concurrency/resource suite.
6. `src/test/kotlin/com/model_store/modern/identity/participant/ParticipantStatusBootJarTest.kt` — new real dual-mode JAR suite.
7. `MIGRATION_04_6_REPORT.md` — this report.

ParticipantController, existing CurrentAdminLock/JdbcCurrentAdminLock, general participant
persistence, advice, shared/security/config, Java/reactive, Flyway, build files,
MIGRATION_PLAN and user files are unchanged relative to the refreshed base. No other
participant registration/read/update/delete/password/photo/domain rules were rewritten.

## Base and recovery

Worktree `C:/Users/skura/.codex/worktrees/ce97/model-store`, branch
`codex/migration-04-6-status-admin-current`. Original clean base was
`3c3cd61d8af06b6f60596a2f79c4b806e9895fe4`. The initial branch-metadata/cache access
restrictions were retried with authorized local access. Original November11 compile
failed at compileTestKotlin with Java heap OOM after39m22s, without executing tests.
Those historical sources were being developed during that attempt and are not claimed
as a stable/final snapshot. Original failure log is preserved in build/status046/recovery/.

On November18 four partial owned files and binary diff were safely backed up under
`build/status046/recovery/`, then the same branch fast-forwarded to local master
`a4796f653bce72e1de2bce0fe20aca65d486d51c` without conflicts or loss of own changes.
Incoming user commit5444d05/V1.29, helper535793e and all other incoming files remain base
content, not task04.6 changes. No reset/clean/stash or remote commands were used.

Read the plan/04.6, accepted16.2/12.3/31 reports and actual Java/controller/service,
owner-port/JPA/domain/security/SQL contracts. No repository AGENTS.md was present;
C:/Users/skura/.codex/AGENTS.md was empty. Model/effort were not changed.

## Final behavior

BlockParticipant requires verified Actor ACCESS, ADMIN claim and positive caller ID.
Its existing writable JPA transaction calls the new owner port before any target read.
The port is MANDATORY and checks actual writable transaction, joined expected JPA EMF
and bound expected DataSource BEFORE SQL, including when target ID is lower than caller.

It locks DISTINCT caller+target IDs ASC using parameterized FOR UPDATE and current SQL
tuples independent of the JPA identity map. After the entire set is locked, caller must
be current ACTIVE, nonagent ADMIN. Only then can a missing target be reported. An absent
lower target is not looked up again beyond the frontier, avoiding a newly inserted row
being acquired out of order. An existing target remains locked through owner read/save.
Existing ParticipantStore refreshes its already-held target snapshot; blocked() and the
route-specific unavailable/notfound rules are unchanged. Caller==target remains allowed
while current ADMIN, with authorization before self mutation. Locks last through outer
commit/rollback. CurrentAdminForbidden maps to ParticipantAdminForbidden/403 ACCESS_DENIED.

The legacy startup regression found BlockParticipant had previously been instantiated
without a profile, unlike its modern-only controller/new adapter. The final service is
explicitly @Profile("modern"); other services and legacy/default selection are unchanged.

## Environment and completed verification

All runs used the exclusive slot, one build at a time, JDK
`C:/Users/skura/.jdks/temurin-21.0.11`, cache `C:/Users/skura/.gradle`, offline/no-daemon,
max-workers1, quoted in-process Kotlin property, Gradle heap1536m/metaspace512m.
Test executor heap512m; own JAR children heap384m/metaspace256m. Every Gradle/test fixture
run explicitly set BOTH fixture paths via JAVA_TOOL_OPTIONS:
`-Dapp.public-key-path=keys/test_public_key.pem -Dapp.private-key-path=keys/test_private_key.pem`.
JAR children clear inherited environment and receive BOTH absolute fixture paths explicitly.
Only embedded PostgreSQL and mock/loopback S3/SMTP were used. No production DB, Docker,
user RSA resource or primary-checkout private key was accessed/copied.

**53 distinct tests in9classes passed; no failures/errors/skips in the final applicable runs.**

| Class | Tests | Final evidence |
| --- | ---: | --- |
| ParticipantStatusAuthorizationTest | 12 | verified-raw/results, raw |
| ParticipantUseCasesTest | 5 | verified-raw/results, raw |
| AgentAccessLockIntegrationTest | 11 | final-1/results, raw |
| AgentProfileCommandsIntegrationTest | 8 | final-1/results, raw |
| ParticipantJpaIntegrationTest | 10 | final-1/results, raw |
| ParticipantLegacyRegistrationContractTest | 1 | final-1/results, raw |
| ParticipantRegistrationMailTest | 4 | final-1/results, raw |
| ParticipantStatusBootJarTest | 1 | verified-raw/results, raw |
| BootJarDualModeSmokeTest | 1 | jar-1/results, raw |

All evidence paths above are under build/status046/. Observed completed runs:

- `final-1`: exit0, BUILD SUCCESSFUL2m12s,51tests/7classes,0failures/errors/skips; bootJar built.
- `jar-1`: exit0, BUILD SUCCESSFUL1m37s,2tests/2classes,0failures/errors/skips; both modes actually ran.
- After test-only logging changes, `own-final`: exit0,1m38s,18tests/3classes,0failures/errors/skips.
- Last `verified-raw`: exit0,1m36s,18tests/3classes,0failures/errors/skips, HTTP/security output suppressed; raw XML/logs preserved unchanged and hashed.

The five identity regressions cover the shared participant persistence/profile/registration
and caller+target lock frontier. Broader agent-token/ActiveAgentLock/MVC/catalog suites
were deliberately not run: those sources/contracts did not change. General assembled-app
smoke was explicitly required and retained. No entire repository suite is claimed.

## Issuer, concurrency and transaction evidence

The real issuer test calls HTTP /auth/login using a BCrypt-backed test password while
DB caller is ACTIVE/nonagent ADMIN, verifies returned ACCESS claims and reuses that exact
immutable token after SQL role/status/is_agent revocation. It covers both ID orders,
foreign/self/unknown target and unchanged data. The JAR test also obtains its unchanged
ACCESS from each actual mode's /auth/login and verifies signature/ID/type/role using the
fixture public key. Synthetic signed ACCESS and agent_access+ADMIN are separate cases;
no claim is made that normal agent issuance13 issues ADMIN delegation.

Tests also cover refresh/USER/missing/invalid identity rejection; positive ordinary/agent
and self targets; unavailable status and error precedence; stale managed caller/target;
full outer rollback; no-tx/read-only/JDBC-only/wrong-manager/foreign EMF/DS rejection;
and resource guard before a deliberately blocked lower target, without reaching owner SQL.
The wrong JDBC/JPA-manager proxy boundary precisely fails CannotCreateTransactionException
with IllegalStateException cause; raw owner-port guards give CurrentAdminTransactionRequired.

`verified-raw/concurrency.txt` is untouched current evidence with scenario labels,
actual backend PID, blocking PID and SQL. Bounded waits use pg_blocking_pids, not sleeps
or catch-any-exception assertions. Role/status/is_agent revoke-first and action-first
cover both caller/target ID orders and self. A test-only trigger/advisory gate pauses
the actual HTTP owner write after both locks; it is dropped in finally. Examples:

- Revoke-first, caller97 > target96, role USER: worker42492 waits behind blocker18644
  on `SELECT role::text,status::text,is_agent FROM participant WHERE id=$1 FOR UPDATE`.
  Release commits revoke; HTTP403 and unchanged target are asserted.
- Action-first, caller46 > target45: worker42492 waits at actual Hibernate participant
  UPDATE behind fixture18644; revoker31500 waits on worker42492 with
  `UPDATE participant SET role='USER' WHERE id=$1`. Release gives HTTP200, then revoke
  commits and the next unchanged token is denied. Full parameterized owner SQL is in file.
- Opposing admins: worker42492 waits on18644 and worker35848 waits on42492; both SQLs
  address participant FOR UPDATE. After release results are exactly200/403, one ACTIVE
  and one BLOCKED, without deadlock. Recursive wait-chain observation accounts for PG queues.
- Target status after wait covers DELETED/BLOCKED/WAITING_VERIFY in both ID orders,
  preserving404 NOT_FOUND after authorized caller validation.

## Source/schema/JAR identity

Before/after all-source manifests include Java/Kotlin/SQL/YAML paths and SHA256 values.
`final-1` before/after manifest SHA256 both:
`559663fcf7ed96b4908bd75566e7f75a2c99f69e1750d647b2f1a9528f45f4fd`.
`verified-raw` before/after both:
`bda41f013dd66ab7490fefbbfe1a6333fc1fdaebcd920a0e935a67a761d51ee2`.
Only the two new test logging configurations differ between these runs; runtime sources,
all neighboring tests and schema are identical. source-delta-from-final1.json records it.
No source changed while any resumed verification command was running. own-2's after
manifest was captured after its known test fix and is explicitly not claimed identical.

Runtime Flyway evidence in verified-raw/schema-current.txt contains30 successful installed
rows,1.0 through1.29, plus actual participant column types. V1.29 is installed_rank30,
checksum666162279,success=true. No old29-row snapshot is claimed current. Current schema
source hashes were also recorded under recovery/current-schema-source-hashes.json.

One actual runtime artifact was copied and launched in both modes, before and after
logging-only test changes. SHA256 remains:
`ee85da2802c69b4b384c6c6d3e51a61ee1da5641fc5983f88e50e4fd9a349679`.
Both JAR suites log that same frozen-artifact digest; source JAR hash is unchanged.

| Actual issuer ACCESS / current DB caller | Legacy HTTP / target | Modern HTTP / target |
| --- | --- | --- |
| ACTIVE nonagent ADMIN | 200 / BLOCKED | 200 / BLOCKED |
| role revoked to USER | 200 / BLOCKED | 403 ACCESS_DENIED / ACTIVE |
| status revoked to BLOCKED | 200 / BLOCKED | 403 ACCESS_DENIED / ACTIVE |
| is_agent changed to true | 403 / ACTIVE | 403 ACCESS_DENIED / ACTIVE |

Thus modern deliberately tightens stale ADMIN authorization while preserving active
status behavior/empty success/error contracts. Legacy Java is unchanged; its role/status
staleness and existing ordinary-token agent rejection are observed, not inferred parity.

## Failures and artifact integrity limitation

Historical failure directories remain available, not counted as successful final evidence:

| Run | Observed exit / result | Resolution |
| --- | --- | --- |
| recovery original | exit1/OOM39m22s, no tests | in-process compiler1536m; old unstable sources not final |
| own-1 | exit1/7s, no compilation/tests | quote dotted PowerShell compiler property |
| own-2 | exit1/59s, compileTestKotlin | require non-null status before sorting new test values |
| own-3 | exit1/1m48s,17tests,2failures | exact wrong-manager exception and transitive PG wait-chain expectation |
| own-4 | exit1/21s, compilation only | JDBC result map values nullable |
| own-5 | exit0/57s,17tests passed | intermediate before legacy profile fix |
| identity-1 | exit1/1m32s,34tests,1failure | modern-only BlockParticipant profile; legacy context then passed |

Framework DEBUG output initially emitted abbreviated fixture JWT responses despite the
test helpers not printing them. I incorrectly redacted20 lines IN PLACE in five historical
textual artifacts without preserving original copies or original artifact hashes:

1. build/status046/own-3/results/TEST-com.model_store.modern.identity.participant.ParticipantStatusAuthorizationTest.xml
2. build/status046/own-5/results/TEST-com.model_store.modern.identity.participant.ParticipantStatusAuthorizationTest.xml
3. build/status046/final-1/results/TEST-com.model_store.modern.identity.participant.ParticipantStatusAuthorizationTest.xml
4. build/status046-bootjar-modern.log (transient root path later overwritten by subsequent runs)
5. build/status046/jar-1/status046-bootjar-modern.log

These versions are REDACTED, NOT RAW. Original pre-redaction copies/hashes are unavailable;
this is an evidence-integrity limitation. Assertion/failure/count contents and original
Gradle logs/observed exits were retained, but complete historical raw stdout cannot be
reconstructed. redacted-history-disclosure.txt and redacted-history-manifest.json identify
edited versions/hashes; the root path was already reopened by the next JAR run during hash
capture, so its earlier redacted version has no directly captured hash. No token/fragments
are reproduced in this report or messages. No further historical output was edited.

Subsequent own-final raw XML/child logs were copied and hashed before further changes;
that run remains raw even where legacy codec logging still emitted a fixture fragment.
Final verified-raw suppresses web/HTTP/security logging, retains raw outputs unchanged,
and its token-output-check.json reports no JWT-like text in its XML/log/txt evidence.
It re-executed only affected own classes without rebuilding runtime or changing neighboring
tests. raw-artifact-hashes.json records final files. Do not represent redacted history as raw.

## Reproduction and handoff

Commands share environment above and:
`--offline --no-daemon --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m' '-Pkotlin.compiler.execution.strategy=in-process'`.
The final-1 command selected the seven classes in the table and bootJar. The JAR command
selected ParticipantStatusBootJarTest/BootJarDualModeSmokeTest with RUN_BOOT_JAR_SMOKE=true.
After logging changes only ParticipantStatusAuthorizationTest/ParticipantUseCasesTest/
ParticipantStatusBootJarTest were selected, same env and RUN_BOOT_JAR_SMOKE=true. Each
completed exit is saved separately; source snapshots identify exactly tested revisions.

Final git diff --check passed. Only the seven owned files remain changed/untracked.
Final process snapshot shows no own Gradle/compiler/JAR/test/PG processes. Existing PG
root PIDs35724/15068/28768 and IDE helper were present before this slot and left untouched.
Exclusive Gradle/JAR/PostgreSQL slot is explicitly RELEASED. Production was never used.

Real compaction command/counter is unavailable in tools; no summary is called compaction.
At implementation handoff the separate context/audit/commit steps were pending; context
and fresh audit are now recorded below. Local commit still requires separate instruction.
No checkbox or commit has been made.

## Separately assigned fresh audit, 18 November 2026

The coordinator separately completed the postimplementation context step (real compaction
command/counter unavailable) and then assigned this fresh security/plan/evidence audit.
No Gradle, compiler, JAR or PostgreSQL was launched during this audit; the heavy slot
remains with other tasks. No production or test source changes were required. Only this
report and new derived static-analysis JSON under build/status046/audit/ were written.
Historical raw/redacted evidence was read only and was not changed by the audit.

### Code and contract conclusions

Re-read all seven owned files, the original04.6 plus approved November18 narrow delta
from current local master's plan, and actual shared verified-Actor security, controller,
owner/JPA/entity/domain/Java legacy contracts. Full production and existing-unit diffs
remain restricted to the agreed scope. No new authorization bypass, changed domain/error
rule, broader flow change or new lock inversion was found.

- One production entry calls BlockParticipant: modern ParticipantController's verified
  AuthenticationPrincipal. No new status route, unguarded overload or alternate owner
  write was introduced. ACCESS/ADMIN/positive ID guards also run on direct app-command calls.
- Current tuple must be ACTIVE/nonagent ADMIN. The adapter guard is before its first JDBC
  call and before any ParticipantStore call, even for target<caller. No-tx, read-only,
  JDBC-only, foreign EMF/DS and incompatible manager paths fail with the exact recorded
  typed exceptions; guard branches contain no SQL/write. Existing raw no-tx/foreign-port
  tests and blocked-lower-target test corroborate the source ordering. The SQL template
  is parameterized and uses the same private expected DataSource as the resource guard.
- DISTINCT sorted IDs execute as separate FOR UPDATE statements, independent of planner
  ordering. A caller read before waiting on a higher target remains current because its
  tuple lock is retained. Authorization is evaluated after the complete lock set.
  An absent lower target throws after caller validation and never reaches a second owner
  lookup. Existing targets are already held when JPA refresh/save reads them again;
  this does not acquire a previously unlocked lower participant. Missing/unavailable
  target remains404 NOT_FOUND only after authorization, revoked caller403 takes precedence.
- Self ID is deduplicated and active ADMIN is checked before self-blocking; no self-ban.
  Domain blocked() permits only ACTIVE and preserves every other field. JPA refresh avoids
  stale managed target overwrite; independent current SQL avoids stale caller acceptance.
  Save is in the same outer JPA transaction with no REQUIRES_NEW/commit. Full rollback
  including the extra caller field write is asserted. No caught database/timeout failure
  is converted to success.
- BlockParticipant and new adapter are modern-only. Its sole consuming controller is
  modern-only too. LegacyRuntimeConfiguration remains !modern and the existing component
  filter is unchanged; the modern-only service no longer adds an unresolved dependency
  to legacy/default graph. Actual legacy registration context and both JAR modes passed
  after this fix; no legacy Java service/controller was edited.

Participant writer/lock-frontier inventory was independently inspected: ordinary
update/delete/password and verification commands lock one participant; photo replacement
adds only image rows. AgentAccessLock/AgentProfile write paths acquire the entire distinct
participant set ASC before images. Media12.3 collects all participants ASC before products/
images; maintenance uses distinct sorted participant IDs; CreateOrder/lifecycle/review
eligibility lock participant sets ASC before their order/product frontier. Basket and
social/transfer owner locks are single-ID; current-ADMIN and active-agent ports are
single-ID. Existing legacy saves/admin-agent profile paths write one participant; no new
04.6 callback, cascade or participant-status DB trigger adds another participant/image/
product/order lock. New04.6 itself acquires only its sorted participant set and updates
its already-held target, so it adds no edge back from other entities to participants.
Composition inside an arbitrary external transaction that prelocks unrelated IDs remains
the caller's general ordering responsibility; no exposed production04.6 call does that.
This is a static inventory plus accepted relevant regression, not a claim of newly
executed cross-context stress tests for every existing writer.

### Independent test/evidence conclusions

The genuine issuer cases call real /auth/login with a test BCrypt password while current
DB ADMIN, verify ACCESS ID/role/type (JAR also verifies signature) and reuse the same val
without renewal after role/status/is_agent changes. Synthetic Jwts and synthetic
agent_access+ADMIN cases remain explicitly separate. Login bodies are absent from final
logs, and helpers never insert token values into assertion messages.

The wrong-manager assertion now names CannotCreateTransactionException and separately
requires its IllegalStateException cause; owner guards require CurrentAdminTransactionRequired,
MANDATORY no-tx proxy requires IllegalTransactionStateException. No general Exception
assertion stands in for those failures. Future timeouts/ExecutionException/SQL40P01 fail
tests rather than being treated as authorization success. JAR readiness's bounded
runCatching only retries startup readiness; it cannot swallow a failed status/SQL assertion.
The tests' full row/status comparisons, guard-before-store mocks, blocked-lower-target
case, outer rollback, managed-stale and post-wait target tests corroborate zero unauthorized
owner mutation. No unexecuted assertion has been counted as a passing test.

Static recheck JSON under build/status046/audit/ records:

- evidence-recheck.json: all17 saved verified-raw artifact hashes match; all593 current
  Java/Kotlin/SQL/YAML source hashes match the final source-after manifest; nine unique
  primary XML classes total exactly53tests,0failures/errors/skips. Repeated own-final/
  verified-raw runs are not added together. Existing neighbors/shared-smoke XML were
  re-read and hashed at this audit; only verified-raw has the pre-audit raw-hash baseline.
- causal-graph-recheck.json:25 captured scenarios =6 target-after-wait +9 action-first
  +9 revoke-first +1 opposing-admin. Every direct recorded worker lists the EXPECTED
  blocker, not merely some blocker. In action-first the revoker's expected blocker is
  the exact owner-write worker previously observed behind the fixture root. Opposing
  admins' recursive CTE starts from the expected root and follows actual blocking edges;
  independently parsed worker35848 -> worker42492 -> root18644 reaches that root.
  Both exact HTTP futures then return200/403. The recursive UNION accounts for tuple
  wait queues and cannot call a deadlock or non-root unrelated wait a passing outcome.
- history-schema-recheck.json: final-1 and jar-1 source-before/after hashes both559663...;
  verified-raw before/after bothbda41f... . Between stages only two own test logging
  configurations differ; runtime/all neighboring test/schema hashes are identical.
  Observed completed exits remain0. Source JAR and both captured runtime suite digests
  agree on ee85da2802c69b4b384c6c6d3e51a61ee1da5641fc5983f88e50e4fd9a349679.
  Schema evidence has30 successful rows, with V1.29 at rank30/checksum666162279.

Final raw log/XML/txt inspection was strengthened from the old 25-character token-prefix
pattern to the short literal eyJ: no hits in verified-raw, all nine selected primary XML,
final-1/jar-1 Gradle logs, or the raw general JAR legacy/modern logs. No token/fragments
were printed. This verifies the final outputs, not historical logs known to contain
fixture fragments. Existing final raw outputs and their hash manifest were not rewritten.

The five historical redactions remain an explicit evidence-integrity limitation. Four
recorded redacted versions still match their redaction-time hashes; the fifth was the
transient root log later overwritten by another run, with no original captured hash.
There is no attempt to reconstruct or label any of those as original raw output.
Their original pre-redaction copies/hashes are still unavailable; historical Gradle
exits/failure summaries remain distinct from the new hash-verified raw reruns.

### Audit disposition

No production/test change, new required heavy check or expanded scope is proposed.
No green test was rerun for formality. All audit commands were read-only source/evidence
inspection or derived local JSON/report writing; no Gradle/JAR/PG use, no historical
artifact rewrite, no remote action, no plan checkbox, no commit. The implementation
source/test snapshot remains the one verified above. Local commit is still awaiting
an explicit separate coordinator instruction. Audit work stops here.

## Separate local-commit authorization

After the context step and fresh audit, the coordinator separately authorized a local
commit of exactly the seven files in Full owned scope, without push. Pre-stage index
was empty, branch remained codex/migration-04-6-status-admin-current at parent
a4796f653bce72e1de2bce0fe20aca65d486d51c. All six source/test files matched the final
audit source manifest byte for byte before staging; only this report gained this handoff
record. No build, test, JAR or PostgreSQL was launched for the commit stage. Explicit
paths are used, excluding build/evidence/logs, plan, keys and all user/foreign files.
The final handoff reports the actual commit SHA and validates committed source contents
against the audit-checked working files with CRLF/LF normalization only; the commit SHA
is not embedded here to avoid a self-referential commit identifier. Earlier no-commit
statements describe the implementation/audit handoffs before this separate authorization.
