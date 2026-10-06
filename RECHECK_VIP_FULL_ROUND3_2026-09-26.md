# Kiểm tra VIP và tính năng liên quan — vòng 3, 26/09/2026

## Kết luận

**Còn lỗi; chưa đủ điều kiện nghiệm thu mua/khôi phục VIP.** Checkout đã có nhiều cải thiện so với vòng 2, nhưng có 12 nhóm phát hiện bên dưới. Không sửa mã sản phẩm hay test gốc trong lần kiểm tra này. Harness độc lập nằm trong `build/vip-audit-20260926/`.

Đã đọc BillingManager, verifier Android/backend, auth, store, reconciliation, RTDN, outbox, hook startup/login/foreground, dialog VIP, quyền watermark ở Viewer/ID card/OCR, worker và catalog Drive. Đây không phải chứng nhận mọi nhánh UI/runtime của toàn ứng dụng.

## Bằng chứng thực thi

| Kiểm tra | Kết quả mới của checkout hiện tại |
|---|---|
| Android `:app:testDebugUnitTest --rerun :app:lintDebug :app:assembleDebug --offline` | BUILD SUCCESSFUL, **906 tests, 0 failures/errors/skipped** |
| Lint debug XML | **0 errors, 777 warnings** |
| Backend test hiện có | **64/64 PASS** |
| Probe Android bổ sung | **9/9 FAIL bằng assertion**, không phải lỗi compile |
| Probe backend bổ sung | **7/7 FAIL bằng assertion** |
| ADB | Có 1 thiết bị model 2210132C; chưa cài/chạy bản audit trên thiết bị |
| Play license tester, Google identity/API thật, RTDN thật, Drive thật, restart Android, hai thiết bị | **NOT RUN** |

Lần chạy Gradle đầu bị chặn ghi cache wrapper ngoài workspace; chạy lại với quyền cache thích hợp đã thành công. ADB lần đầu bị sandbox chặn daemon, lần đọc lại có quyền đã thấy thiết bị. Không diễn giải hai lỗi môi trường này thành lỗi ứng dụng.

Artifact: `baseline.log`, `baseline-counts.json`, `baseline-test-results/`, `backend-baseline.log`, `VipIndependentAuditTest.kt`, `audit.init.gradle`, `android-probes.log`, `android-probe-results.xml`, `backend-probes.test.ts`, `backend-probes.log` trong thư mục harness. Các probe sử dụng logic production, fake transport/Play/SharedPreferences để điều khiển lỗi và thời điểm; không dùng receipt, token hay dữ liệu người dùng thật.

## F01 — P1 — Auth production vẫn dùng HMAC thử nghiệm, không xác thực Google identity đúng contract

- `backend/billing-verifier/src/auth.ts:83,149-169,265`: mặc định có khóa development trong mã; chỉ chặn `alg=none`, mọi thuật toán khác vẫn được kiểm bằng HMAC; `exp` không bắt buộc, issuer/audience chỉ kiểm khi có cấu hình. Pub/Sub cũng dùng HMAC thay vì xác minh chữ ký Google.
- `index.ts:60,250` tạo AuthService mặc định, không cung cấp adapter Google qua custom verifier. Token Google thật không được đường mặc định này xác minh đúng. Chưa kiểm tra deployment ngoài repository; không khẳng định endpoint public hiện đang bị khai thác.
- Hai probe FAIL: token tổng hợp thiếu các claim bắt buộc vẫn được chấp nhận khi dùng cấu hình development; token khai RS256 nhưng ký HMAC vẫn được chấp nhận.
- Tác động: cấu hình mặc định có thể cho caller giả danh owner; cấu hình secret riêng vẫn không giải quyết việc nhận Google ID token/PubSub token thật.
- Nghiệm thu: bỏ fallback khóa development ở production; Google signature/key rotation + issuer/audience/expiry bắt buộc; Pub/Sub kiểm service account/email_verified; không nhúng khóa server vào app. Fail startup/readiness nếu thiếu cấu hình bắt buộc. Test positive với khóa RSA tổng hợp và JWKS giả tại boundary, không chỉ HMAC tự ký.

## F02 — P1 — Adapter Android chưa được nối vào đường chạy thật; readiness chỉ kiểm chuỗi URL

- `BillingManager.kt:52` lấy `PlayPurchaseVerifier.getInstance()` không tham số. Tìm toàn `app/src/main` không có lời gọi `PlayPurchaseVerifier.configure(...)` hay chỗ cấp backend URL/tokenProvider cho singleton.
- `TScannerApplication.kt` khởi tạo auth rồi gọi sync nhưng không bootstrap verifier. `VipPurchaseActionCoordinator.kt:128` chặn mua khi verifier chưa cấu hình: gate này đúng, nhưng hiện bản chạy thật luôn ở gate đó, không chỉ thiếu thao tác deploy bên ngoài.
- `PlayPurchaseVerifier.kt:133-134` coi cả HTTP và URL không hợp lệ/nonempty là configured; `:226` cho phép gửi không có Authorization. Probe `httpEndpointMustNotBeReady` FAIL. Có URL vẫn chưa chứng minh session/token sẵn sàng.
- Nghiệm thu: config URL theo build/environment được đọc thật trước khi tạo BillingManager; HTTPS validation, token gắn phiên và refresh/re-auth; integration test cold start/login/logout/foreground sử dụng production bootstrap. Không bật local fallback để vượt gate.

## F03 — P1 — Danh sách Play rỗng tự thu hồi quyền server và tự tăng version trên client

- `BillingReconciliation.kt:149-200`: khi SUBS/INAPP đều rỗng, đổi mọi Play entitlement thành REVOKED và `snapshotVersion + 1` tại client.
- Probe `emptyDeviceCatalogMustPreserveServerEntitlement` FAIL: quyền server còn hạn bị thu hồi khi thiết bị không có receipt. Trường hợp hợp lệ: cùng app account trên thiết bị có Play account khác.
- Android restore/sync chỉ query Play rồi verify từng receipt; không có đường lấy `/api/v1/billing/restore`/snapshot theo app account. Vì vậy cũng không lấy được quyền của tài khoản nếu catalog thiết bị rỗng.
- Tác động phụ: watermark bật lại, Drive bị chặn; version client tự tạo có thể xung đột snapshot server cùng version.
- Nghiệm thu: server quyết định thu hồi, client không mint server version; danh sách thiết bị là candidate tokens, không là toàn bộ quyền app account; xử lý offline, promotion, account switch, response cũ và partial restore.

## F04 — P1 — ID entitlement thay đổi khi hết hạn/thu hồi, client giữ cả quyền active cũ

- `backend/.../verifier.ts:263` cấp active với id `${source}_${purchaseToken}`, nhưng `:142,193` tạo EXPIRED/REVOKED có id bằng token.
- `BillingEntitlement.kt:177,189` merge theo `id`, không theo token; snapshot mới không xóa entry active có id khác.
- Backend probe `entitlement ID must remain stable after authoritative expiry` FAIL. Android probe `expiredServerIdMustReplaceActiveToken` FAIL: cùng token có entry EXPIRED mới nhưng snapshot vẫn VIP vì entry active cũ.
- Nghiệm thu: token có identity ổn định trong cả lifecycle; migration/dedupe dữ liệu đã lưu giữ trạng thái mới nhất theo nguồn authoritative, không tùy tiện xóa token khác. Test active→expired/revoked→renewal, reload và snapshot replay.

## F05 — P1 — Android bỏ thông tin thu hồi authoritative từ server

- Backend trả `REJECTED` kèm entitlement hết hạn/thu hồi (`verifier.ts:157,208`). Android `PlayPurchaseVerifier.kt:356` chỉ parse reason/message; `PurchaseVerifier.kt` không mang tombstone trong Rejected.
- `BillingManager.kt:902` chỉ báo thất bại, không cập nhật store. Probe `rejectionMustPersistAuthoritativeRevocation` FAIL: server nói PURCHASE_REVOKED nhưng cache vẫn active.
- Tác động: đặc biệt nguy hiểm với lifetime không có thời hạn tự hết; thiết bị có receipt stale/nonempty sẽ không đi vào nhánh query rỗng.
- Nghiệm thu: chuyển snapshot/tombstone hợp lệ đến store; phân biệt revoked/expired authoritative với token unknown, ownership conflict, auth/network lỗi. Không revoke mọi rejection. Kiểm lại watermark/Drive sau khi nhận revocation và sau restart.

## F06 — P1 — Parse thiếu/sai dữ liệu theo hướng tự cấp quyền

- Android `PlayPurchaseVerifier.kt:324-350`: unknown/missing state trở thành VERIFIED_ACTIVE, thiếu expiry trở thành null/lifetime, thiếu nhiều field lấy từ request. Chưa kiểm response owner/token/product khớp request hay HTTP success hợp lệ đầy đủ.
- Probe `malformedStateMustNotBecomeActive` FAIL: SUCCESS với state UNKNOWN và thiếu expiry thành quyền active không thời hạn.
- Backend `googlePlayClient.ts:335` thiếu purchaseState mặc định PURCHASED; HTTP 200 `{}` đi xuyên production transport và verifier thành lifetime SUCCESS. Probe `empty Play JSON must not grant lifetime VIP` FAIL.
- Nghiệm thu: schema strict theo loại sản phẩm/state, finite timestamps/version, binding owner/token/product/package; dữ liệu không đủ phải error, không grant. HTTPS/readiness thuộc F02. Kiểm unknown states, null, NaN, thiếu field, HTTP error có body SUCCESS, response nhầm account/token.

## F07 — P1/P2 — Kết quả mua/restore không phản ánh quyền đã áp dụng

- `BillingManager.kt:825`: chỉ coi VERIFIED_ACTIVE là success, dù `BillingEntitlement.isCurrentlyActive()` cho phép CANCELED_ACTIVE/IN_GRACE_PERIOD. Probe canceledPaidPeriod FAIL: paid period vẫn hiệu lực, quyền được lưu nhưng completion=false; UI báo gói không hoạt động. Phần thông báo sai này là P2.
- `BillingEntitlementStore.kt:98-135` trả success khi commit merged snapshot giữ bản mới hơn. `BillingManager.kt:995-1028` dựa trên Boolean, không kiểm state cuối cùng. Probe staleActiveSnapshot FAIL: store REVOKED v9, nhận ACTIVE v1 bị bỏ qua, callback vẫn true (P1).
- `BillingManager.kt:1014` bỏ qua Boolean của `AppAuthManager.applyEntitlementSnapshot`; hàm này `AppAuthManager.kt:1199` commit store lần hai. Probe secondStoreCommitFailure FAIL: commit 1 thành công, commit 2 fail, profile vẫn FREE nhưng callback true (P1).
- Nghiệm thu: một durable commit; profile/UI lấy snapshot đã commit, propagate lỗi, trả result typed gồm replay/stale/conflict/storage failure và trạng thái active cuối cùng. Không báo kích hoạt từ response đã bị bỏ qua. Giữ session guards ở cả callback sau acknowledge.

## F08 — P1 — Version tăng không bảo vệ thứ tự cập nhật nghiệp vụ

- `sqliteDriver.ts:44-52` tăng version cho mọi update, kể cả dữ liệu cũ vừa trả về. Không CAS với version trước query Play.
- `rtdnHandler.ts:61,162,189,194,249` dùng Map timestamp trong RAM, check trước await, chọn state theo eventType; không kiểm lại thứ tự khi commit. Nhiều process/restart cũng không chia sẻ watermark event.
- Hai probe FAIL: RTDN renew cũ hoàn thành sau revoke mới làm REVOKED→VERIFIED_ACTIVE; verify expired cũ trả sau renewal mới làm ACTIVE→EXPIRED.
- Nghiệm thu: contract authoritative state dùng subscriptionsv2/current state, transaction/CAS hoặc serialize per token có cơ chế dùng được qua process; retry query sau conflict, không áp lại dữ liệu cũ bằng version cao hơn. Event dedupe/watermark bền vững; xử lý one-time bằng trạng thái Play hiện hành. Test latch đảo callback, concurrent handlers/connections, restart.

## F09 — P1 — Acknowledge còn bỏ sót state và có khoảng trống crash

- `verifier.ts:298`: chỉ enqueue khi state VERIFIED_ACTIVE; CANCELED_ACTIVE còn hạn, chưa acknowledge không vào queue. Probe canceled active unacknowledged FAIL, queue có 0 item.
- Commit entitlement (`sqliteDriver.ts:80/135`) và enqueue outbox (`verifier.ts:299`, `sqliteDriver.ts:188`) là hai transaction/call tách rời. Process chết ở giữa có thể để receipt chưa acknowledge không có recovery job. Đây là CODE VERIFIED; chưa chạy kill-process fault injection.
- Android vẫn acknowledge trực tiếp (`BillingManager.kt:835`), backend worker cũng acknowledge: chưa thống nhất chủ thể và recovery contract.
- Nghiệm thu: durable grant + outbox atomic, bao phủ mọi purchased state còn quyền hợp lệ; một chính sách ack rõ ràng, idempotent retry và recovery sau crash. Không coi client phải mở lại app là bảo đảm recovery.

## F10 — P2 — Worker Drive không kiểm lại VIP sau thao tác chờ

- `GoogleDriveBackupWorker.kt:60` kiểm quyền đầu hàm, `:147` lấy OAuth token, `:175` bắt đầu upload mà không kiểm lại quyền/session/cancellation.
- Probe driveWorkerMustRecheckVipAfterTokenWait FAIL: token provider mô phỏng VIP bị thu hồi trong lúc chờ; upload vẫn được gọi 1 lần thay vì 0. Không có dữ liệu Drive thật được gửi.
- Logout đã yêu cầu cancel WorkManager, nhưng các bước REST đồng bộ chưa có checkpoint sau chờ. Không suy ra từ probe này rằng tài liệu A đã bị gửi sang Drive B; probe chỉ chứng minh gate VIP bị vượt sau thay đổi quyền.
- Nghiệm thu: recheck entitlement, session generation, owner, cancellation trước remote mutation và local commit; phân biệt request đã gửi với request chưa bắt đầu, không hứa thu hồi được byte đã gửi. Giữ snapshot retry/CAS/không nhân đôi file.

## F11 — P1 — Backend vẫn có thể khởi động với store chỉ trong RAM

- `store.ts:20` mặc định `:memory:` nếu không có DATABASE_URL/SQLITE_PATH; `index.ts` khởi tạo mặc định, `/health` chỉ trả UP.
- SQLite driver thật đã có, nên không còn đúng nếu nói toàn bộ store chỉ là Map. Nhưng cấu hình production thiếu path vẫn chạy và mất owner/version/outbox khi restart. CODE VERIFIED; chưa kiểm storage deployment ngoài repository.
- Nghiệm thu: production bắt buộc storage durable và fail closed nếu thiếu/memory; tách test factory dùng memory; readiness không báo ready khi storage/auth/API config thiếu. Quy định persistent volume và giới hạn multi-instance hoặc DB dùng chung; không đặt SQLite ephemeral lên cloud rồi gọi durable.

## F12 — P2 — Báo cáo hoàn tất vượt bằng chứng

- `REPORT_VIP_BILLING_ROUND2_Q12.md:6,19-31,101-102` đánh dấu ALL COMPLETED, RESOLVED và architecture READY/PASS. Probe hiện tại bác bỏ kết luận hoàn tất auth, reconciliation, persistence/result, RTDN và transport schema.
- Số 893 tests có thể là snapshot cũ, không tự nó là báo cáo sai; lượt hiện tại 906 tests không phải chứng minh các lỗi mới được sửa.
- Nghiệm thu: bảng F01–F12→gói sửa→test/log cụ thể; tách IMPLEMENTED/PROBE PASS/CODE VERIFIED/NOT RUN/EXTERNAL GATE. Không đổi expected test sang hành vi sai để xanh.

## Những điểm đã tốt hơn và giới hạn kiểm tra

- Pending fixture hiện đã dùng đúng representation SDK; fallback không tự bật do classpath JUnit; có product allowlist, preflight chặn mua khi verifier chưa có URL, immutable operation context, store conflict cho equal version, promotion được bảo toàn ở nhánh query rỗng.
- Có HTTP transport, SQLite driver và AckWorker thật; RTDN đã không đánh dấu processed trước query lỗi ở đường tuần tự. Các cải thiện này được giữ lại khi sửa.
- Watermark policy central đã recheck VIP tại các callsite export Viewer/ID card/OCR được đọc; chưa tìm thấy lỗi độc lập mới ở các callsite này. Quyền VIP sai từ F03–F07 vẫn làm chúng quyết định sai theo state đầu vào.
- Các bài login/continuation/owner isolation/cloud hiện có nằm trong baseline 906 tests đã chạy. Chưa kiểm Google consent thật, UI rotation/process death, download/export Office trên thiết bị hoặc mọi race của Drive. Không gắn nhãn PASS cho các mục đó.

## Chạy lại và tài liệu đối chiếu

```powershell
$env:GRADLE_USER_HOME='C:/Users/nguye/.gradle'
./gradlew.bat -I build/vip-audit-20260926/audit.init.gradle :app:testDebugUnitTest --tests com.tscanner.app.VipIndependentAuditTest --offline --console=plain
node --experimental-strip-types --test build/vip-audit-20260926/backend-probes.test.ts
```

Hai command hiện kỳ vọng đỏ vì đang chứng minh lỗi. Không dùng init script này cho baseline bình thường; nó chỉ thêm harness audit vào test source set. Kết quả baseline đã được lưu riêng trước khi probe ghi đè report Gradle.

Google yêu cầu kiểm chữ ký và claim ID token: [Authenticate with a backend server](https://developers.google.com/identity/sign-in/android/backend-auth). Pub/Sub dùng token do Google ký: [Authentication for push subscriptions](https://docs.cloud.google.com/pubsub/docs/authenticate-push-subscriptions). Subscription current state cần lấy từ nguồn authoritative: [Subscription lifecycle](https://developer.android.com/google/play/billing/lifecycle/subscriptions), [purchases.subscriptionsv2](https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.subscriptionsv2).

Kế hoạch Gemini 3.8 Flash: `PLAN_VIP_GEMINI_3_8_FLASH_ROUND3_2026-09-26.md`.
