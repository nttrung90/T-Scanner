# VIP round 7 — independent audit harness, 01/10/2026

Audit-only artifacts. Production was not edited. See [report](</E:/DU AN AI/T-Scanner/RECHECK_VIP_FULL_ROUND7_2026-10-01.md>) and [sequential Gemini plan](</E:/DU AN AI/T-Scanner/PLAN_VIP_GEMINI_ANTIGRAVITY_ROUND7_AUTORUN_2026-10-01.md>).

## Results before remediation

Normal Android suite: **946/946 PASS**; backend: **99/99 PASS**; original round6: **12/12 PASS**; lint **0 errors/757 warnings**, debug build PASS. Host seams do not establish real device, payment, OAuth, RTDN delivery or Drive acceptance. ADB currently has no attached device.

| Harness | Cases | Result | Finding mapping |
|---|---|---|---|
| AndroidAgentAuditTest.kt | A01–A08 | 7 FAIL, 1 PASS | A01 F05; A02–A05 F06; A06/A07 F04; A08 control |
| RootVipAuthAuditTest.kt | C01–C06 | 5 FAIL, 1 PASS | C01–C03/C05 F07; C04 F08; C06 control |
| RootVipParserAuditTest.kt | D01–D04 | 3 FAIL, 1 PASS | D01–D03 F09; D04 control |
| backend-agent-probes.test.ts | B701–B706/B709–B711 | 7 FAIL, 2 PASS | B701/B711 F01; B702/B703 F02; B704 F03; B705 F06; B706 policy check; B709/B710 controls |

Total **27 cases, 22 FAIL, 5 PASS**. B706 alone is a contract decision, not evidence of an incorrect grant. 21 failing cases support nine grouped findings; duplicate probes do not represent additional independent defects.

## Fixture constraints

- All credentials/HTTP payloads are synthetic; no real Google, payment, Drive or credential network calls.
- C04 uses a JWT with valid local shape/future expiry, so rejecting a malformed token cannot accidentally satisfy the HTTPS assertion.
- A06 is deliberately noncooperative synchronous work. Preserve latch/dispatcher drain/store assertion; replacing it with a cancelable Deferred would miss the defect.
- A07 begins as A before connection, then switches to B; no assumption that receipts may change owner.
- B702 uses the production V2 response parser. Its different-SKU scenario must not be replaced with a permissive mock.
- B704 uses real pending verify, which does not bind the new token; canceled-pending RTDN then stops at TOKEN_UNKNOWN before querying Google. It records both query counts, currently zero. No implausible ACTIVE-to-PENDING lifecycle is used.
- B705 asserts per-token PENDING, independent of aggregate completion policy.
- B711 uses two DB connections plus close/reopen; only first query returns delayed stale ACTIVE. A conflict retry's fresh query returns EXPIRED, matching current authority.
- D03 models the valid backend tombstone with expiry0 plus linked ACTIVE. Do not remove active expiry validation globally to make it pass.
- Unsupported exploratory B707/B708 candidates were excluded from the final harness after checking Google lifecycle; they are not findings or part of these counts.

## Run from repository root

```powershell
$env:GRADLE_USER_HOME='C:/Users/nguye/.gradle'
./gradlew.bat -I docs/vip-round7-20261001/audit.init.gradle :app:testDebugUnitTest --tests com.tscanner.app.AndroidAgentAuditTest --tests com.tscanner.app.RootVipAuthAuditTest --tests com.tscanner.app.RootVipParserAuditTest --offline --console=plain
node --experimental-strip-types --test docs/vip-round7-20261001/backend-agent-probes.test.ts
```

Expected pre-fix exit is 1 due to assertions, with **0 Android errors**. Gradle compiles production and the probes successfully. The init script adds only this audit directory to test sourceSet. Normal builds do not include these probes until X00 ports them into permanent suites.

Full suite commands and original round5/6 harness commands are in the plan. Preserve every run's XML/log before another Gradle run overwrites the shared results directory. Backend durable fixtures use a unique process-specific DB under `build/vip-audit-round7-20261001/`; new process runs do not reuse earlier evidence DBs.

## Evidence files

- `evidence/android-baseline-summary.txt`, `android-baseline.log`: normal host validation.
- `evidence/backend-baseline.log`: 99 existing backend tests.
- `evidence/android-original-round6.xml`, `android-original-round6.log`, `backend-original-round6.log`: original probes.
- `evidence/android-new-probes-final.log`, three Android probe XML files: final 18-case run.
- `evidence/backend-agent-probes.log`, `backend-agent-probes.exit.txt`: final 9-case backend run with tightened lifecycle/race fixtures.
- `evidence/adb-devices.txt`: no device attached.
- Full baseline XML and git snapshots remain in `build/vip-audit-round7-20261001/`.

Official contracts used for linked identity/lifecycle: [Subscriptions V2](https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.subscriptionsv2), [subscription lifecycle](https://developer.android.com/google/play/billing/lifecycle/subscriptions). Interpretation and current source defects are explained in the audit report.
