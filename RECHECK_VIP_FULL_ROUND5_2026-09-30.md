# Kiểm tra độc lập VIP — vòng 5, 30/09/2026

## Kết luận

Còn **8 nhóm lỗi**, tái hiện qua **14 phép thử thất bại / 16 phép thử bổ sung** (2 đối chứng đạt). Chưa đủ cơ sở kết luận luồng VIP hoàn tất. Không sửa mã sản phẩm trong lượt kiểm tra này; chỉ tạo hồ sơ kiểm tra, probe và kế hoạch. Giữ nguyên thay đổi chưa commit của người dùng.

Phạm vi: mua/xác thực/khôi phục/sync VIP, phiên Google, Android entitlement store/profile, backend Google Play API + SQLite + RTDN, ảnh hưởng tới watermark và Drive. Dùng mã production qua các seam HTTP/Play/context; không gọi thanh toán, Google API hoặc Drive thật.

Kế hoạch tiếp theo: [Gemini Antigravity autorun](PLAN_VIP_GEMINI_ANTIGRAVITY_ROUND5_AUTORUN_2026-09-30.md).

## Kết quả kiểm tra

| Kiểm tra | Kết quả |
|---|---|
| Toàn bộ Android unit tests | 928 tests, 0 failures/errors/skipped; đã buộc chạy lại task bằng `--rerun` |
| Backend hiện có | 89/89 PASS |
| `lintDebug` | PASS, 0 errors, 757 warnings |
| `assembleDebug` | PASS; phần lớn build tasks UP-TO-DATE |
| Probe vòng 5 Android | 9 tests: 8 FAIL, 1 PASS |
| Probe vòng 5 backend | 7 tests: 6 FAIL, 1 PASS |
| Thiết bị / Play Console / backend triển khai / Drive thật | NOT RUN |

Lần baseline đầu unit task UP-TO-DATE; sau đó đã chạy mới toàn bộ unit tests, không dùng kết quả cache làm chứng cứ cuối. Lint/build thành công trong 4m49s. Probe Android lần đầu có fixture chưa bật BillingClient ready; đã sửa fixture và chạy lại. **Chỉ dùng kết quả cuối** trong `docs/vip-round5-20260930/android-probes.xml` (9 tests/8 failures), không dùng lần thử fixture ban đầu.

Log baseline: `build/vip-audit-20260930/android-baseline.log`, `android-baseline-fresh.log`, `backend-baseline.log`; XML baseline được lưu riêng. Probe, XML và log backend được giữ trong `docs/vip-round5-20260930/` để Gemini đưa vào regression suite, không phụ thuộc thư mục build có thể bị clean.

## F01 — P1: Chống ghi đè đồng thời chưa bao phủ lần bind đầu và RTDN/verify

- Mã: `backend/billing-verifier/src/verifier.ts:105-106,178-190,394-435`; `src/storage/sqliteDriver.ts:65-80`; `src/rtdnHandler.ts:278-284`.
- **B01 FAIL**: DB chưa có token; query ACTIVE cũ bị giữ; query EXPIRED mới commit trước; thả ACTIVE cũ. Kết quả DB quay về VERIFIED_ACTIVE. `expectedVersion=undefined` vừa mang nghĩa chưa tồn tại vừa có nghĩa bỏ qua CAS.
- **B02 FAIL**: hai request cùng đọc version cũ; ACTIVE commit trước; phản hồi EXPIRED còn lại bị bỏ vì conflict. Không refetch Play, trả cache ACTIVE như đã giải quyết dù upstream trong fixture hiện EXPIRED. Không thể suy ra response nào mới chỉ từ thứ tự commit; cần retry có giới hạn hoặc trả conflict có thể thử lại.
- **B07 FAIL**: RTDN query ACTIVE đang chờ; verify ghi EXPIRED; RTDN hoàn tất và ghi ACTIVE vì chỉ có eventTime guard, thiếu expectedVersion xuyên các loại tác vụ.
- Tác động: giữ/bật lại VIP đã hết hạn, hoặc bỏ lỡ renewal theo thứ tự tương tự; ảnh hưởng cả restore và đồng bộ.
- Acceptance: expected-absent riêng; CAS chung verify/restore/RTDN; conflict không ngụy trang thành xác thực mới thành công. Phải kiểm tra cả hai hướng trạng thái, nhiều connection SQLite và restart. Không dùng quy tắc “revoked luôn thắng” làm hỏng renewal hợp lệ.

## F02 — P1: ON_HOLD không cập nhật quyền; V2 thiếu state vẫn cấp VIP

- Mã: `src/googlePlayClient.ts:318-386`; `src/verifier.ts:138-144,205-244`.
- **B03 FAIL**: seed ACTIVE, Play V2 trả ON_HOLD với expiry đã qua. Adapter đặt paymentState=0; verifier trả PENDING sớm trước khi lưu ON_HOLD. DB còn VERIFIED_ACTIVE với hạn tương lai của snapshot cũ.
- **B04 FAIL**: V2 có lineItems/expiry hợp lệ nhưng thiếu subscriptionState vẫn cho SUCCESS. Parser trả state undefined và verifier dùng đường fallback autoRenewing.
- Acceptance: mapping lifecycle dùng chung verify và RTDN, state/shape thiếu phải không cấp quyền; ON_HOLD/PAUSED lưu trạng thái ngưng quyền, PENDING không trở thành paid. Giữ đúng GRACE và CANCELED còn hạn.
- Đối chiếu [Google SubscriptionState](https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.subscriptionsv2#SubscriptionState): ON_HOLD là suspended; khác pending thanh toán lúc đăng ký. Đây là đối chiếu contract, không phải chứng cứ giao dịch thật đã chạy.

## F03 — P1: Metadata từ client ghi đè receipt đã biết khi restore

- Mã: `src/verifier.ts:473-491,496-538`.
- **B05 FAIL**: seed lifetime ACTIVE; Play đổi sang refunded; gửi restore với cùng token nhưng SKU không hợp lệ. Map ghi metadata client đè metadata server; verification từ chối SKU, không query receipt đúng, response vẫn SUCCESS với ACTIVE cũ.
- **B06 PASS đối chứng**: cùng refund đó, restore không có candidate client thì cập nhật REVOKED đúng.
- Acceptance: metadata đã bind là nguồn tin cậy cho known token; candidate xung đột được báo lỗi riêng, không ngăn refresh record đã biết. SUCCESS chỉ khi các receipt cần refresh được giải quyết theo freshness policy; partial/error không giả thành kết quả đầy đủ.

## F04 — P1: Parser restore Android không kiểm tra chặt phản hồi

- Mã: `app/src/main/java/com/tscanner/app/utils/billing/PlayPurchaseVerifier.kt:590-638`.
- **A01 FAIL**: request owner A nhưng snapshot owner B vẫn thành RestoreResult.Success. `dummyReq` được dựng từ chính dữ liệu response, nên không ràng buộc với owner của thao tác.
- **A02 FAIL**: SKU ngoài catalog vẫn thành Success.
- **A03 FAIL**: entitlement state UNKNOWN bị bỏ qua, toàn response được đổi thành Success với snapshot rỗng.
- **A04 FAIL**: HTTP 400 nhưng body SUCCESS vẫn được nhận.
- **A07 PASS đối chứng**: snapshot hợp lệ được parse thành Success.
- Tác động đã chứng minh là parser chấp nhận dữ liệu sai. Không khẳng định đã khai thác được quyền giữa hai tài khoản trên server thật; projection profile có thêm guard riêng. Tuy nhiên cache/UI và callback có thể không nhất quán.
- Acceptance: kiểm tra HTTP/status, owner của request/session, catalog + productType/source, token/version/expiry và schema; thiếu/hỏng item không âm thầm thành full success. Restore có thể trả token chưa có trên thiết bị, nên không bắt token phải nằm trong candidate local.

## F05 — P1: Restore trả muộn vẫn ghi sau đổi phiên

- Mã: `BillingReconciliation.kt:120-130,164-201`; `BillingManager.kt:1070-1079`.
- **A05 FAIL**: bắt đầu restore owner A, suspend remote; đổi sang B; thả success A. Store A vẫn bị ghi. Coroutine restore không kiểm tra lại operation owner/generation sau await trước side effect.
- Guard tại BillingManager chỉ loại UI callback sau khi reconciler đã ghi store và billing flag. Probe xác nhận store mutation; không suy diễn thành B được cấp VIP trực tiếp.
- Acceptance: stale response không ghi cache/prefs/profile/callback. Kiểm tra A→B, logout, A→B→A đổi generation; SUCCESS/PARTIAL/error đều có guard. Coroutine gắn lifecycle/cancellation phù hợp, không để job riêng chạy quá session.

## F06 — P2: Báo khôi phục dựa vào response cũ thay vì snapshot đã commit

- Mã: `BillingReconciliation.kt:167-193`.
- **A06 FAIL**: seed REVOKED version10, restore trả ACTIVE version9. Store merge giữ REVOKED đúng nhưng reconciler dùng snapshot incoming để set `billing_vip_active=true` và phát Restored.
- Nhánh này bỏ qua kết quả persist; lỗi commit/conflict cũng chưa được đưa vào quyết định thành công.
- Acceptance: dùng typed apply result và snapshot đã merge/commit làm nguồn duy nhất cho profile, flag và kết quả restore. Persistence failure/conflict phải trả failure/retry thích hợp, không “khôi phục thành công”.

## F07 — P1: PARTIAL lưu VIP nhưng không cập nhật hồ sơ dùng bởi các tính năng

- Mã: `BillingReconciliation.kt:184-194`; `AppAuthManager.kt:219,1228-1236`; `WatermarkHelper.kt:19,28`; `GoogleDriveBackupWorker.kt:65,167,193`.
- **A08 FAIL**: PARTIAL chứa một entitlement ACTIVE hợp lệ. Store có VIP, nhưng `AppAuthManager.isUserVip()` vẫn false do nhánh Partial không project vào profile.
- Tác động theo đường gọi hiện tại: watermark/Drive vẫn xem tài khoản là Free dù store đã có quyền. Hướng ngược (partial có revoke) cần regression bổ sung để tránh giữ VIP cũ.
- Partial với 0 entitlement active vẫn phát Restored ở mã hiện tại; cần phân biệt restore được một phần với không khôi phục quyền nào.
- Acceptance: áp dụng các receipt đã xác thực thành công, giữ receipt lỗi tạm thời theo version/freshness, project snapshot hợp nhất; UI nói rõ một phần, không báo full success. Probe tích hợp watermark và Drive bằng production helper/worker.

## F08 — P2: Token phiên hết hạn bị biến thành lỗi dịch vụ không có lối đăng nhập lại

- Mã: `AppAuthManager.kt:159,1385,1422`; `PlayPurchaseVerifier.kt:158-200`; `VipPurchaseActionCoordinator.kt:117-124`.
- **A09 FAIL**: tài khoản đã đăng nhập với ID token hết hạn, backend URL đúng. isConfigured trả false; coordinator chỉ báo “dịch vụ ... chưa sẵn sàng”, canRetry=false, không yêu cầu đăng nhập lại.
- Đây là phần còn thiếu của T09 vòng trước: phát hiện expiry đã có, nhưng refresh/re-auth chưa được nối. Đăng xuất/đăng nhập thủ công có thể là workaround; không được mô tả lỗi thành dịch vụ đang hỏng.
- Acceptance: trạng thái cấu hình backend tách khỏi trạng thái xác thực; thiếu/hết hạn token đi qua refresh được hỗ trợ hoặc re-auth rõ ràng. Resume đúng một lần, có kiểm tra owner/generation; không launch Play trước readiness và không retry vô hạn.

## Những phần đã tiến bộ và giới hạn

- Endpoint production đã dùng subscriptionsv2; có tìm đúng lineItem theo SKU. Backend auth audience/issuer và các regression vòng trước đạt trong suite hiện tại.
- Store có version guard; grant + ack outbox trong transaction; Drive worker đã có ensureActive sau chờ OAuth và lookup folder. Các thay đổi này không được hoàn tác để chữa lỗi khác.
- Ma trận vòng 4 đánh dấu “100%” chủ yếu theo từng probe cụ thể. T02/T04/T05/T09/T10 chưa bao phủ đầy đủ acceptance ban đầu, thể hiện ở F01–F08; không phải toàn bộ công việc vòng trước vô hiệu.
- Chưa kiểm thử Play license tester, OAuth token acquisition thật, restart deployment với volume, real RTDN delivery/retry, thanh toán pending/renew/refund, hai thiết bị, restore sau reinstall, UI main-thread/lifecycle, Drive thật, PDF export trên máy. Lệnh ADB trên host không khởi động được daemon ở lượt này; không có chứng cứ device acceptance.
- Không đọc/in secrets, không deploy/publish, không thay cấu hình release.

## Tái hiện

Tại repository root, PowerShell:

```powershell
$env:GRADLE_USER_HOME='C:/Users/nguye/.gradle'
./gradlew.bat -I docs/vip-round5-20260930/audit.init.gradle :app:testDebugUnitTest --tests com.tscanner.app.VipRound5AuditTest --offline --console=plain
node --experimental-strip-types --test docs/vip-round5-20260930/backend-probes.test.ts
./gradlew.bat :app:testDebugUnitTest --rerun --offline --console=plain
node --experimental-strip-types --test backend/billing-verifier/test/*.test.ts
./gradlew.bat :app:lintDebug :app:assembleDebug --offline --console=plain
```

Hai lệnh probe hiện phải thất bại theo số liệu ở trên. Chuyển probe thành regression bền vững trước khi sửa; không đưa build folder vào source set mặc định. Khi chuyển backend test vào thư mục test, sửa import path tương ứng. Không nới assertion để làm báo cáo xanh.
