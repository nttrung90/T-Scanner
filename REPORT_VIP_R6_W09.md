# Báo cáo VIP Round 6 — W09: Xác minh host độc lập & Ma trận nghiệm thu toàn diện

## 1. Mục tiêu và phạm vi
- Chạy toàn bộ ma trận kiểm tra độc lập trên host machine để xác minh toàn bộ các sửa chữa từ W00 đến W08.
- Đảm bảo 100% các phép thử probe mới (Round 6) và probe gốc (Round 5) đều đạt kết quả PASS mà không có bất kỳ sự suy giảm chất lượng (regression) nào trên toàn bộ codebase.
- Kiểm tra các gate quan trọng: Backend unit suite, Android unit suite với task `--rerun`, `lintDebug` (0 errors), và `assembleDebug`.

## 2. Ma trận kết quả kiểm tra

| Suite / Bài thử | Lệnh thực thi | Kết quả | Chi tiết |
|---|---|---|---|
| **Round 6 Backend Probes** | `node --experimental-strip-types --test docs/vip-round6-20261001/backend-probes.test.ts` | **PASS (3/3)** | B01, B02, B03 đều PASS |
| **Round 5 Backend Probes** | `node --experimental-strip-types --test docs/vip-round5-20260930/backend-probes.test.ts` | **PASS (7/7)** | B01, B02, B03, B04, B05, B06, B07 đều PASS |
| **Toàn bộ Backend Suite** | `node --experimental-strip-types --test backend/billing-verifier/test/*.test.ts` | **PASS (99/99)** | 99 passed, 0 failed, duration ~2s |
| **Round 6 Android Suite** | `./gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipRound6RegressionTest` | **PASS (9/9)** | A01 → A09 đều PASS |
| **Round 6 Android Audit Probe** | `./gradlew.bat -I docs/vip-round6-20261001/audit.init.gradle :app:testDebugUnitTest --tests com.tscanner.app.VipRound6AuditTest` | **PASS (9/9)** | Toàn bộ 9 probe gốc R6 audit PASS |
| **Round 5 Android Audit Probe** | `./gradlew.bat -I docs/vip-round5-20260930/audit.init.gradle :app:testDebugUnitTest --tests com.tscanner.app.VipRound5AuditTest` | **PASS (7/7)** | Toàn bộ 7 probe gốc R5 audit PASS |
| **Toàn bộ Android Unit Suite** | `./gradlew.bat :app:testDebugUnitTest --rerun --offline --console=plain` | **PASS (946/946)** | 946 tests completed, 0 failed, 0 errors |
| **Android Lint Check** | `./gradlew.bat :app:lintDebug --offline --console=plain` | **BUILD SUCCESSFUL** | 0 errors |
| **Android Build Assemble** | `./gradlew.bat :app:assembleDebug --offline --console=plain` | **BUILD SUCCESSFUL** | APK debug tạo thành công |

## 3. Tổng kết khắc phục các nhóm khiếm khuyết R01–R07
- **R01 (Probes A01, A02):** Hợp nhất đầy đủ các biên lai Google Play trên thiết bị và các quyền của tài khoản ứng dụng trên máy chủ authoritative trong `BillingReconciliation`. Không bỏ qua restore khi có pending hoặc nonempty device catalog.
- **R02 Backend (Probes B01, B03):** Khi query 404 cho receipt đã biết, trả về `PARTIAL` thay vì full `SUCCESS` giả với cache cũ.
- **R02 Android (Probe A03):** Bảo toàn metadata lỗi với `failedCount > 0`, không làm biến mất trạng thái phục hồi một phần khi lên UI.
- **R03 (Probe A04):** Toàn bộ operation bất đồng bộ gắn với managed CoroutineScope trong `BillingManager`. Hủy sạch sẽ khi `destroy()`, không có side-effect hay callback muộn.
- **R04 (Probe A05):** Single durable commit vào `BillingEntitlementStore`. Chiếu trực tiếp lên profile bằng `projectSnapshotToProfile` và kiểm tra kết quả lưu trữ, loại bỏ double-commit.
- **R05 (Probe A06):** Missing, blank hoặc expired ID token đều chuyển hướng thống nhất vào chu trình phục hồi đăng nhập (`RequestSignIn` / `ShowSignInRequiredPrompt`).
- **R06 (Probes A07, A08):** Ràng buộc chặt chẽ `productType` và `provider source` theo catalog SKU (`ALL_SUBSCRIPTION_IDS` -> `subs` / `GOOGLE_PLAY_SUBSCRIPTION`; `ALL_INAPP_IDS` -> `inapp` / `GOOGLE_PLAY_INAPP`).
- **R07 (Probe B02):** Hỗ trợ chuẩn xác canonical state `SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED` và giải quyết đệ quy an toàn có giới hạn cho `linkedPurchaseToken`.

## 4. Bước tiếp theo
- Chuyển sang thực hiện **W10** — Gate external và lập các tài liệu nghiệm thu cuối cùng (`REPORT_VIP_R6_FINAL.md` và `docs/billing/ROUND6_ACCEPTANCE.md`).
