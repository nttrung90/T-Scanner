# Tái kiểm tra VIP Billing sau B00–B12 — vòng 2, 26/09/2026

## Kết luận và phạm vi

**Còn lỗi, chưa thể nghiệm thu thanh toán thật.** Có 12 nhóm phát hiện R01–R12 bên dưới. Probe bổ sung có **11 assertion thất bại: 10 về logic production, 1 về fixture kiểm thử**. Các kết quả này không phải lỗi biên dịch.

Kiểm tra checkout hiện tại ở `E:\DU AN AI\T-Scanner`, gồm Android client và `backend/billing-verifier`. Đã giữ nguyên mã sản phẩm/test gốc và các thay đổi có sẵn. Chỉ tạo báo cáo, kế hoạch và harness trong `build/vip-billing-reaudit2/`. Không triển khai hoặc kiểm thử trên dịch vụ production.

Các cải thiện nhìn thấy trong mã: bỏ fallback tự cấp trial từ nút mua, dependency lên Billing 8.0.0, có allowlist, mô hình entitlement tuyệt đối, connection waiter, tách origin sync/purchase, hook login/foreground và observer giá. Probe đối chứng replay cùng entitlement không cộng ngày đã PASS. Những cải thiện này không đủ để kết luận toàn bộ B00–B12 hoàn tất.

## Bằng chứng thực thi mới

| Kiểm tra | Kết quả |
|---|---|
| `:app:testDebugUnitTest --rerun :app:lintDebug :app:assembleDebug --offline` | BUILD SUCCESSFUL; **834 tests, 0 failures/errors/skipped**; task test được chạy lại |
| Lint debug XML | **0 errors, 767 warnings** |
| Backend test gốc | **21/21 PASS** |
| Android probe độc lập | **10 tests: 8 FAIL, 2 PASS**, 0 errors/skipped |
| Backend probe độc lập | **4 tests: 3 FAIL, 1 PASS** |
| ADB | Không có thiết bị kết nối |
| Play Console, release/license tester, Google API thật, deployment | NOT RUN |

Thư mục bằng chứng `build/vip-billing-reaudit2/`: `baseline.log`, `baseline-counts.json`, `backend-baseline.log`, `BillingRound2ProbeTest.kt`, `audit.init.gradle`, `probes.log`, `probe-results.xml`, `backend-probes.test.ts`, `backend-probes.log`.

Android probe gọi BillingManager/AppAuthManager/store/reconciler thật, dùng BillingClientWrapper fake và verifier seam có kết quả kiểm soát. Probe HTTP dùng endpoint server thật bind loopback ephemeral, receipt tổng hợp, không gửi thông tin người dùng thật; server đã đóng sau test. Không đồng nhất fake verifier PASS với xác thực Play thật.

## R01 — P1 — Đường xác thực thật chưa được triển khai, không chỉ thiếu Console/credential

- `app/src/main/java/com/tscanner/app/utils/billing/PlayPurchaseVerifier.kt:171-176`: `verifyViaRemoteBackend()` luôn trả MissingBackendGate, không thực hiện HTTP kể cả có URL. Singleton mặc định cũng chưa được cấu hình URL ở đường chạy thật.
- `backend/billing-verifier/src/googlePlayClient.ts:81-131`: cả 4 hàm production query/ack đều throw; thêm GOOGLE_APPLICATION_CREDENTIALS vẫn gặp `Not implemented`.
- `controlConfiguredRemoteVerifierStillReportsMissingImplementation` PASS xác nhận adapter client cấu hình URL vẫn bị chặn. Đây là đối chứng hành vi gate, không phải test tính năng mua thành công.
- Nút mua hiện chưa có preflight gate theo readiness verifier; nếu sản phẩm Play đã khả dụng, app có thể mở thanh toán rồi không xác thực/cấp VIP được.
- Test tự bật local fallback khi tìm thấy JUnit (`PlayPurchaseVerifier.kt:27-43`), nên môi trường test và release khác đường xử lý.
- Nghiệm thu: adapter HTTP/auth/serialization và Google API thật có implementation; contract tests qua transport boundary; chưa deploy/credential thì báo gate rõ, không cho mua khi biết chắc không xử lý được. Không thêm credential vào app.

## R02 — P1 — Giao dịch đã thuộc A bị dựng lại thành guest rồi gắn cho B

- `BillingManager.kt:991-1034`: khi guest store rỗng nhưng boolean billing chung=true, dựng `LEGACY_LOCAL/VERIFIED_ACTIVE` từ last receipt chung, rồi bind vào current user. Không kiểm owner gốc/server.
- `BillingEntitlementStore.kt:121-147`: chuyển tất cả guest sang user và tăng version cục bộ.
- Probe `probeOwnedReceiptMustNotReappearAsGuestAndBindToB` FAIL: A có receipt đã verified, đổi B, bind => B được VIP từ receipt đó. B10 gọi bind trên login thật tại `AppAuthManager.kt:1296` nên đây không phải helper không được dùng.
- Nghiệm thu: migration legacy là unverified, không tự cấp paid; quyền chỉ chuyển/gắn theo owner được server xác thực; global last receipt không là nguồn cấp quyền; guest không tự tăng version server. Test A/B/C và login lại phải dùng hook thật.

## R03 — P1 — Thu hồi sai tầng dữ liệu, sai nguồn quyền và sai phiên tài khoản

- `BillingReconciliation.kt:128-139` chỉ `setUserVipTier(FREE)` cho current user tại lúc callback; không lưu revoke vào BillingEntitlementStore, không capture owner/session, không phân biệt nguồn PROMOTIONAL.
- `AppAuthManager.kt:1304...` ưu tiên store khi load, vì vậy quyền cũ có thể quay lại khi login/reload.
- Ba probe FAIL: `probeRevokedReceiptMustNotReturnOnReload`, `probeEmptyPlayQueryMustPreservePromotion`, `probeOldEmptyQueryMustNotRevokeNewAccount`.
- Tái hiện: Play trả rỗng -> profile FREE nhưng load lại thành VIP; hoặc A query chậm rồi B login -> B bị revoke; hoặc không có sản phẩm Play nhưng promotion còn hiệu lực bị hạ FREE.
- Nghiệm thu: reconciliation gắn owner/session và snapshot version, revoke bền vững đúng nguồn; snapshot trễ không ghi đè purchase mới. Cần phân biệt catalog của Play account hiện tại với entitlement app account: query rỗng trên thiết bị khác không được tự suy ra mọi quyền server của app account mất hiệu lực.

## R04 — P1 — Restore dùng owner của luồng mua trước

- `BillingManager.kt:269,614,739`: `activePurchaseOwnerUserId` được lưu chung, dùng cả RESTORE/RECONCILE, không reset theo từng operation. Không có session generation trong context này.
- Probe `probeRestoreMustNotReusePreviousPurchaseOwner` FAIL: owner cũ A, current user B restore => request verifier vẫn ghi A.
- Nghiệm thu: mỗi purchase/restore/reconcile có immutable operation context; owner của restore lấy ở lúc bắt đầu restore, không mượn biến purchase chung; callback A->B->A phải vẫn kiểm generation/operation, không chỉ ID trùng.

## R05 — P1 — Lưu entitlement thất bại vẫn báo mua thành công

- `BillingManager.kt:872,886,889` bỏ qua Boolean của store và AppAuthManager; `processPurchase` sau đó callback true. Với receipt chưa ack, code còn ack trước khi thử lưu client.
- Probe `probeCommitFailureMustNotReportPurchaseSuccess` FAIL: store commit=false nhưng callback success=true.
- Nghiệm thu: chỉ báo thành công khi entitlement đã được lưu theo contract; phân biệt payment accepted/verification/persistence failure, có recovery sau restart. Chốt một chủ thể acknowledge và thứ tự durable grant/ack, không dùng toast thành công để che lỗi lưu.

## R06 — P1 — Snapshot cùng version được phép hồi sinh quyền đã revoke

- `BillingEntitlement.kt:183`: dùng `incoming.snapshotVersion >= existing.snapshotVersion` thay vì bảo vệ dữ liệu cùng version khác payload.
- Probe `probeEqualVersionCannotResurrectRevokedState` FAIL: REVOKED version 2 rồi ACTIVE version 2 -> VIP active.
- Nghiệm thu: equal+identical là replay; equal+different là conflict giữ state cũ/báo lỗi; version cũ bị từ chối; renewal version mới hoạt động. Version server phải tồn tại qua restart; client migration không tự cạnh tranh version server.

## R07 — P1 — Endpoint backend không xác thực người gọi

- `backend/billing-verifier/src/index.ts:55-114`: verify/restore/ack lấy owner/body trực tiếp, không kiểm Authorization, principal hoặc quyền; RTDN cũng chưa kiểm push identity.
- Probe HTTP thật `probe HTTP restore endpoint must require authentication` FAIL: POST restore không Authorization, owner A do caller tự khai, purchases=[] nhận **HTTP 200**. Store được seed receipt tổng hợp, không Google API fake ở HTTP handler.
- Ở cấu hình hiện tại API Play chưa chạy, nhưng restore vẫn trả dữ liệu đã lưu cho owner caller chọn. Chưa kiểm tra gateway ngoài repo có bổ sung auth hay không; không coi lớp gateway không được chứng minh là bảo vệ mặc định.
- Nghiệm thu: user JWT/id token được xác minh issuer/audience/expiry; owner derive từ principal; ack authorization sau verify; RTDN xác minh Pub/Sub identity/audience. Mọi route unauth/mismatch phải chặn trước store/API mutation.

## R08 — P1 — Store và ack retry chỉ ở RAM, chưa có worker thực thi

- `store.ts:17-18`: Map ownership/entitlement; `ackService.ts:21-22`: Set/Map ack và retry. Không durable database/transaction giữa instance/process.
- `drainPendingQueue()` chỉ có định nghĩa và test gọi; production index không schedule worker. `/verify` không enqueue ack; ack là endpoint tách rời, client hiện ack qua SDK.
- Trạng thái CODE VERIFIED: restart xóa owner/version/queue; hai server process có thể nhận cùng token cho hai owner khi receipt không có hash ràng buộc. Snapshot version reset khiến client có version cao bỏ qua update mới.
- Nghiệm thu: database unique token-owner + monotonic version, transaction; outbox/retry bền vững có worker/recovery/backoff/idempotence. Không gọi Map là persistent store; không coi thiếu credential là lý do chưa thể viết persistence thật.

## R09 — P1 — RTDN lỗi lần đầu bị bỏ mất khi retry

- `rtdnHandler.ts:132` đánh dấu event processed trước query/update. API fail trả ERROR nhưng index.ts:110 luôn trả HTTP 200, tức ack push.
- Probe `probe RTDN retries same event after temporary Google API failure` FAIL: lần 1 ERROR, sau API hồi phục cùng event bị SKIPPED_STALE.
- Handler còn chọn trạng thái chủ yếu theo notificationType, không trạng thái authoritative đầy đủ; one-time notifications bị bỏ qua với PROCESSED. Những nhánh này chưa được probe riêng.
- Nghiệm thu: chỉ đánh dấu hoàn tất sau commit; lỗi retryable trả non-2xx hoặc durable enqueue trước ack; concurrency/reorder/restart an toàn; đọc trạng thái hiện hành từ Play, xử lý hoặc ghi rõ không hỗ trợ loại notification.
- Google mô tả push acknowledgement và retry ở [Push subscriptions](https://docs.cloud.google.com/pubsub/docs/push), xác thực ở [Authentication for push subscriptions](https://docs.cloud.google.com/pubsub/docs/authenticate-push-subscriptions).

## R10 — P1 — Backend restore trả quyền active cũ dù vừa xác minh đã hết hạn

- `verifier.ts:117,140`: expired/revoked chỉ return REJECTED, không cập nhật bản ghi cũ. `:241-250` restore bỏ kết quả verify từng receipt rồi trả toàn bộ store như SUCCESS.
- Probe `probe authoritative expired verification must not restore stale active receipt` FAIL: store đang active; Play xác minh expiry trong quá khứ; restore vẫn trả VERIFIED_ACTIVE với expiry cũ tương lai.
- Nghiệm thu: expired/revoked authoritative tạo snapshot/tombstone mới; transient error không biến thành expired hoặc success mới; restore có kết quả partial/error rõ, không giữ cache bị phủ định bởi dữ liệu authoritative.

## R11 — P2 — Fixture pending tạo ra Purchase thực tế là PURCHASED

- `BillingTestFixtures.kt:108,120` ghi nguyên enum `Purchase.PurchaseState.PENDING` vào raw JSON. Constructor SDK diễn giải JSON khác enum public.
- Probe `probePendingFixtureMustRepresentPendingPurchase` FAIL: mong đợi state **2/PENDING**, getter trả **1/PURCHASED**.
- Đây là lỗi test, không khẳng định Play thật gửi sai trạng thái. Các bài pending dùng fixture này không kiểm đúng nhánh chờ thanh toán. Một số test suite cũng chủ động bật local fallback nên không bao phủ production adapter.
- Nghiệm thu: factory tạo raw payload đúng SDK, self-test getter PURCHASED/PENDING; test pending không verify/ack/grant; fake verifier chỉ inject ở test, không tự thay production behavior chỉ vì classpath có JUnit.

## R12 — P2 — Báo cáo nghiệm thu vượt bằng chứng và sai mapping lỗi

- `REPORT_VIP_BILLING_B12.md:14,20-34,117-119`: tuyên bố giải quyết triệt để, đổi mapping Fxx thành gói Bxx, thêm F00 không có trong audit gốc; nhắc SignatureVerifier nhưng không có implementation tương ứng được tìm thấy. Kết luận còn bước Console bỏ sót transport/auth/persistence/server API chưa làm.
- Step A cũng lặp lại kết luận quá mức. Ghi 834 tests pass là đúng với lượt chạy lại; suy ra đủ mọi nhánh/device/backend từ số đó là không đúng.
- Nghiệm thu: lập bảng F01–F11 gốc -> bằng chứng mới -> remaining Rxx, phân biệt IMPLEMENTED/PROBE PASS/CODE VERIFIED/NOT RUN; chưa có adapter/DB/auth thì ghi INCOMPLETE, không chỉ NOT DEPLOYED.

## Chạy lại

```powershell
$env:GRADLE_USER_HOME='C:/Users/nguye/.gradle'
./gradlew.bat -I build/vip-billing-reaudit2/audit.init.gradle :app:testDebugUnitTest --tests com.tscanner.app.BillingRound2ProbeTest --offline --console=plain
node --experimental-strip-types --test build/vip-billing-reaudit2/backend-probes.test.ts
```

Hai command probe hiện được kỳ vọng đỏ để chứng minh defect; bản ghi XML/log đã lưu riêng. Bản production sửa sau phải làm xanh các invariant, không đổi expected sang hành vi sai. Đối chứng MissingBackendGate cần thay bằng test transport/auth thật khi R01 hoàn tất.

Kế hoạch tiếp theo: `PLAN_FIX_VIP_PLAY_BILLING_ROUND2_SMALL_MODEL_2026-09-26.md`. Không triển khai sửa trong lượt audit này. Tham khảo thêm [Google: verify before granting entitlement](https://developer.android.com/google/play/billing/security).
