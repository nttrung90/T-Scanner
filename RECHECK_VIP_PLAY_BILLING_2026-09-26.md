# Kiểm tra tích hợp VIP / Google Play Billing — 26/09/2026

## Kết luận

Mã nguồn đã có Play Billing, nhưng **chưa đủ điều kiện nghiệm thu thanh toán thật**. Phát hiện 11 nhóm vấn đề; 9 probe gọi logic production tái hiện lỗi, 10 ca đối chứng từ BillingManagerTest vẫn qua. Không sửa mã sản phẩm, dependency hoặc test gốc trong lượt kiểm tra này.

Phạm vi: BillingManager, VipUpgradeDialog, AppAuthManager, MoreFragment, điểm khởi động ứng dụng, dependency/manifest, giá và nhãn mua VIP. Chỉ kiểm tra checkout hiện tại tại `E:\DU AN AI\T-Scanner`; working tree đã có rất nhiều thay đổi staged/unstaged/untracked, được giữ nguyên. Không suy diễn kết quả từ các vòng kiểm tra login/Drive trước.

## Kiểm tra đã chạy

| Kiểm tra | Kết quả và giới hạn |
|---|---|
| `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline` | BUILD SUCCESSFUL; phần lớn task UP-TO-DATE |
| `:app:testDebugUnitTest --rerun --offline` | Chạy lại task test: **739 tests, 0 failures, 0 errors, 0 skipped** |
| BillingManagerTest hiện có | **10/10 PASS**; không bao phủ các lỗi bên dưới |
| Lint debug | **0 errors, 736 warnings**, đọc XML của lượt kiểm tra; không coi tất cả warning đều vô hại |
| Probe độc lập | **19 tests: 10 đối chứng PASS, 9 probe FAIL**, 0 errors, 0 skipped; thất bại là assertion về hành vi mong đợi, không phải lỗi compile |
| Manifest debug đã merge | Có `com.android.vending.BILLING` và metadata phiên bản Billing |
| `adb devices -l` | Không có thiết bị kết nối |
| Play Console, license tester, signed release, giao dịch thật, backend | **NOT RUN / chưa xác minh** |

Lần đầu dùng cache workspace thiếu AGP 9.3.0; lần dùng cache mặc định bị khóa quyền ghi. Sau khi cho phép chạy với cache có sẵn `C:\Users\nguye\.gradle`, kiểm tra hoàn tất. Đây là vấn đề môi trường, không phải lỗi app.

Bằng chứng dưới `build/vip-billing-reaudit/`: `baseline.log`, `baseline-tests.log`, `baseline-counts.json`, `baseline-BillingManagerTest.xml`, `probes.log`, `probe-results.xml`, `BillingReauditProbeTest.kt`, `audit.init.gradle`, `create-probes.ps1`.

Probe sử dụng seam BillingClientWrapper và Android JVM stubs sẵn có, gọi BillingManager/AppAuthManager thật. Fake của bộ test gốc dùng SharedPreferences chung cho các tên và trả cùng danh sách cho SUBS/INAPP; các lỗi idempotence/đổi tài khoản được test trực tiếp độc lập với điểm này. Probe thông báo sync đòi hỏi 0 sự kiện mua mới, nên dù mỗi loại trả danh sách riêng thì chỉ một receipt cũng đủ vi phạm. Không coi các probe này là bằng chứng giao dịch Google Play thật hay khai thác bảo mật trên thiết bị.

## Findings

Đường dẫn Kotlin trong phần này tương đối với `app/src/main/java/com/tscanner/app/`. P1 cần xử lý trước bán VIP; P2 cần hoàn thiện trước nghiệm thu UX/recovery. Số dòng là snapshot lúc kiểm tra, phải định vị lại theo symbol khi sửa.

### F01 — P1 — Lỗi kết nối/thanh toán tự cấp VIP miễn phí; có đường cấp hai lần

- Bằng chứng: `ui/dialogs/VipUpgradeDialog.kt:101-108,160-167`. Khi chưa CONNECTED, thiếu Activity, hoặc launch thất bại, gọi `activateTrialVip()` rồi `setUserVipTier(...365)`; không có kiểm tra build debug/release. Callback `onError` gọi trial, sau đó return false lại gọi trial lần nữa.
- Tái hiện theo mã: tài khoản đã đăng nhập, Play chưa sẵn sàng, bấm nâng cấp => được 365 ngày không thanh toán. Lỗi launch đồng bộ => có thể cộng 730 ngày. Cache sản phẩm trống => manager trả false dù đang re-query và có thể mở thanh toán muộn, trong khi UI đã cấp trial/đóng dialog.
- Trạng thái: **CODE VERIFIED**, chưa chạy UI thiết bị. Regression cần seam hành động purchase UI hoặc instrumentation, không chỉ grep nguồn.
- Nghiệm thu: lỗi/pending/loading/cancel không cấp VIP; một thao tác có một kết quả cuối; trial dev nếu giữ phải không tồn tại trong đường release.

### F02 — P1 — Receipt không idempotent, sync/bind lặp tăng hạn VIP

- `utils/BillingManager.kt:486-502,617-624`; token chỉ được ghi, không dùng chống xử lý lặp. `utils/AppAuthManager.kt:1183-1184` cộng kỳ mới vào `max(now, currentExpiry)`.
- Probe `probeDuplicateTokenMustNotExtendExpiry`: cùng token được gọi hai lần, hạn tăng thêm **31.536.000.000 ms = 365 ngày**. `probeRepeatedBindingMustNotExtendExpiry` cũng thất bại tương tự.
- Tái hiện: mở lại More/restore/reconnect/login-binding nhiều lần với một receipt.
- Nghiệm thu: replay, restart, restore và bind không gia hạn thêm; renewal được xử lý bằng trạng thái mới có thẩm quyền, không chặn mọi renewal chỉ vì token cũ.

### F03 — P1 — Hạn và thu hồi VIP không phản ánh entitlement trên Play

- `BillingManager.kt:509-517` tự quy đổi tháng=30, năm=365, lifetime=3650 ngày; `grantVipForPurchase` không dùng hạn thật. Restore một thuê bao gần hết hạn vẫn cấp nguyên kỳ tính từ hiện tại. Lifetime thực chất hết hạn sau 10 năm.
- `BillingManager.kt:572-575` chỉ hạ boolean billing khi query rỗng; UserProfile vẫn VIP. `UserProfile.kt:23`/`WatermarkHelper.kt:28` dùng quyền từ profile, không dùng boolean billing này.
- Probe `probeEmptyAuthoritativeSyncMustRevokeBillingVip` thất bại: cả query thành công/rỗng, quyền VIP của profile vẫn active.
- Nghiệm thu: hợp nhất mọi entitlement đã xác thực; snapshot có thẩm quyền không còn quyền thì thu hồi đúng nguồn billing; không thu hồi nguồn hợp lệ khác. Hủy tự gia hạn vẫn còn VIP đến hết hạn; refund/revoke/hold/expiry xử lý theo trạng thái thực. Không thay lỗi bằng `purchaseTime + 365 ngày` vì renewal/trial/proration không tương đương.

### F04 — P1 — Không chặn product lạ và chưa có bước xác thực trước cấp quyền

- `BillingManager.kt:449-471,509-517`: kiểm tra PURCHASED/acknowledged, không kiểm tra allowlist trước acknowledge/cấp VIP; product không khớp mặc định 365 ngày. Không có verifier token/signature/backend trong đường này. `acknowledgePurchase` là xác nhận đã xử lý, không thay thế toàn bộ xác thực entitlement.
- Probe `probeUnknownProductMustNotGrantVip` thất bại: `unrelated_product` nhận VIP. Đây là chứng minh thiếu allowlist, không tuyên bố người dùng thường có thể giả receipt của Play trên máy nguyên bản.
- Nghiệm thu: product/package/token/owner/trạng thái được kiểm tra trước cấp quyền; receipt không hợp lệ không được acknowledge như thành công. Có nguồn xác thực server và trạng thái expiry phù hợp trước khi tuyên bố sẵn sàng bán thật.
- Google hướng dẫn xác thực trước cấp quyền, kiểm tra token và ownership tại [Fight fraud and abuse](https://developer.android.com/google/play/billing/security).

### F05 — P1 — Callback muộn cấp quyền cho tài khoản khác

- `BillingManager.kt:455-458,500-502` lấy current user tại lúc ack trả về; không capture user/session ở đầu operation. `launchWithProductDetails:398-400` không gắn obfuscated account ID. Receipt local `:491-496,617-624` là một bản ghi chung, chưa có owner.
- Probe `probeLateAckMustNotGrantToDifferentAccount` thất bại: A bắt đầu xử lý, đổi sang B, ack của A về => B được VIP.
- Nghiệm thu: kết quả gắn owner đã xác thực; mọi mutation/UI callback kiểm tra session generation; đổi/logout tài khoản không chuyển quyền sang người mới. Một receipt không được gắn vô điều kiện cho nhiều tài khoản app.

### F06 — P1 — Restore báo thành công sai; lỗi query bị coi là không có giao dịch

- `BillingManager.kt:585-610` đặt `foundAny=true` trước `processPurchase` và không chờ callback; `checkCompletion:569-577` chỉ đếm query, không đếm processing/ack. Query lỗi vẫn đi vào cùng checkCompletion.
- Probe `probeAckFailureMustNotReportRestoreSuccess`: ack lỗi nhưng restore trả `true`.
- Probe `probeQueryErrorMustPreserveLastKnownBillingState`: SERVICE_UNAVAILABLE của query làm boolean billing active bị xóa.
- Nghiệm thu: kết quả có kiểu phân biệt restored/no purchases/pending/retryable failure; restore chờ xử lý xong, completion đúng một lần; lỗi/partial query không tạo snapshot rỗng có thẩm quyền. Giữ dữ liệu đã xác thực trong giới hạn freshness, không suy ra offline VIP vĩnh viễn.

### F07 — P2 — Restore trong lúc CONNECTING mất callback

- `BillingManager.kt:234-235` return bỏ `onSetupFinished`; `restorePurchases:523-538` phụ thuộc callback này.
- Probe `probeRestoreDuringConnectingMustComplete`: hoàn tất setup thành công vẫn không trả kết quả restore.
- Nghiệm thu: xếp hàng/coalesce waiter, mỗi waiter nhận một terminal result hoặc timeout/cancel xác định; reconnect hữu hạn; stale connection callback không ghi đè trạng thái mới.

### F08 — P2 — Sync nền phát sự kiện mua mới; restore có thể chạy hậu xử lý nhiều lần

- `BillingManager.kt:459,470,590,606` dùng cùng notifyCallbacks cho purchase, restore và silent sync. `VipUpgradeDialog.kt:71-76,129-132` đều gọi `onPostUpgradeFlow` từ callback global và completion restore.
- Probe `probeSilentSyncMustNotEmitPurchaseSuccessEvent` thất bại. Khi dialog đang mở, sync có thể hiện toast mua thành công, xin quyền Drive, dismiss dialog dù người dùng chưa mua mới. Restore có thể kích hoạt flow nhiều lần cho nhiều receipt và callback cuối.
- Nghiệm thu: entitlement state riêng với event theo operation; sync nền không phát purchase UI event; restore chỉ hậu xử lý một lần, đúng host còn sống/session đúng, trên main thread.

### F09 — P1 — Thiếu điểm sync app foreground và gắn quyền sau đăng nhập thật

- Tìm toàn bộ main source: `bindPurchasesToCurrentUser` chỉ có định nghĩa, không có caller production. Test guest tự gọi hàm này nên không chứng minh login thật có tích hợp.
- `MoreFragment.kt:146` gọi sync ở onViewCreated; `:149-157` onResume chỉ add callback/kiểm tra hạn local. `TScannerApplication.kt:15-44` và MainActivity không có billing foreground reconciliation. App bắt đầu ở Home.
- Tái hiện theo mã: payment hoàn tất khi app không chạy, mở app ở Home mà không vào More/dialog => không có query mới. Query trước login khi là guest lưu local; login thành công không tự bind như báo cáo Step A mô tả.
- Trạng thái: **CODE VERIFIED**, pending/restart thật cần thiết bị. Google hướng dẫn query lại khi foreground để bắt giao dịch hoàn tất ngoài luồng tại [Integrate Play Billing](https://developer.android.com/google/play/billing/integrate).
- Nghiệm thu: sync từ lifecycle app và sau login đã xác thực, không phụ thuộc tab; hook dùng cơ chế owner/idempotence đã sửa, không chỉ cắm hàm bind hiện tại vào login.

### F10 — P2 — Giá/nhãn mua chưa theo trạng thái sản phẩm thực

- `VipUpgradeDialog.kt:55-65` vẫn dùng nhãn kích hoạt dùng thử và chỉ đọc giá cache một lần. Không observe `products`; query lần đầu bất đồng bộ có thể về sau dialog.
- `BillingManager.kt:335-340,389` chọn offer/phase đầu tiên; với offer free trial có thể hiển thị 0 mà thiếu giá tái tục. `res/layout/dialog_vip_upgrade.xml:86,96` giữ giá năm và quy đổi tháng cố định.
- Nghiệm thu: loading/unavailable/pending rõ; hiển thị đúng offer, tiền tệ, chu kỳ, trial và giá recurring từ Play; sản phẩm về muộn cập nhật UI; không dùng giá tĩnh như giá giao dịch; 8 locale đang hỗ trợ có chuỗi phù hợp.

### F11 — P1, release gate — Billing 7.1.1 đã qua hạn mặc định cho app/update mới

- `app/build.gradle:106` dùng billing-ktx 7.1.1; báo cáo Step A gọi đây là phiên bản mới nhất không đúng với mốc audit.
- Tại ngày 26/09/2026, [bảng thời hạn chính thức](https://developer.android.com/google/play/billing/deprecation-faq) ghi Billing 7 có hạn mặc định 31/08/2026, có thể được gia hạn tới 01/11/2026. Chưa kiểm tra app có được gia hạn trên Console hay không.
- Đây là rủi ro phát hành, không có nghĩa app đã cài sẽ tự ngừng thanh toán. Nghiệm thu: migrate bản đang được hỗ trợ (ít nhất 8 ở mốc này), compile API thật, kiểm manifest/dependency bản release và trạng thái Console; không chỉ sửa số version.

## Cách chạy lại probe

```powershell
$env:GRADLE_USER_HOME='C:/Users/nguye/.gradle'
./gradlew.bat -I build/vip-billing-reaudit/audit.init.gradle :app:testDebugUnitTest --tests com.tscanner.app.BillingReauditProbeTest --offline --console=plain
```

Không chạy `create-probes.ps1` đè probe đã chỉnh trong lần sửa sau: script chỉ tạo snapshot lần audit từ test gốc. Nên chuyển những probe phù hợp thành regression vĩnh viễn gọi production seam, cải thiện fake phân tách preference namespace/product type và giữ đối chứng lỗi/kết nối bất đồng bộ.

## Bàn giao

Thực hiện theo `PLAN_FIX_VIP_PLAY_BILLING_SMALL_MODEL_2026-09-26.md`. Báo cáo Step A phải được đính chính ở gói cuối: hiện chưa có bằng chứng idempotent, secure verification, login bind tự động, hoặc nghiệm thu Play Console. Không upload/triển khai hoặc sửa mã trong lượt audit này.
