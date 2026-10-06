# Gemini Antigravity sửa VIP vòng 7 — tự chạy X00 đến X12

## Mục tiêu, chứng cứ đầu vào và quy tắc autorun

Làm việc tại `E:\DU AN AI\T-Scanner`. Đọc [audit vòng 7](</E:/DU AN AI/T-Scanner/RECHECK_VIP_FULL_ROUND7_2026-10-01.md>), [probe README](</E:/DU AN AI/T-Scanner/docs/vip-round7-20261001/README.md>), kế hoạch/báo cáo vòng 6 và checkout hiện tại. Giải quyết F01–F09 với regression gọi production path, bảo toàn luồng mua/restore, quyền theo account, watermark, Drive và các regression cũ.

**Tự làm X00 → X01 → … → X12 theo thứ tự, không hỏi người dùng sau mỗi gói.** Checkpoint là ghi chứng cứ nội bộ rồi chuyển tiếp. Đây là tài liệu giao Gemini trong Antigravity; Codex chưa khởi chạy Gemini từ chat này.

- Giữ staged/uncommitted/untracked changes. Không reset/clean/stash/drop, không sửa file ngoài scope, không thay SKU/giá/signing/release configuration. Không tự commit/push/deploy/publish hoặc giao dịch tiền thật.
- Mỗi gói: đọc current production → regression red → sửa đúng scope → focused tests green → ghi `REPORT_VIP_R7_Xxx.md`, cập nhật `PROGRESS_VIP_R7_AUTORUN.md` → chuyển gói kế tiếp.
- Nếu đã sửa ở checkout: kiểm chứng đầy đủ rồi ghi `SKIPPED_ALREADY_VERIFIED` với command/result; không dựa vào báo cáo trước để bỏ kiểm tra.
- Không nới assertions, loại test, thêm mock credential vào production, bypass owner/CAS/auth/expiry validation hoặc sửa riêng fixture branch để đạt báo cáo xanh. Fixture auth cần token tổng hợp explicit ở test; preflight format/exp không thay thế xác minh chữ ký Google phía backend.
- Các file trùng scope giữa gói chỉ sửa tuần tự. Backend contract phải hoàn thành trước Android consumer. Không chạy agents cùng sửa `verifier.ts`, `BillingManager.kt` hay `PlayPurchaseVerifier.kt`.
- Tự xử lý compile/test failures trong scope và quay lại gói sở hữu nếu integration phát hiện lỗi. Thiếu device/credentials ghi `NOT_RUN/BLOCKED_EXTERNAL`, tiếp tục phần độc lập. Không đánh dấu gói phụ thuộc DONE khi code dependency chưa đạt. Chỉ tập hợp vấn đề cần người dùng thật sự ở cuối.
- Sau interruption, đọc progress và xác minh checkout/test state trước khi tiếp tục. Ghi contract, before/after tests, lệnh, số test, exit/XML/log, file thay đổi, việc chưa chạy cho từng gói.

Baseline xác nhận: Android **946/946**, backend **99/99**, original R6 **12/12**, lint **0 errors/757 warnings**, debug build PASS. Probes mới: **18 Android:15 FAIL/3 PASS**, **9 backend:7 FAIL/2 PASS**; B706 là policy check, không phải chứng cứ cấp quyền sai.

Prompt tái sử dụng cho từng checkpoint: `PLAN_VIP_GEMINI_ANTIGRAVITY_ROUND7_AUTORUN_2026-10-01.md — thực hiện Xxx theo scope/regression/acceptance của gói; đọc progress và current code, lưu REPORT_VIP_R7_Xxx.md, rồi tự chuyển gói kế tiếp khi đạt. Không chờ người dùng sau checkpoint.` Thay `Xxx` bằng ID từ X00 đến X12.

## X00 — Baseline, bảo toàn checkout và regression bền vững

- **Ownership:** chỉ tests/docs/progress. Chưa sửa production.
- Lưu `git status --short`, diff inventory và baseline mới; không sửa nội dung có sẵn của người dùng. Xác minh R5/R6 regressions và harness gốc. Không dùng suite count cũ sau khi thêm test.
- Chuyển 18 Android probes trong `docs/vip-round7-20261001/` vào test sourceSet thường với class không trùng; chuyển 9 backend probes vào `backend/billing-verifier/test/round7-regression.test.ts`. Adapt imports/fixture temp path/cwd và class naming có ghi rõ; giữ assertions hành vi. Lưu harness gốc nguyên vẹn làm evidence trước sửa.
- B711 phải dùng hai physical SQLite connections và close/reopen DB, query đầu delayed ACTIVE, query sau phản ánh authority EXPIRED. A06 dùng noncooperative synchronous verifier + latch/dispatcher drain; không đổi thành deferred cancellation hợp tác. B704 dùng new PENDING chưa bind → canceled pending; không dùng ACTIVE→canceled-pending fixture sai lifecycle.
- Phân loại B706 theo X04: có thể adapt expectation theo contract được ghi rõ và test đầu cuối, không gọi đó là sửa entitlement defect. B705 phải assert per-token PENDING, không chỉ overall status.
- **Acceptance:** map đủ 27 cases → Fxx/contract/control; red evidence từ production, không fixture/compile error; 5 controls PASS; có registry command/log để chạy lại. Checkpoint `REPORT_VIP_R7_X00.md`, tự chuyển X01.

## X01 — CAS cho mọi linked bind/update (F01)

- **Depends:** X00. **Files:** `backend/billing-verifier/src/verifier.ts`, `src/store.ts`/`src/storage/sqliteDriver.ts` chỉ nếu API hiện có chưa đủ; backend regressions. Không đổi DB schema hoặc token ownership rộng hơn nhu cầu.
- Dùng explicit absent sentinel (`expectedVersion:null` hoặc `expectedAbsent`) khi record chưa tồn tại; captured expectedVersion khi đã có record. Kiểm tra bind result và propagate conflict/storage error.
- Khi conflict cần bounded retry/re-read và authoritative re-query theo policy hiện có; không lấy response cũ rồi tự tăng version trên record mới. Tái sử dụng normal verify CAS logic nếu phù hợp, tránh một linked path yếu hơn normal path.
- **Regression:** B701/B711; first bind absent race, existing-version race, owner conflict, storage false/throw, retry exhausted; query fresh EXPIRED và fresh ACTIVE controls; hai connection, reopen và restart. Không hard-code EXPIRED khi Google query mới hợp lệ trả ACTIVE.
- **Acceptance:** response cũ không hồi sinh quyền đã bị authority mới invalidate; version monotonic và owner bất biến; conflict không bị log rồi bỏ qua. Checkpoint, tự chuyển X02.

## X02 — Linked identity và typed resolution outcome (F02)

- **Depends:** X01. **Files:** backend `src/verifier.ts`, `src/googlePlayClient.ts`, `src/types.ts`; shared helper nhỏ nếu cần; tests. Không sửa client Android.
- Không dùng SKU mới làm expected SKU cho old linked token. Nếu old record có identity đáng tin, dùng nó; nếu chưa có, lấy identity từ Google V2 và đối chiếu catalog của app. Giữ strict SKU/type/hash/owner validation; không nhận SKU bất kỳ hoặc metadata client tự khai.
- Resolver trả typed outcome: resolved active/inactive/pending, unresolved transient/permanent/owner conflict/storage conflict, cùng token/SKU/status cần aggregation. Verify new canceled receipt vẫn REVOKED, không đổi thành paid ACTIVE nhờ linked receipt.
- Giữ linked visited/depth bound, không recursive loop vô hạn. Không bỏ linked upstream lỗi bằng `catch { warn }` rồi báo toàn bộ complete. Không chuyển receipt đã thuộc B sang A.
- **Regression:** B702/B703/B709/B710; monthly→yearly và yearly→monthly, same SKU, old absent/known, ACTIVE/EXPIRED/REVOKED/PAUSED/HOLD/GRACE, linked 503/404/hash mismatch/owner conflict/missing/cycle/depth. Dùng production V2 parser+transport seam cho different SKU, không chỉ MockGooglePlayBillingApi không validate SKU.
- **Acceptance:** identity old/new đúng, old hợp lệ được restore hoặc invalidate đúng; linked failure có thể retry và đi ra contract; X01 CAS vẫn PASS. Xuất JSON fixtures cho X04/X05, tự chuyển X03.

## X03 — RTDN canceled pending và linked authority (F03)

- **Depends:** X02. **Files:** backend `src/rtdnHandler.ts`, resolver helper/API từ X02, route retry handling nếu cần, tests. Không tự deploy hoặc cấu hình PubSub thật.
- Handle new pending chưa bind: query Play authority để biết canceled/linked metadata; chỉ resolve owner khi linked record/hash/canonical identifiers chứng minh app owner theo policy hiện có. Không tự bind unknown token vào một owner tùy ý hoặc suy đoán account từ notification.
- Refresh required linked old receipt qua resolver an toàn X01/X02 cho cả unknown new và known new canceled path. Chọn status rõ khi owner không xác định; không coi linked query/storage failure đã PROCESSED hoặc consumed vĩnh viễn.
- RTDN retry/dedupe/event order phải bảo toàn khi một bước đã commit còn bước linked lỗi; xử lý duplicate có thể hoàn tất bước còn thiếu. Không tự revoke old từ notification type mà thiếu Google authority.
- **Regression:** B704 lifecycle PENDING→canceled, unknown new + known old owner, known new, old khác SKU, foreign owner/hash, old ACTIVE→EXPIRED/REVOKED, transient linked query và retry same event, out-of-order concurrent event, two connections/reopen.
- **Acceptance:** old state sau notification khớp authority và không còn bị bỏ vì TOKEN_UNKNOWN trước query; owner conflict không cấp VIP; failure retry hữu hạn/đúng contract. Checkpoint, tự chuyển X04.

## X04 — Backend restore contract đầy đủ theo từng token (F06 backend)

- **Depends:** X03. **Files:** backend `src/types.ts`, `src/verifier.ts`, HTTP response mapping ở `src/index.ts` nếu cần; tests và contract docs.
- Giữ PENDING là per-token PENDING, không REJECTED thiếu reason. Aggregate cần diễn đạt complete/partial/unresolved/transient/no-active và result của linked resolution. Known cached ACTIVE chưa refresh không thành fresh success. Có thể giữ quyền cached theo policy đã chọn, nhưng không làm mất freshness/failure.
- Quyết định B706: ưu tiên overall phản ánh các candidate bị từ chối; nếu giữ SUCCESS nghĩa query hoàn tất/no-active, ghi rõ và bảo đảm per-token errors đến client/UI, không báo khôi phục VIP thành công. Update probe contract minh bạch; không tính thay đổi này là sửa cấp quyền sai.
- Deduplicate local/known/linked candidates theo token và identity; giữ status/count theo receipt, không theo số products. Trả message/error reason hữu ích, không chứa raw token/secrets.
- **Regression:** B705; all-pending, no candidates, all-new404, all-known unresolved, mixed active+pending+rejected, successful revoke/expire, local success+linked503, foreign owner, route response với authenticated principal. Giữ R6B01/B03 PASS.
- **Acceptance:** JSON fixtures full/partial/pending/no-active/auth/transient có per-token outcomes nhất quán, snapshot ownership đúng và freshness policy ghi rõ. Handoff fixtures tới Android; tự chuyển X05.

## X05 — Android parser theo state, strict required fields và metadata (F09, F06 parser)

- **Depends:** X04. **Files:** `app/src/main/java/com/tscanner/app/utils/billing/PurchaseVerifier.kt`, `PlayPurchaseVerifier.kt`, model cần thiết, verifier tests. Chỉ adapt caller tối thiểu để compile; UI behavior thuộc X10.
- Parse `results[]`, overall message/status/freshness từ contract X04; giữ token identity, reason, pending và failure counts thực. Không để caller tự dựng failures=1.
- Required owner/source/token/type/version và timestamps theo contract phải explicit; không điền owner/source cho malformed production payload bằng request/default. Legacy/dev schema chỉ có migration/policy explicit, không fallback trong strict Play path.
- Expiry validation theo state: ACTIVE/GRACE/CANCELED_ACTIVE phải expiry hợp lệ; tombstone REVOKED do canceled pending không có expiry/expiry0 phải được nhận theo backend contract. Pending không được cấp paid. Không từ chối valid old ACTIVE trong snapshot hợp lệ chỉ vì canceled new chưa từng có expiry.
- **Regression:** D01–D04; X04 JSON fixtures có new canceled tombstone + old monthly ACTIVE; source missing/null/blank/unknown, item/snapshot owner thiếu/sai, type mismatch, version/timestamp malformed, duplicate/conflicting tokens, expired/revoked/lifetime controls, HTTP/body status mismatch. Giữ R5/R6 malformed payload regressions.
- **Acceptance:** malformed không mutation; valid lifecycle payload đi qua production parser; metadata được bảo toàn tới typed result. Tạo contract seam fixtures cho X10, tự chuyển X06.

## X06 — Credential thật, default model và readiness (F07 phần preflight)

- **Depends:** X05. **Files:** `UserProfile.kt`, `PlayPurchaseVerifier.kt`, `VipUpgradeDialog.kt` resolver, `VipPurchaseActionCoordinator.kt`, test fixtures. Session API trong `AppAuthManager.kt` chỉ nếu cần.
- Đưa `UserProfile.idToken` default về null. Chuyển fake credentials sang test helpers explicit. Những test user cần logged-in-ready phải cấp synthetic JWT có exp phù hợp; không thêm mock token vào production để giữ số tests xanh.
- Local production readiness fail closed cho null/blank/malformed/decode lỗi/missing-exp/invalid-exp/expired token; JWT preflight là kiểm tra shape+expiry, không giả định đã xác minh chữ ký/issuer. Backend auth vẫn là authority. Dev mode/scheme phải explicit và không lọt release path.
- Backend configured và auth ready là hai trạng thái khác nhau. Readiness cuối trước launch/HTTP phải lấy phiên hiện tại sau reconnect/product query; owner/generation thay đổi hủy operation cũ. Không mở Play paid flow khi credential đã mất/hết hạn.
- **Regression:** C01/C02/C03/C06; real Google SDK null token, demo user policy, malformed payload/base64/exp type, missing token, valid control, token expired trong lúc chờ reconnect/products, A→B→A, double click và cancel. Test DefaultVipPurchaseLauncher/BillingManager path, không chỉ fake launcher default true.
- **Acceptance:** không credential giả ở model/main defaults, không malformed-ready; phiên hợp lệ vẫn mua/restore được; missing/expired dẫn tới recovery X07. Checkpoint, tự chuyển X07.

## X07 — Restore transport guard và auth recovery (F08, F07 phần restore)

- **Depends:** X06. **Files:** `PlayPurchaseVerifier.kt`, typed result, `BillingManager.kt` auth result mapping, `VipUpgradeDialog.kt`, `VipLoginContinuationHandler.kt`, Home/More entrypoints, strings/locales và tests trong scope.
- Áp dụng HTTPS/config/auth/session guard tại đường restore gửi request thật, trước bearer/transport. Trim/validate endpoint bằng cùng policy verify; config lỗi trả NotConfigured, auth thiếu/hết hạn trả AuthRequired hoặc tương đương typed rõ, cancellation rethrow. Không chặn riêng injected transport.
- Phân biệt 401 re-auth với 403 owner/permission/policy; không gom thành lỗi Google Play503. UI cho phép recovery/refresh provider được hỗ trợ; token fresh thì resume đúng một lần dưới owner/generation đúng. Cancel/error không tự grant trial/VIP hoặc loop sign-in.
- Logged-in Home mở VIP cũng phải có recovery khả dụng; missing callback chỉ toast/dismiss không đủ continuation cho kế hoạch này. Restore button/loading luôn có terminal state hoặc bị dispose theo lifecycle; old dialog không nhận callback session mới.
- **Regression:** C04/C05; valid synthetic token + HTTP rejected trước transport, HTTPS valid control, URL blank/malformed, missing token, restore401, 403 owner error, 429/503, cancellation; sign-in success/cancel/failure, expired sau await, Home/More, duplicate callback, account switch. Không gửi credential thật trong probes/logs.
- **Acceptance:** invalid config/auth không gọi transport; auth lỗi hiện đúng recovery và resume một lần; server error vẫn retry đúng; không thay auth/owner security để đi qua tests. Checkpoint, tự chuyển X08.

## X08 — Owner/generation trước await và cancellation sau blocking HTTP (F04)

- **Depends:** X07. **Files:** `BillingManager.kt`, `BillingReconciliation.kt`, `BillingOperationContext.kt`/scope plumbing tối thiểu và tests. Không mở lại entitlement storage architecture nếu không cần.
- Capture operation owner/generation ở entrypoint restore/sync/purchase, trước reconnect/query/token/HTTP. Pass context qua các callbacks; không capture current B sau khi A đã chờ. Suppress stale caller callback; operation mới của B vẫn hoạt động.
- Sau verifier đồng bộ không hợp tác trả về: kiểm tra cancellation/job/isDestroyed trước store/profile/prefs/ack/listener/onComplete. CancellationException không được biến thành network error. Audit các nhánh Success/Rejected/tombstone/ack, không chỉ remote restore.
- Giữ policy receipt có thể đã thanh toán nhưng account đổi: identity/binding của old owner phải được bảo toàn qua recovery/retry phù hợp, không cấp vào current owner hoặc cập nhật UI cũ. Destroyed Manager không tiếp tục commit; Manager mới/session mới có thể restore receipt hợp lệ.
- **Regression:** A06/A07; cooperative và noncooperative verifier success/rejection/error sau destroy, connection success/failure muộn, A→B→A, canceled token/HTTP/ack callback, recreate Manager cùng session, listener/prefs/profile/store assertions. Kiểm tra main-thread callback contract.
- **Acceptance:** probes red thành green bằng production ownership/cancellation checks; không bỏ store assertion; flags/profile/ack/callback không leak từ operation disposed. Checkpoint, tự chuyển X09.

## X09 — Account backend refresh khi Play query lỗi (F05)

- **Depends:** X08. **Files:** `BillingReconciliation.kt`, `BillingManager.kt` orchestration, tests. Reuse contract X04/X05, không tạo authority song song.
- Play query failure không ngăn backend refresh của authenticated app owner. Query thành công của catalog còn lại vẫn được xét; lỗi/pending/absence không tự revoke. Backend authoritative tombstones/remote-only receipts vẫn được commit/project một lần bằng path hiện có.
- Tránh duplicate network/ack/commit; tách local query availability khỏi per-token verification và account snapshot completeness. Backend error giữ cache theo policy và trả partial/unresolved đúng X10.
- **Regression:** A01; subs lỗi/inapp OK, ngược lại, cả hai lỗi, local none/pending/purchased/mixed, server lifetime refund/revoke/active, thiếu auth/config, owner change sau Playquery/backend await. Qua Manager sync và restore thật, không chỉ helper.
- **Acceptance:** backend được gọi dù Play query lỗi khi ready; new server state phản ánh profile/flags; account isolated; không success giả từ lỗi Play. Checkpoint, tự chuyển X10.

## X10 — Partial/pending/unresolved đi tới UI và feature gates (F06 Android)

- **Depends:** X09. **Files:** `BillingReconciliation.kt`, `BillingManager.kt`, `VipUpgradeDialog.kt`, localized strings, result models nếu cần, tests. Giữ single commit + checked projection từ W06.
- Phân biệt số receipts freshly resolved, số cached active còn dùng được, pending, unresolved/error và local query lỗi. Không lấy snapshot.size làm fresh-success count; không hard-code failure=1; không bỏ `results[]`/message.
- Local success + remote failure/rejected/not-configured phải giữ partial/unresolved. Có thể apply entitlement hợp lệ, nhưng UI phải báo phần còn lỗi và retry. Pending không báo complete restore; no-active/all-rejected không gọi post-upgrade activation.
- Định nghĩa callback UI bằng typed outcome hoặc mapping rõ thay vì Boolean làm mất contract. Nếu partial có active quyền hợp lệ, vẫn cho dùng tính năng theo entitlement policy nhưng message không nói hoàn tất mọi receipt. Silent sync không tạo sự kiện mua tương tác. Cached unresolved không trở thành “vừa khôi phục thành công”.
- **Regression:** A02–A05; full/mixed2failures/allpending/allfailed/partial0active/partialrevoke/cachedactiveunresolved, localgood+remote503/Rejected/NotConfigured, B706 selected contract, auth vs network error. Assert Manager message/result và dialog action, không chỉ parser enum. Sau thay đổi state assert profile/tier/billing prefs, watermark policy và Drive worker gate; projection/persistence failure vẫn không full success.
- **Acceptance:** status/count/freshness đúng xuyên backend→parser→reconciler→Manager→dialog; receipt còn hợp lệ không mất quyền vì lỗi receipt khác; feature gates dùng entitlement đúng. Checkpoint, tự chuyển X11.

## X11 — Kiểm chứng host độc lập và acceptance toàn bộ

- **Depends:** X10. **Files:** tests/docs/reports. Lỗi production mới phải quay lại gói sở hữu, không báo DONE với probe xanh nhưng acceptance thiếu.
- Chạy 27 probes vòng 7 (B706 theo contract ghi rõ), 12 probes gốc vòng6, 16 probes gốc vòng5 và regressions các vòng trước trong full suites. Chạy full Android/backend, lintDebug, assembleDebug; lưu XML/log/exit code, không lấy số tests từ báo cáo trước.
- Nếu chuyển đủ probes vào suite thường, số tối thiểu dự kiến **964 Android/108 backend** trước các regression biến thể mới. Không dùng con số này làm bằng chứng; kiểm đếm actual results. Nếu khác số, giải thích skip/merge/rename, không xóa test để đạt PASS.
- Bắt buộc chạy biến thể đã nêu ở từng gói: two-connection/reopen CAS; production V2 different-SKU; pending→cancel unknownRTDN; canceled-tombstone+linkedACTIVE payload qua backend và Android; noncooperativeHTTP; owner trước reconnect; expired token/readiness sau await; exact per-token counts/UI; projection failure; watermark/Drive gates.
- Snapshot before/after diff, xác minh scope và bảo toàn unrelated changes. Không sửa báo cáo lịch sử để xóa evidence. Không gọi unit/mock suite là Android device/end-to-end acceptance.
- **Acceptance:** tests/build/lint đạt, mỗi Fxx có production path + regression + kết quả actual; không còn host defect trong scope. Nếu fail tự sửa/kiểm tra lại các phần liên quan rồi tiếp tục X12. Khi đủ matrix, không mở rộng test vô hạn.

## X12 — External gates và bàn giao cuối một lần

- **Depends:** X11. **Files:** `REPORT_VIP_R7_FINAL.md`, `docs/billing/ROUND7_ACCEPTANCE.md`, progress. Không tự release/deploy hoặc dùng secrets/giao dịch thật.
- Nếu môi trường test đã được cấp và dùng được, chạy Play license tester: pending/cancel upgrade differentSKU, renewal/grace/hold/refund/revoke/lifetime, cross-device/Play account khác app account; OAuth missing/expiry/re-auth; live RTDN retry/duplicates và staging restart; Android process death/account switch/destroy/signed upgrade; PDF watermark export và Drive backup/restore thực.
- Hiện audit ADB không có thiết bị; Play/OAuth/liveRTDN/Drive đều chưa chạy. Ghi từng gate `NOT_RUN/BLOCKED_EXTERNAL`, điều kiện tiếp tục và chứng cứ có/không. Thiếu môi trường không chặn hoàn tất code/tests/docs độc lập; không ngụy tạo device/network validation.
- Bảng nghiệm thu: F01–F09 → Xxx → production files → regression → host result → external result. Dùng trạng thái `FIXED_VERIFIED_HOST`, `FAILED`, `NOT_RUN`, `BLOCKED_EXTERNAL`; không “100% coverage/sẵn sàng release” khi external gate cần thiết chưa chạy.
- **Acceptance:** final report có actual counts/files changed, contract B706, kết quả mỗi Fxx, hạn chế, external checklist và việc thật sự cần người dùng; chỉ báo cáo một lần cuối sau khi tự làm mọi gói có thể thực hiện.

## Lệnh chính

Chạy từ root workspace. Phải lưu evidence ngay vì focused Gradle run ghi đè XML của run trước:

```powershell
$env:GRADLE_USER_HOME='C:/Users/nguye/.gradle'
./gradlew.bat :app:testDebugUnitTest --rerun :app:lintDebug :app:assembleDebug --offline --console=plain
node --experimental-strip-types --test backend/billing-verifier/test/*.test.ts
./gradlew.bat -I docs/vip-round7-20261001/audit.init.gradle :app:testDebugUnitTest --tests com.tscanner.app.AndroidAgentAuditTest --tests com.tscanner.app.RootVipAuthAuditTest --tests com.tscanner.app.RootVipParserAuditTest --offline --console=plain
node --experimental-strip-types --test docs/vip-round7-20261001/backend-agent-probes.test.ts
./gradlew.bat -I docs/vip-round6-20261001/audit.init.gradle :app:testDebugUnitTest --tests com.tscanner.app.VipRound6AuditTest --offline --console=plain
node --experimental-strip-types --test docs/vip-round6-20261001/backend-probes.test.ts
./gradlew.bat -I docs/vip-round5-20260930/audit.init.gradle :app:testDebugUnitTest --tests com.tscanner.app.VipRound5AuditTest --offline --console=plain
node --experimental-strip-types --test docs/vip-round5-20260930/backend-probes.test.ts
```

Nếu môi trường Java/cache khác, dùng runtime hợp lệ hiện có; không thay release config để chạy tests. Signature đổi chỉ adapt harness có ghi rõ, không xóa ý nghĩa hành vi của assertions.

## Prompt giao một lần cho Gemini trong Antigravity

```text
Làm việc tại E:\DU AN AI\T-Scanner.
Đọc PLAN_VIP_GEMINI_ANTIGRAVITY_ROUND7_AUTORUN_2026-10-01.md,
RECHECK_VIP_FULL_ROUND7_2026-10-01.md và docs/vip-round7-20261001/README.md.

Tôi giao bạn thực hiện toàn bộ X00–X12 theo thứ tự từ đầu đến cuối.
Tự sửa, tự chạy tests, tự chẩn đoán và chuyển gói khi đạt acceptance;
không hỏi hoặc chờ tôi sau từng gói. Sau gián đoạn, đọc progress rồi
xác minh current checkout để tiếp tục từ gói chưa xong.

Giữ changes hiện có; không reset/clean/stash/drop. Mỗi lỗi phải có
regression production red trước sửa rồi green sau sửa, đủ các biến thể
ghi trong kế hoạch. Không chỉ làm xanh probes gốc, không bỏ/nới test,
không thêm mock credential vào production, không bypass owner/CAS/auth.
B706 là policy check: ghi contract và test đầu cuối rõ, không coi nó là
bằng chứng cấp VIP sai. Các ca lifecycle phải phản ánh Google thực tế.

Lưu PROGRESS_VIP_R7_AUTORUN.md và REPORT_VIP_R7_Xxx.md từng gói.
Dependency chưa PASS thì tự quay lại sửa; không đánh dấu DONE giả.
Thiếu device/credentials ghi NOT_RUN/BLOCKED_EXTERNAL và tiếp tục phần
độc lập, chỉ tổng hợp vấn đề thật sự cần tôi ở cuối.

Không tự commit/push/deploy/publish, thay signing/SKU/giá hoặc mua bằng
tiền thật. Kết thúc bằng REPORT_VIP_R7_FINAL.md và
docs/billing/ROUND7_ACCEPTANCE.md với actual test counts, evidence cho
F01–F09, phạm vi đã kiểm chứng và external gates chưa chạy.
```
