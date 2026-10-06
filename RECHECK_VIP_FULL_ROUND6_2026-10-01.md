# Kiểm tra độc lập VIP vòng 6 — 01/10/2026

## Kết luận

Các **16 probe gốc vòng 5 đã PASS**, nhưng còn **7 nhóm lỗi** được tái hiện qua **10 FAIL / 12 phép thử bổ sung** (2 đối chứng PASS). Báo cáo vòng 5 đúng về các probe gốc; chưa đủ chứng cứ cho kết luận đã hoàn tất mọi điều kiện nghiệm thu của U01/U05/U06/U07/U08/U09.

Lượt này chỉ kiểm tra, viết probe, lưu chứng cứ và lập kế hoạch. Không sửa production code, không thay release config, không commit/push/deploy; giữ các thay đổi đang có trong checkout.

Kế hoạch sửa: [Gemini Antigravity vòng 6 autorun](PLAN_VIP_GEMINI_ANTIGRAVITY_ROUND6_AUTORUN_2026-10-01.md).

## Chứng cứ và kết quả

| Kiểm tra | Kết quả kiểm tra mới |
|---|---|
| Android unit suite hiện có, task `--rerun` | 937 tests, 0 failures/errors |
| Backend suite hiện có | 96/96 PASS |
| Probe nguyên gốc vòng 5 | Android9/9 và backend7/7 PASS |
| `lintDebug` / `assembleDebug` | BUILD SUCCESSFUL; lint0 errors757warnings; các task này UP-TO-DATE |
| Probe Android vòng 6 | 9 tests, 8 FAIL, 1 PASS |
| Probe backend vòng 6 | 3 tests, 2 FAIL, 1 PASS |
| ADB sau khi cho phép chạy daemon ngoài sandbox | Danh sách thiết bị rỗng |
| Play license tester / Google SDK thật / live RTDN / Drive thật | NOT RUN |

Phép thử dùng logic production qua seam Play/HTTP/SharedPreferences và `BillingManager.syncPurchases/restorePurchases/destroy`. Không gọi tài khoản thanh toán/Drive thật. A05 chủ động cho lần commit entitlement thứ hai trả false để kiểm tra propagation, không phải một lỗi host ngẫu nhiên.

Toàn bộ probe, XML và log mới được giữ tại `docs/vip-round6-20261001/`. Baseline XML toàn suite lưu tại `build/vip-audit-20261001/baseline-xml/`; XML probe vòng 5 được lưu trước khi chạy probe mới. Chỉ có audit source set được thêm bằng init script, không đưa probe thất bại vào suite mặc định.

## R01 — P1: Server entitlement chỉ được refresh khi Play trả danh sách hoàn toàn rỗng

- Mã: `app/src/main/java/com/tscanner/app/utils/billing/BillingReconciliation.kt:146-165,254-299`.
- **A01 FAIL**: seed quyền ACTIVE của tài khoản; Play trả một giao dịch PENDING khác. Reconciler trả NoActivePurchases sớm, **0 lần gọi restore backend**. Quyền server đã bị revoke không được cập nhật; pending local không thể dùng làm kết luận về quyền khác của app account.
- **A02 FAIL**: seed lifetime của tài khoản chỉ có ở backend, đã refunded trong fixture; Play có một subscription PURCHASED nhưng backend xác định PAUSED. Chạy **BillingManager.syncPurchases** thật. Chỉ subscription trên thiết bị được xử lý; **0 lần remote restore** để refresh lifetime còn trong cache.
- Tác động: account-owned quyền cũ có thể tiếp tục mở watermark/Drive, hoặc quyền mua trên thiết bị khác không được khôi phục, tùy các receipt đang thấy trong Play.
- Acceptance: restore/sync luôn có bước hợp nhất device candidates và known app-account receipts, kể cả pending, nonempty hoặc lỗi query theo policy; không tự revoke từ query rỗng/error, không cấp paid cho pending. Có test dữ liệu trộn subscription/lifetime và hai Play accounts.

## R02 — P1: Mất thông tin lỗi/partial và báo khôi phục đầy đủ từ cache chưa giải quyết

- Backend: `backend/billing-verifier/src/verifier.ts:430-459`. Android: `PurchaseVerifier.kt:100-105`; `PlayPurchaseVerifier.kt:679-687`; `BillingReconciliation.kt:182-211`; `BillingManager.kt:1082-1083`.
- **B01 FAIL**: seed lifetime ACTIVE; Play trả 404 cho receipt đã biết. Backend ghi per-token REJECTED nhưng aggregate vẫn SUCCESS với ACTIVE cache. Không yêu cầu xóa quyền mù quáng từ 404; vấn đề là không phản ánh refresh chưa giải quyết mà báo fresh full success.
- **A03 FAIL**: remote RestoreResult.Partial chứa một receipt active và thông báo một receipt chưa refresh được; reconciler trả Restored với failedCount=0, totalCount=count. Manager hiển thị “Đã khôi phục thành công ...” như đầy đủ. Parser không giữ `results[]` và message của backend.
- **B03 PASS đối chứng**: backend mixed good + transient failure trả PARTIAL đúng. Lỗi fidelity tiếp tục nằm ở Android; nhánh backend permanent rejection vẫn chưa đúng.
- Acceptance: trạng thái overall/per-token/freshness đi xuyên server→parser→reconciler→UI; phân biệt full/partial/pending/unresolved/no-active. Áp dụng được receipt hợp lệ, giữ cache khi hợp lý, nhưng không đổi unresolved thành xác thực mới thành công. Partial0-active không báo khôi phục quyền thành công.

## R03 — P1: Restore chạy ngoài scope BillingManager, vẫn commit sau destroy

- Mã: `BillingReconciliation.kt:166`; `BillingManager.kt:347-355,1047-1068`.
- **A04 FAIL**: remote restore suspend; gọi `BillingManager.destroy()`; thả response success cùng tài khoản/generation. Entitlement vẫn được ghi. Coroutine reconciler tạo scope riêng chỉ có dispatcher, không thuộc scope bị hủy trong Manager.
- Guard owner/generation vòng 5 đã có và probe account-switch gốc PASS; nó không thay guard job/lifecycle. Một Manager mới cùng session có thể chạy trong khi Manager cũ vẫn trả kết quả.
- Acceptance: restore/sync thuộc scope/operation được quản lý, destroy hủy job; cancellation không bị đổi thành TransientError rồi phát callback. Không side effect/callback từ tác vụ đã hủy, nhưng response của thao tác mới cùng session vẫn hoàn tất.

## R04 — P1: Double commit còn trong restore; lỗi projection bị bỏ qua

- Mã: `BillingReconciliation.kt:189-205`; `AppAuthManager.kt:1203-1235`.
- **A05 FAIL**: commit snapshot lần đầu thành công; lần ghi lại vào entitlement store trong `AppAuthManager.applyEntitlementSnapshot` trả false. Reconciler bỏ qua Boolean này, vẫn trả Restored và set billing flag true; profile vẫn Free. Vì vậy watermark/Drive tiếp tục dùng trạng thái Free dù báo khôi phục thành công.
- Đường processPurchase đã dùng `projectSnapshotToProfile` với kết quả checked tại `BillingManager.kt:1004-1017`; đường remote restore chưa dùng cơ chế đó.
- Acceptance: một lần commit entitlement; project snapshot đã commit bằng API có sẵn, kiểm tra lỗi lưu profile; flags/result chỉ success khi các state phục vụ tính năng nhất quán. Có test persist=false/exception, restart và hướng revoke VIP→Free.

## R05 — P2: Thiếu ID token chưa đi vào re-auth

- Mã: `VipUpgradeDialog.kt:29-33`; `VipPurchaseActionCoordinator.kt:117-135`; `PlayPurchaseVerifier.kt:194-210`.
- **A06 FAIL**: user có email nhưng idToken=null; resolver vẫn ActivateVip. Cả resolver và coordinator chỉ kiểm tra expired khi token không blank. Preflight production sau đó false và trở về lỗi dịch vụ thay vì đăng nhập lại.
- Vòng 5 đã sửa token có exp quá hạn, nhưng bỏ sót missing/blank token. `isTokenExpired` còn coi token malformed/missing-exp là chưa expired; đây là phần contract cần kiểm tra thêm, không phải probe riêng đã chạy.
- Acceptance: missing/expired session cùng đi qua auth recovery, backend misconfiguration có lỗi riêng. Credential lỗi không launch Play; resume đúng một lần sau re-auth; cancel/account switch/repeated click không mua hộ phiên khác. Không áp luật signed JWT cho scheme giả lập/dev mà chưa tách mode rõ.

## R06 — P2: Restore vẫn tự khớp productType và chấp nhận provider source sai

- Mã: `PlayPurchaseVerifier.kt:497-515,539-545,653-675`.
- **A07 FAIL**: yearly subscription nhưng productType=inapp vẫn RestoreResult.Success. Expected type được lấy từ chính response, không từ catalog.
- **A08 FAIL**: receipt yearly có source=PROMOTIONAL vẫn Success. `valueOf` nhận source enum hợp lệ mà không kiểm tra có đúng provider/product không; source không biết lại bị fallback sang Google Play.
- Tác động đã chứng minh là parser nhận response sai contract. Không khẳng định có exploit server thật; backend hiện tạo source/type đúng. Sai identity provider/source còn có thể làm token/version merge không nhất quán khi payload lỗi.
- Acceptance: SKU quyết định type/source allowed, required field không tự coerce. Test duplicate token/conflicting source, null/missing owner, source lạ, expiry/version/timestamps sai, cả success và tombstone. Receipt restore chưa có trên thiết bị vẫn hợp lệ.

## R07 — P2: Canonical pending-purchase-canceled/linked token chưa được hỗ trợ

- Mã: `backend/billing-verifier/src/googlePlayClient.ts:324-336,357-376`; `src/verifier.ts:141-190` và `src/rtdnHandler.ts` lifecycle switch.
- **B02 FAIL**: HTTP200 với SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED + linkedPurchaseToken bị adapter ném GooglePlayApiError400 “unknown state”. Không đến được logic truy vấn receipt cũ.
- Đây là enum hợp lệ; Google hướng dẫn dùng linkedPurchaseToken khi canceled pending purchase liên quan subscription đang tồn tại. [Google SubscriptionState](https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.subscriptionsv2#SubscriptionState).
- Acceptance: parse state/shape đó mà không cấp quyền cho purchase mới đã hủy; resolve linked receipt theo authoritative API và owner/CAS guard. Giới hạn chiều sâu, chống cycle/cross-owner; old ACTIVE/EXPIRED/REVOKED và mạng lỗi phải có outcome đúng. Không chỉ thêm enum rồi vẫn reject ở switch verifier.

## Những sửa đúng được xác nhận

- B01/B02/B07 vòng 5: expected-absent và CAS refetch đã giải quyết các race gốc được probe.
- ON_HOLD và thiếu V2 state đã được xử lý; metadata client không ghi đè known token trong restore.
- HTTP400, wrong owner/SKU, malformed item gốc không còn thành Success.
- Account-switch sau await được guard; stale version không làm success từ incoming ACTIVE cũ.
- Partial active hiện đã project profile trong trường hợp persist bình thường; A09 đối chứng vòng 6 xác nhận watermark gate đúng.
- Expired token có đường re-auth; thiếu token vẫn còn R05.

U09 vòng 5 chỉ đưa thêm đúng 7+9 probes gốc; các biến thể bắt buộc (persistence failure, canceled pending/linked token, scope destroy, partial error fidelity, device catalog mixed) chưa được chứng minh đầy đủ bằng báo cáo đó. Không cần hoàn tác các sửa đã đúng; cần bổ sung acceptance còn thiếu.

## Tái hiện tại repository root

```powershell
$env:GRADLE_USER_HOME='C:/Users/nguye/.gradle'
./gradlew.bat -I docs/vip-round6-20261001/audit.init.gradle :app:testDebugUnitTest --tests com.tscanner.app.VipRound6AuditTest --offline --console=plain
node --experimental-strip-types --test docs/vip-round6-20261001/backend-probes.test.ts
./gradlew.bat -I docs/vip-round5-20260930/audit.init.gradle :app:testDebugUnitTest --tests com.tscanner.app.VipRound5AuditTest --offline --console=plain
node --experimental-strip-types --test docs/vip-round5-20260930/backend-probes.test.ts
./gradlew.bat :app:testDebugUnitTest --rerun :app:lintDebug :app:assembleDebug --offline --console=plain
node --experimental-strip-types --test backend/billing-verifier/test/*.test.ts
```

Hai probe vòng 6 hiện phải fail đúng số liệu. Các test gốc vòng 5 phải giữ PASS. Khi API đổi, chỉ adapt harness theo contract được ghi rõ; không xóa assertion hành vi để làm xanh.

## Giới hạn nghiệm thu

ADB đã khởi động thành công ngoài sandbox nhưng không có thiết bị kết nối. Chưa chạy signed upgrade/reinstall, Play license testing, account switch/provider lifecycle thật, OAuth refresh, live RTDN + delivery/retry, deployment restart/volume thực, hai thiết bị, watermark PDF export/Drive thực. Host checks và mocked transport không chứng minh những gate này.
