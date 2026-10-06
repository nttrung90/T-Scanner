# Gemini (Antigravity) sửa VIP vòng 6 — tự chạy W00 đến W10

## Mục tiêu và quy tắc autorun

Đọc `RECHECK_VIP_FULL_ROUND6_2026-10-01.md`, `docs/vip-round6-20261001/` và kết quả vòng 5. Giải quyết R01–R07, giữ 16 probes vòng5 PASS, hoàn tất mọi acceptance mới có thể kiểm chứng trên host.

Tự làm **W00 → W01 → … → W10** tuần tự. Không hỏi người dùng sau từng gói. Đây là tài liệu giao cho Gemini trong Antigravity; không phải xác nhận Gemini đã được khởi chạy trong chat Codex.

- Bảo toàn staged/uncommitted/untracked changes. Không reset/clean/stash/drop hoặc sửa file ngoài phạm vi. Không tự commit/push/deploy/publish, thay signing/SKU/giá, dùng secrets hay giao dịch tiền thật.
- Mỗi gói: xác minh current code → regression red → sửa đúng scope → focused tests green → ghi `REPORT_VIP_R6_Wxx.md`, cập nhật `PROGRESS_VIP_R6_AUTORUN.md` → tự chuyển gói tiếp.
- Checkpoint là điểm ghi chứng cứ nội bộ, không chờ người dùng phê duyệt. Nếu gói đã được sửa ở checkout, kiểm chứng rồi ghi SKIPPED_ALREADY_VERIFIED với chứng cứ, không làm lại.
- Test phải gọi production logic/seam, có đối chứng. Không loại assertion/nới expected behavior để đạt báo cáo xanh; không dùng suite fake tự mô tả chính implementation mong muốn.
- Blocker kỹ thuật tự xử lý trong scope. Thiếu external environment ghi NOT_RUN/BLOCKED_EXTERNAL; vẫn làm tiếp phần độc lập. Code dependency chưa PASS thì không đánh dấu gói phụ thuộc DONE. Chỉ tổng hợp câu hỏi cần người dùng thật sự ở báo cáo cuối.
- Báo cáo phải có before/after tests, command/result/count, file thay đổi, contract đã chọn, thiếu validation và bước tiếp. Khi tiếp tục sau interruption, đọc progress và xác minh checkout tương ứng.

## W00 — Baseline và regression bền vững

- Scope: `app/src/test/java/com/tscanner/app/VipRound6RegressionTest.kt`, `backend/billing-verifier/test/round6-regression.test.ts`, progress/report. Chưa sửa production.
- Chuyển 9 Android + 3 backend tests từ `docs/vip-round6-20261001/` vào suite đúng import/class. Dự kiến 10 FAIL/2 PASS. Probe gốc vòng5 phải16/16 PASS; baseline937 Android/96 backend, lint0 errors757warnings.
- Kiểm tra failure do production logic, không fixture/compile. A02 dùng BillingManager sync thật; A04 suspend response qua destroy; A05 fault injection SharedPreferences; giữ cấu trúc này.
- Acceptance: đủ map probe→Rxx, red evidence và đối chứng. Bàn giao code ownership; không chạy nhiều gói sửa chung file song song.
- Prompt gói: `Đọc PLAN_VIP_GEMINI_ANTIGRAVITY_ROUND6_AUTORUN_2026-10-01.md; làm W00 rồi tự chuyển W01.`

## W01 — Lifecycle canceled pending và linked receipt (R07)

- Depends W00. Files: backend `src/googlePlayClient.ts`, `src/verifier.ts`, `src/rtdnHandler.ts`, `src/types.ts`; helper shared lifecycle nhỏ nếu cần; tests.
- Đối chiếu Google API contract đã dẫn trong audit. Nhận canonical PENDING_PURCHASE_CANCELED; validation expiry/grant-time theo từng state, không ép shape ACTIVE cho canceled pending.
- Chỉ resolve quyền từ linked receipt được query authoritative. Không cấp paid cho canceled new receipt, không tự chuyển owner từ linked token, không bỏ CAS và hash binding. Dedupe/cycle/depth bound; không recursive loop vô hạn.
- Regression: B02; linked old ACTIVE/EXPIRED/REVOKED, chưa có record local, owner conflict, missing/cyclic linked token, transient upstream; cả verify và RTDN. Giữ mapping HOLD/PAUSED/GRACE/CANCELED, V2 missing state/SKU checks.
- Acceptance: không classify enum hợp lệ thành malformed; identity/state/version của old/new tokens rõ, old entitlement hợp lệ được bảo toàn hoặc invalidate đúng. Handoff result taxonomy cho W02.

## W02 — Restore backend giữ fidelity cho unresolved/per-token results (R02 backend)

- Depends W01. Files: `backend/billing-verifier/src/verifier.ts`, `src/types.ts`, `src/index.ts` route mapping nếu cần, tests. Không đổi auth/storage architecture.
- Định nghĩa complete/partial/pending/unresolved/transient/no-active theo kết quả thực. Known receipt 404/invalid/hash/owner error không thành full fresh SUCCESS với stale ACTIVE. Không tự revoke mọi cache từ upstream error.
- Giữ snapshot caching/freshness policy rõ; receipt hợp lệ vẫn apply được khi receipt khác lỗi. `results[]`, message và overall status phải nhất quán; hỗ trợ candidate mới rejected không che refresh known token. Giữ known-token metadata authoritative.
- Regression: B01/B03, mixed success + permanent rejection, all unresolved, pending, expired/refunded được giải quyết, offline, invalid client metadata; kiểm tra response HTTP endpoint từ authenticated principal.
- Acceptance: failure/per-token/freshness đi ra contract; không trả full success nếu chưa giải quyết các receipt cần refresh. Handoff JSON fixtures active/partial/no-active/error cho W03/W07.

## W03 — Android parser ràng buộc type/source theo catalog (R06)

- Depends W02. Files: `PlayPurchaseVerifier.kt`, `PurchaseVerifier.kt` result payload, verifier tests.
- SKU quyết định allowed productType/provider source. Không lấy response productType làm expected type; không fallback source lạ thành Play source. Required fields owner/token/version/expiry/type/source/timestamps được validate theo contract.
- Shared strict validation cho restore/success/tombstone. Backend-owned receipt chưa có local candidate vẫn hợp lệ. Định nghĩa policy legacy/promotional migration riêng nếu cần, không cho payload Play arbitrary source.
- Regression: A07/A08; source unknown/missing, type null/blank/mismatch, duplicate token/conflicting provider, item/snapshot owner thiếu/sai, timestamp/version malformed; valid remote-only receipt và HTTP/body mismatch controls.
- Acceptance: malformed không mutation hoặc thành Success; valid v2 fixtures W02 vẫn được nhận. Bàn giao typed results, chưa sửa orchestration ngoài nhu cầu compile.

## W04 — Restore/sync thuộc scope và operation có thể hủy (R03)

- Depends W03. Files: `BillingManager.kt`, `BillingReconciliation.kt`, operation context/scope plumbing tối thiểu, lifecycle tests.
- Bỏ coroutine root độc lập trong reconciler. Manager cung cấp managed scope/job hoặc suspend API được gọi trong scope có chủ sở hữu. Destroy cancel cả restore/sync đang chờ; scope mới hoạt động khi Manager mới được tạo.
- Capture operation owner/generation xuyên query/reconnect/token/HTTP/commit. Recheck cancellation và stale trước side effect; propagate CancellationException, không biến thành network error rồi callback. UI callback theo main-thread/lifecycle contract hiện có.
- Regression: A04, account switch gốc, A→B→A, destroy + late success/partial/error, new Manager same session, cancel trong token/HTTP/store wait. Dùng deferred await thật, kiểm tra cache/prefs/profile/callback.
- Acceptance: job cũ không còn mutation/callback sau destroy; operation mới hoàn tất. Handoff scope và cancellation contract cho W05.

## W05 — Hợp nhất toàn bộ account receipts cho mọi nhánh Play (R01)

- Depends W04. Files: `BillingReconciliation.kt`, `BillingManager.kt` restore/sync, request/result model nếu cần, tests.
- Backend restore theo app account không chỉ gọi khi local list rỗng. Hợp nhất known server receipts với local purchased candidates; pending không làm bỏ quyền khác; tách lỗi Play query với backend authority theo policy rõ.
- Thiết kế tránh duplicate network/ack/commit không cần thiết, nhưng không bỏ receipt server-only hoặc coi Play account = app account. Pending không cấp paid; local absent/error không tự revoke. Không bỏ qua guest/auth requirement.
- Regression: A01/A02; local none/pending/purchased/mixed/query-error, remote-only lifetime refund/active, paused subscription + lifetime, khác Play account, nhiều device tokens, transient remote. Test qua BillingManager sync/restore production.
- Acceptance: mọi receipt cần refresh đều được xét; profile/flags đúng hợp nhất; một pending/error không ngăn refresh quyền khác. Bàn giao canonical snapshot path cho W06.

## W06 — Một commit và propagation lỗi profile (R04)

- Depends W05. Files: `BillingReconciliation.kt`, `AppAuthManager.kt` projection nếu cần, prefs/profile tests. Dùng APIs đã có trước khi tạo abstraction mới.
- Sau `applySnapshotTyped` thành công, project snapshot đã commit qua `projectSnapshotToProfile`, không gọi lại API commit entitlement. Check Boolean/errors từ projection/persist; success callback/flag phải phù hợp.
- Chọn recovery khi entitlement đã commit nhưng profile persistence lỗi: retry projection/error rõ; không mint server version/rollback receipt bằng dữ liệu giả. Đảm bảo restart khôi phục từ entitlement authoritative; không giữ profile Free/VIP stale.
- Regression: A05; commit false/exception lần đầu, projection false/exception, stale/equal-version conflict, app restart, ACTIVE→REVOKED, multi-entitlement; gọi watermark helper và Drive worker seam để kiểm tra gate.
- Acceptance: một durable entitlement commit, feature state thống nhất, không success giả. Giữ processPurchase path đang dùng single commit/projection checked. Handoff committed result cho W07.

## W07 — Partial/unresolved đi tới UI đúng nghĩa (R02 Android)

- Depends W06. Files: `PurchaseVerifier.kt`, parser metadata trong `PlayPurchaseVerifier.kt`, `BillingReconciliation.kt`, `BillingManager.kt`, `VipUpgradeDialog.kt`, localized strings cần thiết, tests.
- Giữ overall/per-token message/status/count/freshness từ W02. Phân biệt full Restored, partial restored, pending/unresolved và no-active. Không tính cached ACTIVE chưa refresh như receipt thành công mới.
- Áp dụng entitlement/tombstone hợp lệ ngay, preserve receipt lỗi tạm thời theo policy; thông báo một phần rõ ràng, khả năng retry phù hợp. Partial0-active không báo khôi phục VIP thành công. Sync silent không phát sự kiện mua interactive.
- Regression: A03/B03 fixtures; partial1-good1-error, full, all-failed, partial revoke với cache khác, 0-active, auth error; assert UI message/count/result và hậu quả watermark/Drive. Không chỉ assert sealed-class parse success.
- Acceptance: không mất failures[] khi parse/merge; UI biết mức hoàn tất thật, typed result nhất quán sau W06. Handoff UI/auth error entry points cho W08.

## W08 — Missing/expired credential vào cùng auth recovery (R05)

- Depends W07. Files: `VipUpgradeActionCoordinator.kt`, `VipUpgradeDialog.kt` resolver, `VipLoginContinuationHandler.kt`, `PlayPurchaseVerifier.kt`, `AppAuthManager.kt` session API tối thiểu, readiness tests.
- Tách backend configured khỏi auth ready; thiếu/blank/expired credential yêu cầu re-auth/refresh được provider hỗ trợ. Đừng báo lỗi service không retry cho missing token. Production scheme có policy malformed/missing-exp fail closed; fake/dev scheme phải explicit.
- Restore và purchase cùng route auth-required; resume đúng một lần sau token fresh, owner/generation đúng. Kiểm tra readiness lần cuối trước launch/HTTP sau các await; backend cấu hình sai vẫn là lỗi cấu hình.
- Regression: A06; missing/blank/expired/malformed credential, valid token, SDK không trả token, re-auth success/cancel/error, delayed connection/product callbacks, account switch/double click, restore401; không gọi paid flow trước readiness.
- Acceptance: không stuck missing-token service error, không loop re-auth vô hạn, không mua dưới owner khác, không cấp trial/fallback. Giữ expired-token probe vòng5 PASS.

## W09 — Xác minh host độc lập và ma trận acceptance đầy đủ

- Depends W08. Files: tests/docs/reports; production lỗi mới chỉ quay lại gói sở hữu.
- Chạy đủ 12 probe vòng6, 16 probe nguyên gốc vòng5, regressions vòng3/vòng4, full Android/backend, lint/assemble. Commands trong audit. Nếu signature đổi, chỉ adapt harness có ghi rõ, giữ assertions hành vi.
- Bổ sung các biến thể ghi trong từng gói; bắt buộc hai connection SQLite/restart, partial failure, persist failure, lifecycle cancel, linked-token ownership và catalog mixed. Không gọi unit/mock suite là device/end-to-end acceptance.
- Acceptance: focused/all tests PASS, build/lint0 errors; báo số test mới thực tế, exit code/XML/log. Snapshot diff bảo đảm scope và bảo toàn user changes. Không copy số937/96 sau khi thêm test.
- Nếu host FAIL, tự quay lại gói sở hữu sửa rồi chạy kiểm tra liên quan; không chuyển sang báo DONE dựa chỉ12 probes. Khi đủ matrix, không tiếp tục mở rộng test vô hạn.

## W10 — Gate external và bàn giao cuối một lần

- Depends W09. Files: `REPORT_VIP_R6_FINAL.md`, `docs/billing/ROUND6_ACCEPTANCE.md`, progress; không sửa báo cáo lịch sử để xóa chứng cứ.
- Nếu có môi trường test đã cấp, chạy Play license tester (pending/recovery/refund/lifetime/restore cross-device), Google SDK/token expiry/missing recovery, RTDN delivery/retry, durable restart staging, signed reinstall/upgrade, process death/account switch, watermark export/Drive thực. Không tự deploy production hoặc mua thật.
- Không có môi trường: ghi từng gate BLOCKED_EXTERNAL/NOT_RUN với điều kiện/lệnh tiếp tục, tự hoàn tất phần báo cáo. Host audit01/10 hiện ADB devices rỗng; không ngụy tạo screenshot/log device.
- Acceptance matrix: R01–R07 → Wxx → production path → regression → host result → external result. Trạng thái riêng FIXED_VERIFIED_HOST/FAILED/NOT_RUN/BLOCKED_EXTERNAL; không “100%/production ready” nếu gate cần thiết chưa chạy.
- Final gồm files changed, actual counts, contract updates, lỗi còn lại, việc cần người dùng ở cuối. Nếu còn host defect, tiếp tục trong scope hoặc báo blocker cụ thể; không tự kết luận đã sửa toàn bộ.

## Prompt giao một lần cho Gemini trong Antigravity

```text
Làm việc tại E:\DU AN AI\T-Scanner.
Đọc PLAN_VIP_GEMINI_ANTIGRAVITY_ROUND6_AUTORUN_2026-10-01.md,
RECHECK_VIP_FULL_ROUND6_2026-10-01.md và docs/vip-round6-20261001/.

Tôi giao bạn thực hiện toàn bộ W00–W10 theo thứ tự. Tự sửa, tự chạy test,
tự chẩn đoán lỗi và chuyển gói khi đạt acceptance; không chờ tôi sau mỗi gói.
Giữ thay đổi hiện có, không mở rộng phạm vi. Mỗi lỗi phải có regression
bắt hành vi cũ trước khi sửa. Giữ 16 probes gốc vòng5 PASS; không làm xanh
bằng cách bỏ/nới test hoặc chỉ sửa đúng12 probe mà bỏ acceptance mở rộng.

Lưu PROGRESS_VIP_R6_AUTORUN.md và REPORT_VIP_R6_Wxx.md để tiếp tục sau
gián đoạn; đọc progress và xác minh checkout trước khi đi từ gói chưa xong.
Thiếu device/credentials ghi BLOCKED_EXTERNAL/NOT_RUN, tiếp tục phần độc lập
và tổng hợp một lần cuối. Không tự commit/push/deploy/publish, thay signing/
SKU/giá hoặc mua bằng tiền thật. Kết thúc bằng REPORT_VIP_R6_FINAL.md và
docs/billing/ROUND6_ACCEPTANCE.md có chứng cứ thực cho R01–R07.
```
