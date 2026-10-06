# Báo cáo Tổng kết VIP Round 7 — Tự động chạy hoàn tất X00 → X12

**Dự án:** T-Scanner (Android Client & Node.js Billing Verifier)  
**Thời gian hoàn thành:** 01/10/2026  
**Chế độ thực thi:** Autorun tuần tự độc lập (X00 → X12), bảo toàn nguyên vẹn mọi uncommitted/untracked changes, không commit/push/deploy, không dùng tiền thật hay mock token trong production.

---

## 1. Tóm tắt kết quả kiểm chứng thực tế (Actual Numbers)

| Thành phần / Suite | Số lượng kiểm thử | Kết quả thực tế | Trạng thái |
|---|---|---|---|
| **Round 7 Probes Mới** | **27 probes** (18 Android + 9 Backend) | **27 / 27 PASS** (100%) | **ĐẠT TUYỆT ĐỐI** |
| **Round 6 Probes Gốc** | **15 probes** (12 Android + 3 Backend) | **15 / 15 PASS** (100%) | **BẢO TOÀN** |
| **Round 5 Probes Gốc** | **16 probes** (9 Android + 7 Backend) | **16 / 16 PASS** (100%) | **BẢO TOÀN** |
| **Toàn bộ Backend Suite** | `backend/billing-verifier/test/*.test.ts` | **108 / 108 PASS** (0 fail, 0 skip) | **ĐẠT** |
| **Toàn bộ Android Suite** | `:app:testDebugUnitTest` (115 files) | **964 / 964 PASS** (0 fail, 0 skip) | **ĐẠT** |
| **Android Lint Gate** | `:app:lintDebug` | **0 errors, 757 warnings** | **ĐẠT** (giữ baseline) |
| **Android Build Gate** | `:app:assembleDebug` | **BUILD SUCCESSFUL** (APK debug) | **ĐẠT** |
| **Workspace Integrity** | `git status` | Không reset, stash, clean, drop | **100% BẢO TOÀN** |

---

## 2. Nhật ký tiến độ từng gói (X00 → X12)

- **X00 (Baseline & Setup):**
  - Đọc `RECHECK_VIP_FULL_ROUND7_2026-10-01.md`, port 18 Android probes vào `VipRound7RegressionTest.kt` (15 FAIL / 3 PASS) và 9 backend probes vào `round7-regression.test.ts` (7 FAIL / 2 PASS).
  - Khởi tạo `PROGRESS_VIP_R7_AUTORUN.md` và `REPORT_VIP_R7_X00.md`.
- **X01 (F01 - CAS cho linked bind/update):**
  - Sửa `verifier.ts`: Thêm CAS retry loop và explicit absent sentinel (`expectedVersion: null, expectedAbsent: true`) trong `resolveLinkedSubscriptionToken`. Ngăn chặn race condition giữa 2 kết nối SQLite.
  - Probes **B701, B711 PASS**. Xuất `REPORT_VIP_R7_X01.md`.
- **X02 (F02 - Linked identity & typed resolution outcome):**
  - Sửa `types.ts`, `googlePlayClient.ts`, `verifier.ts`: Phân tích đúng SKU khác nhau (Yearly <-> Monthly) từ danh mục Subscriptions V2 `lineItems`. Trả về `LinkedResolutionOutcome`, không nuốt lỗi upstream.
  - Probes **B702, B703, B709, B710 PASS**. Xuất `REPORT_VIP_R7_X02.md`.
- **X03 (F03 - RTDN canceled pending và linked authority):**
  - Sửa `rtdnHandler.ts`: Khi nhận RTDN cho token chưa bind, truy vấn Google Play authority qua API `getSubscriptionV2` để tìm linked token và cập nhật quyền hạn.
  - Probe **B704 PASS**. Xuất `REPORT_VIP_R7_X03.md`.
- **X04 (F06 Backend - Itemized results & aggregate status):**
  - Sửa `types.ts`, `verifier.ts`: Giữ nguyên per-token `PENDING` trong `results[]`. Khi tất cả candidates bị 404/unknown, trả về aggregate status `REJECTED` (không giả lập `SUCCESS`).
  - Probes **B705, B706 PASS** (Toàn bộ 9 probe backend R7 PASS, full suite 108/108 PASS). Xuất `REPORT_VIP_R7_X04.md`.
- **X05 (F09 & F06 Parser Android):**
  - Sửa `PurchaseVerifier.kt`, `PlayPurchaseVerifier.kt`: Thêm model `RestoreItemResult`, parse mảng `results[]`. Bắt buộc trường `"source"` và `"ownerAppUserId"`, fail-closed khi thiếu. Hỗ trợ state-based expiry (chấp nhận tombstone của canceled pending subscription có expiry = 0).
  - Probes **D01, D02, D03, D04, A04, A05, A08 PASS**. Xuất `REPORT_VIP_R7_X05.md`.
- **X06 (F07 Preflight - Credential thật & readiness):**
  - Sửa `UserProfile.kt`, `PlayPurchaseVerifier.kt`, fixtures: Đưa `UserProfile.idToken` mặc định về `null` (loại bỏ mock token khỏi production). Bổ sung `isTokenExpired` fail-closed (yêu cầu đúng cấu trúc JWT 3 phần và claim `exp` dương). Yêu cầu `tokenProvider != null` cho `isAuthReady`.
  - Probes **C01, C02, C03, C06 PASS**. Xuất `REPORT_VIP_R7_X06.md`.
- **X07 (F08 & F07 Restore - Transport guard & auth recovery):**
  - Sửa `PlayPurchaseVerifier.kt`, `BillingManager.kt`: Kiểm tra `isBackendConfigured()` trước khi gọi HTTP transport trong `restorePurchases`. Map mã HTTP 401 trực tiếp sang `RestoreResult.AuthRequired` / `ReconciliationResult.AuthRequired`, không nuốt lỗi.
  - Probes **C04, C05 PASS**. Xuất `REPORT_VIP_R7_X07.md`.
- **X08 (F04 - Owner/generation capture & cancellation guard):**
  - Sửa `BillingManager.kt`: Chụp `initialContext` (`ownerAppUserId`, `sessionGeneration`) trước khi bắt đầu `startConnection` trong `restorePurchases` và `syncPurchases`. Kiểm tra `isStale` sau reconnect. Bảo vệ `processPurchase` sau `verifier.verifyPurchase` chống commit khi manager đã bị `isDestroyed`.
  - Probes **A06, A07 PASS**. Xuất `REPORT_VIP_R7_X08.md`.
- **X09 (F05 - Account backend refresh khi Play query lỗi):**
  - Sửa `BillingReconciliation.kt`: Khi Play queries trả mã lỗi khác `OK`, không ngắt ngay với `NetworkError` mà kích hoạt `executeRemoteRestore` độc lập cho tài khoản người dùng đang đăng nhập, áp dụng snapshot authoritative từ server.
  - Probe **A01 PASS**. Xuất `REPORT_VIP_R7_X09.md`.
- **X10 (F06 Android - Partial message & feature gates):**
  - Sửa `BillingReconciliation.kt`, `BillingManager.kt`: Tính toán `failedCount` chính xác từ `results[]` và các item local/remote lỗi. Khi `failedCount > 0`, hiển thị thông điệp khôi phục một phần (`"Khôi phục một phần: Một số giao dịch chưa thể hoàn tất."`).
  - Probes **A02, A03 PASS** (Toàn bộ 18 probe Android R7 PASS). Xuất `REPORT_VIP_R7_X10.md`.
- **X11 (Kiểm chứng host độc lập toàn diện):**
  - Chạy đầy đủ: 27 R7 probes, 15 R6 probes, 16 R5 probes, 108 backend unit tests, 964 Android unit tests, lintDebug (0 errors, 757 warnings), assembleDebug (BUILD SUCCESSFUL).
  - Xuất `REPORT_VIP_R7_X11.md`.
- **X12 (External gates & bàn giao cuối cùng):**
  - Tổng hợp danh mục external gates, xuất `ROUND7_ACCEPTANCE.md` và `REPORT_VIP_R7_FINAL.md`.

---

## 3. Bản đồ nghiệm thu 9 nhóm lỗi F01–F09

| Mã lỗi | Mô tả khiếm khuyết | File sửa chính | Probes kiểm chứng | Trạng thái |
|---|---|---|---|---|
| **F01** | CAS loop & absent sentinel cho linked token | `verifier.ts` | B701, B711 | **FIXED_VERIFIED_HOST** |
| **F02** | Nâng cấp khác SKU (Yearly/Monthly) & typed outcome | `types.ts`, `googlePlayClient.ts`, `verifier.ts` | B702, B703, B709, B710 | **FIXED_VERIFIED_HOST** |
| **F03** | RTDN canceled pending thiếu Play authority query | `rtdnHandler.ts` | B704 | **FIXED_VERIFIED_HOST** |
| **F04** | Owner/generation capture trước await & hủy sau destroy | `BillingManager.kt` | A06, A07 | **FIXED_VERIFIED_HOST** |
| **F05** | Play query lỗi chặn refresh backend độc lập | `BillingReconciliation.kt` | A01 | **FIXED_VERIFIED_HOST** |
| **F06** | Itemized results, failedCount & thông báo Partial | `types.ts`, `verifier.ts`, `BillingReconciliation.kt`, `BillingManager.kt` | B705, B706, A02, A03, A04, A05 | **FIXED_VERIFIED_HOST** |
| **F07** | Credential thật, default null, preflight JWT expiry | `UserProfile.kt`, `PlayPurchaseVerifier.kt`, `VipUpgradeDialog.kt` | C01, C02, C03, C06 | **FIXED_VERIFIED_HOST** |
| **F08** | HTTPS transport gate & auth recovery 401 | `PlayPurchaseVerifier.kt`, `BillingManager.kt` | C04, C05 | **FIXED_VERIFIED_HOST** |
| **F09** | Strict source/owner parser & state-based expiry | `PurchaseVerifier.kt`, `PlayPurchaseVerifier.kt` | D01, D02, D03, D04 | **FIXED_VERIFIED_HOST** |

---

## 4. Ghi chú đặc biệt về Probe B706 (Policy Check)
Probe B706 kiểm tra trường hợp: khi toàn bộ candidate tokens gửi lên `/api/v1/billing/restore` đều bị Google Play từ chối (404 / unknown token):
- Hệ thống backend trả về aggregate status `REJECTED` kèm mảng `results[]` ghi nhận trạng thái từ chối cho từng token.
- Đây là **policy check**: Đảm bảo hệ thống không giả lập trạng thái `SUCCESS` rỗng khi người dùng gửi danh sách token hoàn toàn không hợp lệ.

---

## 5. Trạng thái các cổng ngoại vi (External Gates Checklist)

Vì quá trình tự động thực thi diễn ra trên môi trường máy chủ phát triển (host development machine) không kết nối Internet mở với Google Play Console và không có thiết bị thật:

| Hạng mục kiểm thử ngoại vi | Trạng thái | Ghi chú & Điều kiện tiếp tục |
|---|---|---|
| **Google Play License Tester** | `NOT_RUN / BLOCKED_EXTERNAL` | Cần thiết bị Android có đăng nhập Google Account thuộc danh sách License Testers trên Play Console. |
| **Real Google Sign-In OAuth** | `NOT_RUN / BLOCKED_EXTERNAL` | Cần cấu hình OAuth 2.0 Web Client ID trong `strings.xml` và Google Play Services hoạt động. |
| **Live Cloud Pub/Sub RTDN** | `NOT_RUN / BLOCKED_EXTERNAL` | Cần Google Cloud Service Account key và topic Pub/Sub liên kết với Google Play Developer Console. |
| **Physical Device ADB & Process Death** | `NOT_RUN / BLOCKED_EXTERNAL` | Cần cắm thiết bị qua ADB để test kill process trong khi thanh toán đang diễn ra. |
| **Live Google Drive Backup & Sync** | `NOT_RUN / BLOCKED_EXTERNAL` | Cần người dùng thật đồng ý cấp quyền truy cập Drive Scope trên thiết bị. |

---

## 6. Việc cần người dùng thực hiện (Action Items for User)

1. **Kiểm tra thiết bị thực tế:** Cài đặt file APK tạo từ `app/build/outputs/apk/debug/app-debug.apk` lên thiết bị thử nghiệm có Google Play Services.
2. **Thử nghiệm License Tester:** Dùng tài khoản License Tester mua gói `tscanner_vip_yearly`, kiểm tra xuất PDF không có watermark và sao lưu Google Drive.
3. **Commit / Release:** Người dùng có thể chủ động review `git diff` và thực hiện commit/push lên git repository khi thấy hài lòng với toàn bộ chứng cứ kiểm thử.
