# Kiểm tra độc lập VIP sau sửa vòng 6 — vòng 7, 01/10/2026

## Kết luận

**Còn 9 nhóm lỗi cần sửa. Chưa đủ điều kiện kết luận luồng VIP hoàn tất hoặc sẵn sàng release.** Các kiểm tra cũ đạt nhưng chưa bao phủ hết acceptance W01–W08. Kiểm tra này chỉ đọc production, chạy tests và tạo tài liệu/probes; không sửa mã sản phẩm, cấu hình release hoặc thay đổi hiện có của người dùng.

Kế hoạch thực hiện tiếp: [Gemini Antigravity X00–X12](</E:/DU AN AI/T-Scanner/PLAN_VIP_GEMINI_ANTIGRAVITY_ROUND7_AUTORUN_2026-10-01.md>). Các gói chạy tuần tự tự động, không cần người dùng theo dõi từng gói.

## Phạm vi và chứng cứ

Đối chiếu kế hoạch vòng 6, progress, các báo cáo W00–W10, `REPORT_VIP_R6_FINAL.md`, `docs/billing/ROUND6_ACCEPTANCE.md` với checkout thực tế. Đọc lại đường verify/restore/sync, linked receipt, storage CAS, RTDN, auth readiness, parser, callback lifecycle, VIP dialog, profile, watermark và Drive gates. Tests gọi production classes/seams, dùng dữ liệu tổng hợp; không gọi Google/payment/Drive thật.

| Kiểm tra đã chạy | Kết quả hiện tại |
|---|---|
| Full Android JVM suite, chạy lại test task | **946 tests, 0 failures, 0 errors** |
| `lintDebug`, `assembleDebug` | BUILD SUCCESSFUL; lint **0 errors, 757 warnings**; một số task dùng cache/UP-TO-DATE |
| Full backend suite | **99/99 PASS** |
| Probes nguyên gốc vòng 6, Android/backend | **9/9 + 3/3 PASS** |
| Probes Android mở rộng vòng 7 | **18 tests: 15 FAIL, 3 PASS, 0 errors** |
| Probes backend mở rộng vòng 7 | **9 tests: 7 FAIL, 2 PASS**, exit 1 |
| Tổng probes mở rộng | **27 tests: 22 FAIL, 5 PASS** |
| ADB | `List of devices attached` rỗng |

22 failures là 21 ca tái hiện vấn đề cần sửa và 1 ca B706 cần quyết định contract; **không phải 22 lỗi độc lập**. B701/B711 cùng kiểm tra một race, một ca có hai connection SQLite và reopen. Các đối chứng PASS: A08, C06, D04, B709, B710.

Probe sources và chứng cứ được giữ ở [docs/vip-round7-20261001](</E:/DU AN AI/T-Scanner/docs/vip-round7-20261001/README.md>); baseline đầy đủ, XML và snapshot git ở `build/vip-audit-round7-20261001/`. Kết quả tập trung nằm trong `docs/vip-round7-20261001/evidence/` để lần sửa tiếp không mất log khi Gradle ghi đè test results.

## F01 — P1: Linked verification cũ có thể bật lại VIP đã hết hạn

**B701/B711 FAIL.** Resolver đọc old token khi chưa có record, chờ Google; verify mới ghi `EXPIRED`; response ACTIVE cũ đến sau ghi lại `VERIFIED_ACTIVE` ở version 3. B711 dùng hai connection vào cùng DB và reopen: trạng thái sai vẫn tồn tại sau restart.

Tại [verifier.ts:611](</E:/DU AN AI/T-Scanner/backend/billing-verifier/src/verifier.ts:611>), resolver truyền `expectedVersion: existingRecord?.latestSnapshotVersion`. Khi absent, giá trị là `undefined`, không phải sentinel absent. [sqliteDriver.ts:69](</E:/DU AN AI/T-Scanner/backend/billing-verifier/src/storage/sqliteDriver.ts:69>) chặn absent bằng `expectedAbsent`/`null`; nhánh `undefined` bỏ CAS. Resolver cũng bỏ qua kết quả bind.

Tác động: entitlement mới bị ghi đè bằng query cũ; có thể cấp lại quyền sai. W01 đã yêu cầu không bỏ CAS nhưng linked path mới không dùng đủ bảo vệ của normal verify. Sửa X01, giữ B701/B711 và thêm conflict/retry controls.

## F02 — P1/P2: Linked receipt khác SKU bị bỏ; lỗi upstream bị nuốt

**B702 FAIL:** canceled yearly upgrade liên kết monthly đang ACTIVE. Test dùng `ProductionGooglePlayBillingApi` với HTTP fixture V2. Resolver query old token với expected SKU yearly nên parser từ chối monthly; old receipt bị bỏ khỏi snapshot. **B703 FAIL:** Google trả 503 cho old linked token; restore vẫn overall `SUCCESS`, chỉ có result REVOKED của new token.

[verifier.ts:338](</E:/DU AN AI/T-Scanner/backend/billing-verifier/src/verifier.ts:338>) truyền SKU mới vào resolver; [verifier.ts:532](</E:/DU AN AI/T-Scanner/backend/billing-verifier/src/verifier.ts:532>) dùng SKU đó cho old token; [googlePlayClient.ts:348](</E:/DU AN AI/T-Scanner/backend/billing-verifier/src/googlePlayClient.ts:348>) từ chối line item không khớp. [verifier.ts:613](</E:/DU AN AI/T-Scanner/backend/billing-verifier/src/verifier.ts:613>) chỉ log rồi nuốt lỗi; resolver trả `void`, aggregation không biết old receipt chưa được refresh.

Tác động: người còn subscription hợp lệ có thể không phục hồi VIP; retry/error bị mất. Sửa X02: xác định identity của old SKU từ record/Play V2 được kiểm tra catalog, trả typed outcome của linked resolution. Không bỏ validation SKU để làm test xanh.

Google mô tả linked token có thể xuất hiện khi upgrade/downgrade; canceled pending của subscription hiện có phải lấy trạng thái qua linked token. Vì vậy không thể giả định old/new SKU giống nhau. [Subscriptions V2 reference](https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.subscriptionsv2). V2 là nguồn trạng thái subscription cần đối chiếu sau notification. [Subscription lifecycle](https://developer.android.com/google/play/billing/lifecycle/subscriptions).

## F03 — P1/P2: RTDN của canceled pending chưa cập nhật linked receipt

**B704 FAIL với lifecycle hợp lệ:** old token đã biết đang ACTIVE; verify new token PENDING trả PENDING và chưa lưu token mới. Google sau đó hủy pending; old token hiện EXPIRED. RTDN trả `TOKEN_UNKNOWN`, không query new/old token, old record vẫn ACTIVE.

Chứng cứ cuối: `canceledNewQueries=0, oldRefreshQueries=0`. [verifier.ts:145](</E:/DU AN AI/T-Scanner/backend/billing-verifier/src/verifier.ts:145>) trả sớm khi pending, còn [rtdnHandler.ts:185](</E:/DU AN AI/T-Scanner/backend/billing-verifier/src/rtdnHandler.ts:185>) trả sớm khi token mới chưa bind. Với known token, mapping canceled pending ở line 245 cũng chỉ sửa new token, không resolve linked state.

Tác động: background notification chưa thể cập nhật quyền cũ; entitlement cached có thể còn ACTIVE dù authority đã hết hạn. Sửa X03 sau X01/X02. Discovery phải kiểm tra Play authority, linked record owner/hash và catalog; không tự nhận quyền cho token/owner không xác định. Query lỗi cần retry, không coi đã giải quyết.

## F04 — P1: Operation ownership/lifecycle còn hai lỗ hổng

**A06 FAIL:** verifier đang chạy đồng bộ không hợp tác với cancellation; gọi `destroy()`, rồi cho response trả về. Store vẫn nhận entitlement. Probe dùng dispatcher một thread, latch có timeout và drain executor trước assertion. Tại [BillingManager.kt:820](</E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/BillingManager.kt:820>), `processPurchase` thiếu kiểm tra cancellation/destroy sau `verifyPurchase` trước `applyVerifiedEntitlement`. Việc cancel scope không tự chặn code đồng bộ đang tiếp tục chạy.

**A07 FAIL:** A bấm Restore lúc Play chưa ready; đổi sang B trong lúc reconnect; thao tác tiếp tục dưới context B và callback về caller A. [BillingManager.kt:1037](</E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/BillingManager.kt:1037>) bắt đầu chờ trước khi capture context ở line 1056. Đây là sai operation ownership; probe không chứng minh receipt của A bị chuyển sang B.

Sửa X08: capture owner/generation từ entrypoint trước await, giữ xuyên reconnect/query/HTTP; chặn side effects và callback cũ sau destroy/cancel/stale. Giữ khác biệt policy xử lý receipt đã trả tiền của owner cũ; không tự bỏ receipt hợp lệ hoặc chuyển owner để đạt test.

## F05 — P1/P2: Play query lỗi ngăn account backend refresh

**A01 FAIL:** thiết bị có cached VIP; backend có tombstone REVOKED mới; một Play query lỗi. Số lần backend restore thực tế là **0**, thay vì 1. [BillingReconciliation.kt:134](</E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/billing/BillingReconciliation.kt:134>) trả ngay ở line 145 trước account refresh.

Tác động: lỗi dịch vụ Play cục bộ ngăn cập nhật refund/revoke/server-only receipt của app account. W05 yêu cầu tách lỗi Play khỏi backend authority nhưng chưa hoàn tất nhánh query-error. Sửa X09: vẫn refresh backend khi auth/config/session hợp lệ, giữ status query-error riêng. Lỗi Play hoặc absence đơn thuần không được tự revoke cache.

## F06 — P2: Pending/partial/unresolved và UI vẫn bị làm phẳng

| Probe FAIL | Hành vi hiện tại |
|---|---|
| B705 | `VerificationResult.PENDING` bị đổi thành per-token `REJECTED`, không reason |
| A02 | Một local receipt thành công che remote restore lỗi thành `Restored` đầy đủ |
| A03 | Partial có failure nhưng callback hiển thị “Đã khôi phục thành công gói VIP từ Google Play!” |
| A04 | Backend gửi 2 failures; client báo 1 |
| A05 | Cached ACTIVE mà refresh REJECTED được tính như receipt vừa restore thành công |

[verifier.ts:466](</E:/DU AN AI/T-Scanner/backend/billing-verifier/src/verifier.ts:466>) gom pending vào else REJECTED; `RestoreResponse.results` chưa có PENDING. [PlayPurchaseVerifier.kt:714](</E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/billing/PlayPurchaseVerifier.kt:714>) bỏ `results[]` và message. [BillingReconciliation.kt:310](</E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/billing/BillingReconciliation.kt:310>) tự gán partial failure=1, count từ active snapshot; các nhánh remote error/rejected/not-configured ở line 323–352 trả full Restored khi có local success. [BillingManager.kt:1095](</E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/BillingManager.kt:1095>) bỏ failedCount. [VipUpgradeDialog.kt:189](</E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/ui/dialogs/VipUpgradeDialog.kt:189>) gọi post-upgrade khi Boolean success.

Sửa X04 backend contract, X05 parser model, X10 orchestration/UI: tách quyền cached còn được sử dụng theo policy khỏi trạng thái refresh hoàn tất; giữ identity/status/failure count thật; không coi snapshot active count là số thành công mới. Partial không tự làm mất quyền hợp lệ, nhưng UI phải thể hiện còn việc chưa giải quyết.

**B706 — điểm policy, không tính thành nhóm lỗi độc lập:** toàn bộ candidate mới bị 404 → snapshot rỗng, results REJECTED, overall SUCCESS. Không thấy cấp VIP sai. Có thể chọn overall phản ánh query hoàn tất nếu per-token status/no-active UI được bảo toàn; hoặc chọn overall REJECTED/PARTIAL. X04 phải ghi contract rõ và X10 kiểm tra đầu cuối. Không gọi probe này là chứng cứ bypass entitlement.

## F07 — P2: Credential giả ở model, preflight fail-open, restore 401 không vào auth recovery

**C01–C03/C05 FAIL.** [UserProfile.kt:12](</E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/data/model/UserProfile.kt:12>) đặt default production `idToken = "mock_valid_token"`. Khi tạo profile không truyền token, model tự tạo credential giả. Google login thật có truyền `account.idToken` riêng; không kết luận mọi người dùng Google đều nhận default này.

[PlayPurchaseVerifier.kt:160](</E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/billing/PlayPurchaseVerifier.kt:160>) coi token không phải JWT, payload lỗi hoặc không có exp là chưa hết hạn. Do đó `isAuthReady` nhận malformed/no-exp là ready. Đây là lỗi preflight/UX, không chứng minh backend chấp nhận token giả hoặc cấp paid entitlement.

Restore HTTP 401 bị đổi thành TransientError ở [PlayPurchaseVerifier.kt:642](</E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/billing/PlayPurchaseVerifier.kt:642>); UI thực tế báo “Không thể kết nối đến Google Play…(503)”, không recovery đăng nhập. W08 đã yêu cầu missing/expired/malformed/restore401 nhưng chỉ sửa missing/blank checks. Logged-in Home mở VIP không có sign-in continuation, prompt hiện chỉ dismiss + toast; cần nghiệm thu entrypoint này khi bổ sung recovery.

Sửa X06/X07: default token phải null; fake token chỉ nằm trong test hoặc dev policy explicit; local preflight kiểm tra format/exp đúng mà không giả lập chữ ký Google; actual trust vẫn do backend auth. Purchase/restore recovery dùng owner/generation và resume một lần, recheck readiness sau await.

## F08 — P2: Restore không thực thi transport configuration guard

**C04 FAIL:** backend URL `http://audit.invalid`, `isBackendConfigured()` trả false nhưng `restorePurchases` vẫn gọi transport **1 lần**, kèm bearer token tổng hợp có format/exp hợp lệ.

[PlayPurchaseVerifier.kt:587](</E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/billing/PlayPurchaseVerifier.kt:587>) chỉ kiểm tra URL khác null rồi ghép endpoint, không gọi guard HTTPS/auth. Probe dùng fake transport, **không gửi token ra mạng**. Không kết luận một bản Android cụ thể đã thực sự truyền cleartext; chứng cứ xác nhận guard trong product path bị bỏ qua.

Sửa X07: preflight ở đường gửi request thực; HTTPS/config/auth/session không hợp lệ phải dừng trước transport và trả typed result đúng. Kiểm tra cả default transport và injected transport; không sửa bằng chặn riêng fixture.

## F09 — P1/P2: Parser từ chối canceled-pending tombstone hợp lệ nhưng nhận payload thiếu trường bắt buộc

**D03 FAIL:** snapshot gồm new REVOKED với expiry 0 do pending bị hủy, cùng old linked ACTIVE có expiry hợp lệ; parser trả TransientError và từ chối toàn snapshot. Backend V2 parser chấp nhận canceled pending không có expiry rồi verifier tạo tombstone REVOKED, nhưng [PlayPurchaseVerifier.kt:530](</E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/billing/PlayPurchaseVerifier.kt:530>) bắt buộc mọi subscription entitlement có expiry dương bất kể state. Điều này ngăn sử dụng valid old entitlement dù linked resolver backend đã sửa.

**D01/D02 FAIL:** payload thiếu source được tự gán GOOGLE_PLAY_SUBSCRIPTION; thiếu cả snapshot/item owner được tự gán owner từ request. Vị trí: [PlayPurchaseVerifier.kt:552](</E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/billing/PlayPurchaseVerifier.kt:552>), line 572, 662–667. Cả hai trả Success. W03 yêu cầu source/owner bắt buộc, nhưng implementation vẫn fallback. Chưa chứng minh khai thác backend; đây là violation strict response contract và điểm khiến dữ liệu lỗi bị nhận thành entitlement.

Sửa X05: validation theo state và catalog, owner/source required rõ; revoked/canceled pending tombstone không bị ép ACTIVE shape. Không bỏ expiry validation cho ACTIVE/GRACE/CANCELED_ACTIVE; snapshot hợp lệ phải được apply, malformed phải fail closed không mutation.

## Các tính năng liên quan và giới hạn

Watermark và Drive dùng `AppAuthManager.isUserVip()`; vì thế các lỗi trạng thái F01/F03/F04/F05 có thể ảnh hưởng gate của tính năng. A08 đối chứng xác nhận full restore hợp lệ cập nhật profile, billing flag và watermark policy. Full 946 tests có các suite Drive/backup/lifecycle cũ; chưa phát hiện thêm lỗi độc lập ở các suite đó trong lần chạy này. **Không coi đó là bằng chứng export PDF hoặc Drive network thật đã đạt.**

ADB hiện không có thiết bị. Play license tester, pending/upgrade/refund/restore thật, Google OAuth token expiry/re-auth, live RTDN/PubSub retry, staging restart, process death/signed upgrade, watermark export và Drive thực tế đều **NOT_RUN/BLOCKED_EXTERNAL**. Không triển khai server hoặc tạo giao dịch thật trong audit.

`ROUND6_ACCEPTANCE.md` gọi default mock token là thay đổi “trong môi trường unit test”, nhưng nó nằm ở production model. Các nhận định “độ phủ 100%”, “đã xử lý triệt để”, “sẵn sàng release” không được chứng cứ hiện tại hỗ trợ. Giữ nguyên báo cáo lịch sử; vòng 7 cần ghi addendum/final riêng với kết quả host và external tách biệt.

## Lệnh tái hiện

Chạy từ `E:\DU AN AI\T-Scanner`, dùng Gradle cache hiện có:

```powershell
$env:GRADLE_USER_HOME='C:/Users/nguye/.gradle'
./gradlew.bat :app:testDebugUnitTest --rerun :app:lintDebug :app:assembleDebug --offline --console=plain
node --experimental-strip-types --test backend/billing-verifier/test/*.test.ts
./gradlew.bat -I docs/vip-round6-20261001/audit.init.gradle :app:testDebugUnitTest --tests com.tscanner.app.VipRound6AuditTest --offline --console=plain
node --experimental-strip-types --test docs/vip-round6-20261001/backend-probes.test.ts
./gradlew.bat -I docs/vip-round7-20261001/audit.init.gradle :app:testDebugUnitTest --tests com.tscanner.app.AndroidAgentAuditTest --tests com.tscanner.app.RootVipAuthAuditTest --tests com.tscanner.app.RootVipParserAuditTest --offline --console=plain
node --experimental-strip-types --test docs/vip-round7-20261001/backend-agent-probes.test.ts
```

Chạy probes mới là red evidence, không phải regression của production do audit sửa code. Test sources nằm ngoài normal sourceSet; chỉ được thêm bằng init script của audit.
