# Gemini Antigravity sửa VIP vòng 8 — tự chạy Y00 đến Y13

## Mục tiêu và cách tự thực hiện

Làm việc tại `E:\DU AN AI\T-Scanner`. Đọc [audit vòng8](</E:/DU AN AI/T-Scanner/RECHECK_VIP_FULL_ROUND8_2026-10-01.md>), [harness README](</E:/DU AN AI/T-Scanner/docs/vip-round8-20261001/README.md>), plan/báo cáo vòng7 và current checkout. Giải quyết **G01–G10**, giữ các fix đã được xác nhận bằng probes gốc. G09 là source-confirmed thiếu UI recovery, chưa có device validation; không gộp nó vào 23 assertion failures.

**Tự làm Y00 → Y01 → … → Y13 tuần tự từ đầu đến cuối, không hỏi hoặc chờ người dùng sau từng gói.** Đây là tài liệu giao Gemini trong Antigravity; chat Codex chưa khởi chạy Gemini.

- Bảo toàn staged/uncommitted/untracked changes. Không reset/clean/stash/drop hoặc ghi đè unrelated work. Không tự commit/push/deploy/publish, thay signing/SKU/giá/release config, dùng secrets trong logs hoặc giao dịch tiền thật.
- Mỗi gói: xác minh current production → regression red → sửa trong scope → focused tests green → ghi `REPORT_VIP_R8_Yxx.md` và `PROGRESS_VIP_R8_AUTORUN.md` → tự chuyển gói tiếp. Checkpoint không phải bước chờ phê duyệt.
- Nếu đã sửa ở checkout: kiểm tra đầy đủ rồi ghi `SKIPPED_ALREADY_VERIFIED` với evidence. Không dựa vào completion report hoặc chỉ tên enum/class để đánh dấu DONE.
- Không nới/bỏ assertions, dùng lifecycle không hợp lệ, thêm token giả vào production, bypass owner/hash/CAS/expiry/HTTPS hoặc revoke cache từ lỗi tạm thời để đạt tests. Signature/model đổi chỉ adapt fixture minh bạch, giữ behavioral invariant.
- File shared giữa các gói chỉ sửa tuần tự. Backend contract trước Android consumer. Không để agents sửa cùng `rtdnHandler.ts`, `verifier.ts`, `BillingReconciliation.kt`, `BillingManager.kt` hoặc UI files đồng thời.
- Tự xử lý lỗi compile/tests/integration trong scope, quay lại gói sở hữu nếu dependency chưa đạt. Thiếu device/credentials ghi `NOT_RUN/BLOCKED_EXTERNAL` rồi tiếp tục phần độc lập. Không đánh dấu code dependency DONE khi còn FAIL. Tổng hợp việc cần người dùng thật sự ở cuối một lần.
- Sau interruption, đọc progress và kiểm tra current checkout/evidence trước khi tiếp tục. Mỗi report có contract, files changed, before/after tests, actual counts, command/exit/XML/log, việc chưa chạy và bước tiếp.

Baseline độc lập: **964 Android /108 backend PASS**, **27 original R7 probes PASS**, lint **0 errors/757 warnings**, debug build PASS. Probes mới: **20 Android:14 FAIL/6 PASS**, **13 backend:9 FAIL/4 PASS**. 23 failures là 9 nhóm tái hiện, không phải 23 lỗi độc lập.

Prompt dùng lại ở mỗi checkpoint: `PLAN_VIP_GEMINI_ANTIGRAVITY_ROUND8_AUTORUN_2026-10-01.md — thực hiện Yxx theo scope/regression/acceptance; đọc progress và current code, lưu REPORT_VIP_R8_Yxx.md, rồi tự chuyển gói kế tiếp khi đạt, không chờ người dùng.`

## Y00 — Baseline, fixture integrity và regressions bền vững

- **Ownership:** tests/docs/progress, chưa sửa production.
- Lưu git inventory và fingerprint production/backend/config, chạy baseline mới và originals R7. Giữ mọi changes hiện có. Đối chiếu assertions được port trong R7 với harness gốc; các fix cũ không được đổi expected để làm xanh.
- Chuyển 12 Android agent probes và 8 root auth/purchase probes vào regression suites phù hợp, 13 backend probes vào `backend/billing-verifier/test/round8-regression.test.ts`; adapt imports/cwd/temp path/classname, không thay assertions hành vi. Evidence/harness gốc vòng8 giữ nguyên.
- **Fixture quan trọng:** C804/C805/C807 đi qua production coordinator→real BillingManager→BillingFlowParams→FakeBillingClient. `AuditTextUtils.kt` chỉ sửa primitive Android bị mock jar trả default sai; không phải app policy. Không copy shim vào main hoặc global normal test sourceSet tùy tiện. Chọn isolated JVM test job/sourceSet cho 3 cases này, hoặc runtime test Android phù hợp đã có. Nếu chưa có runtime, giữ harness isolated là regression chạy riêng; không thay real Manager bằng fake launcher để tránh exception.
- B801/B802/B809 phải gửi **identical** event/time, cùng process, reopen và hai physical connections. Không đổi event time mới để bypass bug. B803/B804 dùng new ACTIVE unack + old acknowledged, production V2 parser/matching owner hash; B810/B811 giữ payload Google không có subscriptionId và type 20. B805 giữ one-shot `bind.success=false` fault; đây là contract test, không gọi là exploit. B812/B813 giữ receipt lifetime đã verified trước refund và voided full refund envelope thực.
- A801 invariant là pending không thành full complete; khi result model có pendingCount riêng, adapt assertion theo incomplete/pending semantics thay vì bắt buộc pending là lỗi. A812 giữ quyền cached theo policy nhưng fresh count phải 0.
- **Acceptance:** đủ 33 cases→Gxx/controls; final pre-fix **23 FAIL/10 PASS**, không SDK fixture exception; B810 bắt và report exception production rõ. Registry run commands/evidence và checkpoint Y00. Nếu code đã sửa trước run thì ghi actual behavior, không giả tạo red. Tự chuyển Y01.

## Y01 — RTDN hoàn tất linked work trước khi consumed, durable retry (G01)

- **Depends:** Y00. **Files:** backend `src/rtdnHandler.ts`, storage/store APIs và migration tối thiểu nếu cần, RTDN route mapping/tests. Không đổi auth/canonical owner architecture.
- Same-event retry phải hoàn tất bước linked chưa xong dù token chính đã update. Memory watermark và durable event-time không được coi cả event hoàn tất khi linked query/storage/outbox thất bại.
- Chọn phương án nhỏ nhất có đủ semantics: sắp xếp side effects an toàn trước final completion hoặc durable pending/completion record cho dependency steps. Không chỉ xóa memory map vì restart/two-worker vẫn fail. Không bỏ stale guard/CAS hay reapply payload cũ để retry.
- Hoàn tất từng bước idempotent; có retry bounded, owner/hash/catalog đúng, event cũ không overwrite state mới. Nếu main update đã có mà linked pending, read state và finish việc còn thiếu; completion phải survive restart. Kiểm tra HTTP ERROR→503 và duplicate completed→2xx đúng policy.
- **Regression:** B801/B802/B809, linked503→same-event success, process crash giữa steps, storage false/throw, main commit succeeded+linked failed, duplicate complete, newer event trước older completion, two connections/reopen và outbox failure. B807 unknown pre-bind transient control vẫn PASS.
- **Acceptance:** same event không SKIPPED_STALE khi required work chưa xong; old receipt cuối khớp fresh authority; complete duplicates không cấp/ack hai lần; stale real events vẫn bị chặn. Checkpoint, tự chuyển Y02.

## Y02 — Unknown linked new receipt theo authority state và ack outbox (G02)

- **Depends:** Y01. **Files:** backend `src/rtdnHandler.ts`, verifier/shared authority helper, `src/types.ts` nếu cần; ack/store plumbing hiện có và tests. Không tự deploy/PubSub setup.
- Unknown token có linked record chỉ dùng owner khi proof/hash/catalog hợp lệ. Không đồng nhất mọi state với REVOKED. New ACTIVE paid upgrade phải bind đúng new SKU/state/expiry/version; old receipt refresh đúng và new ack-required phải vào durable outbox.
- Reuse normal verified purchase mapping/validation/CAS/ack behavior, tránh một RTDN implementation yếu hơn verifier. Notification type chỉ gợi ý query; authority state quyết định entitlement. PENDING không paid; canceled pending là tombstone; PAUSED/HOLD/EXPIRED/GRACE/CANCELED_ACTIVE theo contract hiện có.
- Account conflict/hash mismatch/catalog mismatch không bind sang owner khác. Unknown không có safe owner giữ unresolved/token-unknown policy rõ; không tự tạo anonymous paid owner. New/old khác SKU phải đúng V2 lineItems/catalog.
- Sửa payload model theo schema Google: SubscriptionNotification không yêu cầu subscriptionId. Resolve product identity từ stored receipt hoặc validated V2 lineItems/catalog, không persist undefined SKU. Query-by-token/canceled tombstone vẫn có identity an toàn; nếu không xác định được thì typed unresolved retry/error, không consumed hoặc cấp quyền bừa. Hỗ trợ type 20 pending-canceled theo authority, không yêu cầu client thêm SKU. Nguồn: [RTDN reference](https://developer.android.com/google/play/billing/rtdn-reference).
- **Regression:** B803/B804/B808/B810/B811; PubSub envelope type4/type20 không có subscriptionId; RTDN PURCHASED trước app verify cho yearly/monthly upgrade, ACTIVE unack/ack, same/different SKU, canceled pending, pending không grant, missing/ambiguous V2 product identity, owner/hash mismatch, old inactive, fresh authority mismatch notification, duplicate/new-worker/reopen và ack retry restart. Có HTTP route test cho production exception mapping, không chỉ direct handler.
- **Acceptance:** server giữ quyền new ACTIVE khi old EXPIRED; ack job durable/dedup đúng new token/SKU; không trả PROCESSED/REVOKED cho ACTIVE; envelope Google hợp lệ không lỗi SQLite do undefined productId. Normal verify/known-envelope controls và R7 linked CAS/different-SKU probes vẫn PASS. Checkpoint, tự chuyển Y03.

## Y03 — Check bind/outbox result trong unknown RTDN (G03)

- **Depends:** Y02. **Files:** `src/rtdnHandler.ts`, result handling helper/store nếu cần và focused tests. Không mở rộng DB schema không liên quan.
- Inspect `success`, `casConflict`, `staleIgnored`, owner conflict/storage failure sau bind. Unknown canceled bind false không được update processed watermark hoặc trả hoàn tất. Bounded re-read/re-query retry đúng owner/version; exhausted failure trả retryable result phù hợp.
- Khi token mới được worker khác bind trong lúc query, quay về known flow an toàn; không chèn tombstone version giả hoặc chuyển owner. Entitlement/outbox/completion nhất quán, không consumed khi required persist chưa xong.
- **Regression:** B805 one-shot CAS false→same event recover, bind throw/persistence false, retry exhausted, concurrent new bind same/foreign owner, existing newer event, outbox false sau entitlement commit và replay after restart.
- **Acceptance:** bind failure có recovery/typed error, same event chưa complete vẫn chạy tiếp; completed event idempotent; Y01 durable semantics giữ. Checkpoint, tự chuyển Y04.

## Y04 — Backend một final outcome cho mỗi receipt (G04 backend)

- **Depends:** Y03. **Files:** `src/verifier.ts`, typed result/contract docs nếu cần, tests. Giữ auth/storage/lifecycle đã sửa.
- Aggregate candidates/linked results bằng token identity xuyên toàn loop, không một nhánh upsert còn nhánh khác push. Dedupe không chỉ theo SKU. Final state/freshness/error phải phản ánh authoritative attempts, không “first SUCCESS wins” che query lỗi mới hoặc “last array item” tùy order.
- Ghi policy khi cùng token local/known/linked được verify nhiều lần: tránh network trùng nếu current authoritative outcome dùng được; unresolved refresh vẫn giữ per-token failure/pending/freshness. Snapshot và results phải nhất quán. Không chỉ `.distinct()` sau khi mất failure semantics.
- **Regression:** B806 new-before-old và old-before-new, same token nhiều candidate, linked success rồi direct503/rejected, direct failure rồi linked fresh success, pending trước paid hoặc receipt pending riêng, duplicate conflicting metadata, known-token authoritative SKU, all-rejected/pending policy R7 B705/B706. Không dựng ACTIVE→PENDING cho cùng receipt làm lifecycle acceptance; contradictory upstream payload chỉ là robustness test được ghi riêng.
- **Acceptance:** một final result per unique token; order không tạo counts/overall status sai; X04 contract và valid cache/access policy giữ. Handoff fixtures tới Y05/Y06, tự chuyển Y05.

## Y05 — Typed pending/unresolved/no-active trong Android (G05)

- **Depends:** Y04. **Files:** Android `PurchaseVerifier.kt`, `PlayPurchaseVerifier.kt` metadata models nếu cần, `BillingReconciliation.kt`, Manager/dialog mapping tối thiểu và tests. Không đổi entitlement authority.
- Giữ pending state từ Play local và backend results xuyên toàn restore/sync. Pending receipt chưa bound server không biến mất khi backend snapshot rỗng; giữ local pending terminal state hoặc gửi safe candidate metadata theo contract. Không acknowledge hoặc cấp paid entitlement khi authority còn PENDING; vẫn được query/verify authority để xác định trạng thái.
- Phân biệt completed-no-active, pending, unresolved/partial-no-active, auth-required, query error và active access. Quyền profile = active entitlement; mức hoàn tất restore = per-token outcomes. Không dùng `isVip=false` để xóa mọi failure/retry.
- ACTIVE + PENDING phải có incomplete/pending UI; pendingCount có thể riêng với failureCount, không ép pending là permanent rejection. EXPIRED + transient nhưng no active phải hiển thị retry/error phù hợp. Silent sync không tạo popup/sự kiện mua tương tác.
- **Regression:** A801/A802/A803/A806; all-pending, local-only pending, active+pending, pending+transient, expired/revoked+unresolved noactive, real completed-noactive control, auth/cancel, account switch và server query-error. Assert Manager message/action, không chỉ sealed class helper.
- **Acceptance:** pending không cấp VIP và không full-complete/no-purchases giả; no-active giữ reason/retry; backend parser/results fixtures đúng. Checkpoint, tự chuyển Y06.

## Y06 — Android token identity, final authority và fresh count (G04 Android)

- **Depends:** Y05. **Files:** `BillingReconciliation.kt`, `BillingManager.kt`, result model/`VipUpgradeDialog.kt`, strings/locales cho thông điệp mới và tests.
- Lưu outcome theo token thay vì chỉ số local successful/total. Cùng token thất bại hai nơi không cộng hai receipt failures. Remote authoritative success có thể resolve lỗi verification local cũ; phân biệt ack-pending/persistence/callback failure để không vô tình xóa việc còn cần retry.
- Fresh count chỉ từ receipt vừa được authority xác minh trong operation, không từ active cached snapshot size. Results remote rỗng giữ cache access policy nhưng không “vừa restore thành công” cache. Product list có thể phục vụ quyền đang active, không dùng nó làm fresh receipt total.
- UI summary gồm fresh resolved, pending, unresolved, cached-access khác nhau; no-active/completed/full/partial đúng. Không gọi post-upgrade activation từ cached-unresolved hoặc all-pending; partial có active hợp lệ có thể cho dùng quyền với message đúng mức hoàn tất.
- **Regression:** A804/A805/A812, unique2failures, same receipt local/remote verification error→fresh success, server success+local ack fail, duplicate/conflicting server results, cached ACTIVE + empty results, cached ACTIVE + unresolved, fresh active/revoke/watermark controls, projection/storage failure. Drive worker gate dùng profile committed đúng, không fake network validation.
- **Acceptance:** count/total/failure/pending/freshness nhất quán theo receipts; receipt hợp lệ không mất quyền do lỗi receipt khác; Manager/dialog message/action đúng contract Y04/Y05. Checkpoint, tự chuyển Y07.

## Y07 — Purchase operation owner/readiness trước mọi await và final launch (G07)

- **Depends:** Y06. **Files:** `VipPurchaseActionCoordinator.kt`, launcher interface/DefaultVipPurchaseLauncher, `BillingManager.kt`, operation context plumbing tối thiểu và tests.
- Capture owner/generation/action identity ngay khi bấm mua, pass xuyên reconnect/products/launch. Không capture B mới khi operation bắt đầu ở A. Callback cũ phải không mở Billing flow hoặc trả success về listener cũ sau account/session/lifecycle change.
- Recheck auth/config/activity/operation sau mọi await và ngay trước `billingClient.launchBillingFlow`. Token mới hết hạn hoặc bị xóa phải auth recovery; config lỗi là service config result riêng. Guard ở cả coordinator và Manager theo context contract, không chỉ fake launcher.
- Giữ coalesce double clicks/terminal callback/reset đúng operation identity; canceled/dismissed action không revive bằng old product callback. Operation mới của B vẫn hoạt động độc lập; không bỏ receipt đã thanh toán trong recovery policy.
- **Regression:** C804/C805/C807 qua production Manager+BillingFlowParams seam; A→B→A, token missing/expired sau reconnect/products, activity closed, verifier config đổi, duplicate delayed callbacks, new action sau cancel, valid unexpired controls. Token subject phải khớp account fixture; không dùng synthetic malformed để tạo false blocker.
- **Acceptance:** old owner hoặc expired session không tới BillingClient launch; same-owner valid delayed action launch một lần. Giữ R7 restore context/lifecycle tests PASS. Checkpoint, tự chuyển Y08.

## Y08 — Guard HTTPS/auth/session ở actual verify và restore transport (G08)

- **Depends:** Y07. **Files:** `PlayPurchaseVerifier.kt`, typed verify/restore result, Manager/coordinator mapping cần compile và tests. Không weaken server auth.
- Dùng shared preflight policy tại cả actual sending paths: endpoint hợp lệ HTTPS; token production có format/exp hợp lệ; owner/generation đúng trước/sau token retrieval và trước transport. Không chỉ check readiness ở UI hoặc restore-only guard.
- Invalid config trả config gate, expired/malformed/missing credential trả auth-required/re-auth được consumer hiểu. Verify không gửi HTTP vì URL nonblank; restore không gửi expired bearer vì token nonblank. CancellationException rethrow, không biến thành generic network error.
- Compatibility parser tests phải cấp synthetic valid credential explicit hoặc parse-only seam test riêng. Không dùng null-provider/test token fallback trong production để giữ tests xanh. Adapt fixture có ghi rõ, không nới payload/status assertions của original R5/R6/R7.
- **Regression:** C801/C802/C803/C806/C808, verify/restore cùng HTTPS/http/blank/malformed URL matrix, token null/blank/malformed/missing-exp/expired/unexpired, owner/generation đổi khi lấy token, 401/403 vs429/503, cancellation, zero transport calls khi not-ready. Không gửi mạng hoặc tokens thật.
- **Acceptance:** actual transport không bị gọi khi config/auth/session sai; valid HTTPS request gọi đúng; typed auth recovery tới Y09, server authoritative identity vẫn được giữ. Checkpoint, tự chuyển Y09.

## Y09 — UI auth recovery thực và continuation đúng action (G09)

- **Depends:** Y08. **Files:** `BillingManager.kt` typed outcome interface tương thích nếu cần, `VipUpgradeDialog.kt`, `VipLoginContinuationHandler.kt` hoặc continuation helper nhỏ, Home/More entrypoints, strings và tests. Không redesign login/Drive architecture.
- Preserve AuthRequired tới UI qua typed outcome/action thay vì chỉ `false,message`. Restore401 hoặc credential preflight yêu cầu sign-in/refresh hỗ trợ bởi provider. Phân biệt resume RESTORE với PURCHASE; login không tự mua gói nếu action ban đầu là restore.
- Home logged-in nhưng missing/expired token cũng có recovery callback; More/Home sign-in success/cancel/error có terminal UI state, không stuck disabled restore button. Sau fresh token resume một lần, đúng owner/generation; old dialog đã dismiss/session khác không nhận action/callback.
- Không coi toast “đăng nhập lại” là proof recovery. Không reset pending intent bừa làm resume nhiều lần, sign-in loop hoặc request Drive thay sign-in. Không tự trial/grant VIP.
- **Regression:** tạo production UI/coordinator seam/instrumentation tests cho actual callback invoked, restore401, expired token, Home logged-in/guest, More, sign-in success/cancel/error, delayed fresh token, A→B→A, double events, dialog dismissed, resume đúng RESTORE/PURCHASE một lần. C05 gốc vẫn PASS nhưng message assertion chỉ là một phần acceptance.
- **Acceptance:** có evidence action→auth→fresh token→original action, cancellation/error/retry đúng; UI/device thật ghi riêng NOT_RUN nếu thiếu môi trường. Checkpoint, tự chuyển Y10.

## Y10 — Chặn SDK/listener callbacks của manager đã dispose (G06)

- **Depends:** Y09. **Files:** `BillingManager.kt`, callback dispatch/operation guard tối thiểu và lifecycle tests. Không xóa processing receipt của manager mới hoặc storage recovery.
- Guard destroyed/job/operation tại SDK entrypoint và direct pending/terminal paths; guard lại khi main-thread dispatched runnable thực thi. Runnables đã queue trước destroy cũng không gọi listener cũ sau dispose. Scope cancellation chỉ đủ cho cooperative work, không thay callback lifecycle ownership.
- Hủy/clear registrations/processing flags thuộc instance đã destroy theo policy; action mới/new Manager hoạt động. Không phát false terminal UI khi receipt paid được recover sau đó; old listener và old context bị suppress.
- **Regression:** A807/A808/A811, USER_CANCELED/OK+PENDING/ERROR/ITEM_ALREADY_OWNED sau destroy, Runnable queued trước destroy, listeners removed/dismissed, stale owner, double destroy, new manager same session completes. Preserve no store/profile/prefs/ack mutation controls từ R7/A811.
- **Acceptance:** disposed SDK callbacks và queued UI listener không leak; noncooperative verify guard vẫn PASS; receipt legitimate được manager mới restore đúng. Checkpoint, tự chuyển Y11.

## Y11 — Xử lý voided full refund lifetime bằng RTDN (G10)

- **Depends:** Y10; reuse durable completion Y01–Y03. **Files:** backend `src/rtdnHandler.ts`, payload/result models, shared verifier/store helper tối thiểu, route/tests. Không đổi push authentication hoặc chế độ deployment.
- Nhận `voidedPurchaseNotification` theo schema Google, dùng bound token/owner/product identity và fresh Play authority để cập nhật known full-refunded lifetime thành REVOKED. Không coi nó là non-billing PROCESSED; không thay bằng oneTime pending-canceled type2. `productType=2/refundType=1` là ca full refund one-time hiện đã tái hiện. Nguồn: [RTDN reference](https://developer.android.com/google/play/billing/rtdn-reference).
- Thực hiện idempotent, CAS/event ordering và durable retry trước khi consumed. Fresh Play503/storage failure phải retry cùng event sau restart; completion không được che side effects chưa xong. Không revoke từ foreign package, unknown/foreign owner, malformed notification hoặc transient authority failure. Unknown token giữ unresolved policy rõ.
- Phân biệt subscription voided/full refund và partial quantity refund: refresh đúng authority/catalog, không áp dụng blanket full-revoke cho mọi refundType/productType. Validate unsupported/ambiguous payload và ghi typed result; không lặng lẽ report full refund handled khi chưa xử lý. Không mở rộng thêm sản phẩm/refund business policy ngoài VIP hiện có.
- **Regression:** B812/B813; full-refunded lifetime qua actual Base64 PubSub envelope, duplicate same event, restart/two connections, Play503→retry, bindfalse/throw→retry, newer receipt update, ordinary restore control, foreign package/unknown token/owner conflict, partial refund không blanket revoke; subscription type có mapping an toàn theo authority. Assert HTTP route status và durable snapshot Android nhận được.
- **Acceptance:** known refunded lifetime không còn active do RTDN ignored; ordinary verify/restore vẫn đúng; no false revoke và same-event failure retry works. Record code/host evidence riêng live Google refund gate. Checkpoint, tự chuyển Y12.

## Y12 — Kiểm chứng host xuyên tầng và ma trận đầy đủ

- **Depends:** Y11. **Ownership:** tests/docs/reports. Production lỗi mới quay lại gói sở hữu; không chỉ làm xanh original probes để đánh dấu hoàn tất.
- Chạy đủ 33 R8 probes, 27 originals R7, R5/R6 originals/permanent regressions, các regression vòng trước trong full suites, lintDebug/assembleDebug. Actual counts phải từ XML/log; không copy 964/108 sau khi thêm tests.
- Nếu port đủ 20 Android/13 backend vào normal suites, số trước variants mới dự kiến 984/121. Nếu 3 SDK integration cases giữ isolated job do fixture/runtime, normal Android có thể 981 và isolated3; báo rõ partition, không drop test hoặc tính hai lần. Có thể có counts lớn hơn sau thêm variants/G09 tests; record actual commands/count/exit/skip.
- Bắt buộc variants từng gói: same-event503 retry+reopen/two-worker, unknown ACTIVE upgrade+durableack, canonical payload không subscriptionId, bindfalse not-consumed, both candidate orders/dedupe, pending/noactive/inactivepartial/cachedfresh, token authority cuối, final purchase owner/readiness, actual HTTPS/auth guards, UI auth continuation, direct+queued postdestroy callbacks, voided fullrefund/retry. Thêm JSON fixture backend→Android→Manager/dialog kiểm tra summary/action và quyền sau refund.
- Kiểm tra fingerprint/diff, không sửa unrelated work hoặc overwrite evidence lịch sử. Lint warning baseline không là device pass. Không gọi unit/seam suites “100% code coverage/end-to-end/production ready”.
- **Acceptance:** mỗi Gxx có production fix/path, regression before/after, contract, actual host result; không host defect unresolved trong scope. Nếu FAIL tự quay lại sửa, chạy các checks liên quan rồi chuyển Y13. Không mở rộng audit vô hạn khi đủ matrix.

## Y13 — External gates và bàn giao cuối một lần

- **Depends:** Y12. **Files:** `REPORT_VIP_R8_FINAL.md`, `docs/billing/ROUND8_ACCEPTANCE.md`, progress. Không tự release/deploy/purchase bằng tiền thật.
- Nếu môi trường test đã cấp dùng được: Play license tester upgrade differentSKU/pendingcancel/refund/renewal/lifetime/restore khác device/account, RTDN đến trước app verify+same-event redelivery, staging backend restart/outboxack; OAuth expiry/missing/re-auth UI Home/More; Android process death/reconnect/account switch/dismiss/destroy/signed upgrade; real PDF watermark export và Drive backup/restore.
- Audit ADB hiện rỗng; Play/OAuth/liveRTDN/Drive đều chưa chạy. Thiếu môi trường ghi từng gate `NOT_RUN/BLOCKED_EXTERNAL`, điều kiện/lệnh tiếp tục và evidence hiện có, vẫn hoàn thành code/tests/docs độc lập. Không ngụy tạo screenshot/device/network acceptance.
- Matrix: G01–G10→Yxx→production path→regression→host result→external result. Dùng `FIXED_VERIFIED_HOST`, `FAILED`, `NOT_RUN`, `BLOCKED_EXTERNAL`. G09 phải có auth action/continuation evidence, không chỉ text message; G10 có voided fullrefund riêng với pending-canceled.
- **Acceptance:** báo cáo cuối một lần có files changed, actual tests/counts/fixture partition, contract changes, từng Gxx, lỗi còn lại và việc thật sự cần người dùng. Không “đã hoàn tất toàn bộ/100%” nếu còn thiếu acceptance hoặc external gate bắt buộc.

## Lệnh chính từ root workspace

```powershell
$env:GRADLE_USER_HOME='C:/Users/nguye/.gradle'
./gradlew.bat :app:testDebugUnitTest --rerun :app:lintDebug :app:assembleDebug --offline --console=plain
node --experimental-strip-types --test backend/billing-verifier/test/*.test.ts
./gradlew.bat -I docs/vip-round8-20261001/audit.init.gradle :app:testDebugUnitTest --tests com.tscanner.app.AndroidRound8AuditTest --tests com.tscanner.app.RootRound8AuthAuditTest --offline --console=plain
node --experimental-strip-types --test docs/vip-round8-20261001/backend-round8-probes.test.ts
./gradlew.bat -I docs/vip-round7-20261001/audit.init.gradle :app:testDebugUnitTest --tests com.tscanner.app.AndroidAgentAuditTest --tests com.tscanner.app.RootVipAuthAuditTest --tests com.tscanner.app.RootVipParserAuditTest --offline --console=plain
node --experimental-strip-types --test docs/vip-round7-20261001/backend-agent-probes.test.ts
./gradlew.bat -I docs/vip-round6-20261001/audit.init.gradle :app:testDebugUnitTest --tests com.tscanner.app.VipRound6AuditTest --offline --console=plain
node --experimental-strip-types --test docs/vip-round6-20261001/backend-probes.test.ts
./gradlew.bat -I docs/vip-round5-20260930/audit.init.gradle :app:testDebugUnitTest --tests com.tscanner.app.VipRound5AuditTest --offline --console=plain
node --experimental-strip-types --test docs/vip-round5-20260930/backend-probes.test.ts
```

Lưu XML/log sau từng run trước khi Gradle ghi đè. Harness signature đổi chỉ adapt minh bạch, giữ original invariants. Đối với standalone main-thread SDK/device acceptance, host mock/seam chưa đủ; dùng runtime test hợp lệ hoặc ghi external gate riêng.

## Prompt giao một lần cho Gemini trong Antigravity

```text
Làm việc tại E:\DU AN AI\T-Scanner.
Đọc PLAN_VIP_GEMINI_ANTIGRAVITY_ROUND8_AUTORUN_2026-10-01.md,
RECHECK_VIP_FULL_ROUND8_2026-10-01.md và docs/vip-round8-20261001/README.md.

Tôi giao bạn thực hiện toàn bộ Y00–Y13 theo thứ tự từ đầu đến cuối.
Tự sửa, tự chạy tests, tự chẩn đoán và chuyển gói khi đạt acceptance;
không hỏi hoặc chờ tôi sau từng gói. Sau gián đoạn, đọc progress rồi
xác minh current checkout để tiếp tục từ gói chưa xong.

Giữ changes hiện có; không reset/clean/stash/drop. Giữ các fixes cũ
đã xác nhận bằng original R7 probes. Mỗi lỗi mới phải có regression
production red trước sửa và green sau sửa, đủ variants trong plan.
Không chỉ làm xanh probes mà bỏ acceptance mở rộng; không nới/bỏ test,
không thêm token giả vào production hoặc bypass owner/CAS/auth/HTTPS.
Giữ fixture lifecycle thực tế; không copy AuditTextUtils vào main/global
test sourceSet tùy tiện. Pending không cấp VIP; cached access khác fresh
restore. G09 cần actual auth continuation, message-only không đủ.
G02 phải dùng payload Google không có subscriptionId; G10 phải xử lý
voided full refund lifetime, không đánh đồng pending-canceled với refund.

Lưu PROGRESS_VIP_R8_AUTORUN.md và REPORT_VIP_R8_Yxx.md từng gói.
Dependency chưa PASS thì tự quay lại sửa, không đánh dấu DONE giả.
Thiếu device/credentials ghi NOT_RUN/BLOCKED_EXTERNAL và tiếp tục phần
độc lập; chỉ tổng hợp vấn đề thật sự cần tôi ở cuối một lần.

Không tự commit/push/deploy/publish, thay signing/SKU/giá hoặc mua bằng
tiền thật. Kết thúc bằng REPORT_VIP_R8_FINAL.md và
docs/billing/ROUND8_ACCEPTANCE.md có actual test counts, fixture partition,
evidence cho G01–G10 và external gates chưa chạy.
```
