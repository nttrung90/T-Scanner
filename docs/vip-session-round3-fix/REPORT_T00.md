# Báo Cáo Kiểm Tra & Khóa Hợp Đồng Gói T00

**Ngày thực hiện:** 05/10/2026  
**Workspace:** `E:\DU AN AI\T-Scanner`  
**Gói công việc:** T00 (Theo `PLAN_FIX_VIP_SESSION_ROUND3_GEMINI_2026-10-04.md`)

---

## 1. Mục Tiêu & Phạm Vi (Scope)

- **Mục tiêu:**
  - Khóa bằng chứng baseline trước khi sửa Round 3 (H01–H04).
  - Ghi nhận trạng thái git status và mã hash SHA256 của các file nguồn thuộc diện sửa đổi.
  - Chạy 7 probes mới nguyên bản (`VipSessionRound3ProbeTest.kt`) để xác nhận chính xác 4 FAIL và 3 PASS (controls).
  - Chạy 7 regressions phù hợp của Vòng 2 để đảm bảo các sửa đổi trước không bị hồi quy.
  - Thiết lập đặc tả hợp đồng cho 3 integration tests còn thiếu:
    1. `receiptAuthRequiredRequestsSameOwnerRecoveryWithoutNewPurchase` (H01 - T03)
    2. `fallbackLaunchFailureAllowsRetryOnSameHost` (H03 - T02)
    3. `navigationFromOwnerARejectedAfterLogoutAndOnReplay` (H04 - T04)

---

## 2. Bằng Chứng Thực Chạy (Evidence)

### 2.1. Mã băm SHA256 các file sản xuất trước khi sửa
Đã lưu trữ tại `docs/vip-session-round3-fix/baseline_hashes.txt`:
- `VipLoginContinuationHandler.kt`: `9067C0930EF417A2582002FBE66C8CCDC86162DB8B39D6291082631E0D28688F`
- `MoreFragment.kt`: `7F7D0B12BD5A04DE321745C2441D71DD284EACD90B2475759D2AB124A1EC2DF7`
- `PdfViewerActivity.kt`: `7045B108B9767540D673CCB028E28B237790CB3E41E8A9F5057E9CF40F3C334F`
- `IdCardComposeActivity.kt`: `CD2A7F31A21FC51DF32774EBDD954BD5DD27272A1FC86D08BA03920F283B011D`
- `PurchaseVerifier.kt`: `198DF57ECCCD00140BF9B9C95BB3E8B403CA14E88624DFBA303CF8D464187D7A`
- `PlayPurchaseVerifier.kt`: `4BAB72D816A31CAFE881EB62173435C3F8D0AD7D50DC14FE4D031090632B9221`
- `BillingManager.kt`: `89874AD9993C7E602676D9FAFEFF2674080605784862604B83339568F02C2E21`
- `VipUpgradeDialog.kt`: `8CEDD0B5766EE75694C4139211D60691AB4C46F896FAAED196440649E6D6FB3C`
- `MainActivity.kt`: `3104FA4FB404E39C83E91352FBF7D4EBB9099EDF1C6C5497369FB338281980FA`
- `HomeFragment.kt`: `27CC4EBF6F44CAFD736A03D9F4496F5437C17713AB9ADC2A61298528B134C43E`

### 2.2. Kết quả chạy 7 probes mới (`VipSessionRound3ProbeTest`)
- Command:
  ```powershell
  .\gradlew.bat :app:testDebugUnitTest --offline -I docs/vip-session-round3-reaudit-20261004/audit.init.gradle -PsessionRound3Probes --tests 'com.tscanner.app.VipSessionRound3ProbeTest' --console=plain
  ```
- Kết quả: **4 FAILED / 3 PASSED**
  - `P01_oldSuccessMustNotEraseNewPendingRequest`: **FAILED** (Unrelated success resets active continuation).
  - `P02_boundContinuationMustRequireOriginatingAttemptAtConsumption`: **FAILED** (Bound continuation consumed without originating request ID).
  - `P03_missingCredentialMustBeAuthRequiredNotReceiptRejection`: **FAILED** (Missing token rejected valid receipt as INVALID_SIGNATURE_OR_TOKEN).
  - `P04_expiredCredentialMustStopBeforeTransport`: **FAILED** (Expired token sent to network transport instead of stopping at auth recovery).
  - `C01_matchingAttemptDispatchesOnce`: **PASSED** (Control).
  - `C02_wrongCancellationPreservesPending`: **PASSED** (Control).
  - `C03_backend401WithFreshTokenIsTyped`: **PASSED** (Control).

### 2.3. Kết quả chạy các regressions phù hợp Vòng 2
- Command:
  ```powershell
  .\gradlew.bat :app:testDebugUnitTest --offline -I docs/vip-session-reaudit-20261004/audit.init.gradle --tests 'com.tscanner.app.VipSessionReauditProbeTest' --console=plain
  ```
- Kết quả: **7/7 PASSED** (P01, P02, P04, P05, P06, C01, C02). Probe P03 cũ là synthetic unbound API audit probe đã được giải thích ở S02.

---

## 3. Ba Hợp Đồng Kiểm Thử Tích Hợp (T00 Integration Test Contracts)

File: `app/src/test/java/com/tscanner/app/VipSessionRound3IntegrationTest.kt`

1. **`receiptAuthRequiredRequestsSameOwnerRecoveryWithoutNewPurchase` (H01 -> T03):**
   - Fake verifier trả `AuthRequired` sau khi mua thành công receipt `PURCHASED`.
   - Production Billing event phải chuyển giao typed auth event tới host.
   - Host yêu cầu reauth đúng chủ sở hữu A.
   - Sau reauth thành công, kích hoạt khôi phục/xác minh lại chính receipt đó mà **không** gọi lại `launchBillingFlow`.
   - Quyền VIP không được cấp trước khi xác minh thành công.
2. **`fallbackLaunchFailureAllowsRetryOnSameHost` (H03 -> T02):**
   - Khi modern Google sign-in gặp lỗi và chuyển sang fallback intent launcher, nếu launcher ném exception:
   - Khối catch của host (`MoreFragment`, `PdfViewerActivity`, `IdCardComposeActivity`) phải hủy auth attempt và giải phóng `vipContinuationHandler.isPending`.
   - Lần bấm tiếp theo của người dùng trên cùng host phải được tiếp nhận, không bị chặn bởi cờ bận cũ.
3. **`navigationFromOwnerARejectedAfterLogoutAndOnReplay` (H04 -> T04):**
   - Producer (`HomeFragment` / `MainActivity`) tạo yêu cầu điều hướng cho chủ sở hữu A.
   - Nếu trước khi `MoreFragment` xử lý mà người dùng đăng xuất (`currentUser == null`), request phải bị hủy an toàn, không được tự động chuyển thành đăng nhập guest.
   - Yêu cầu điều hướng đã tiêu thụ hoặc lệch epoch/generation phải bị loại bỏ hoàn toàn.

---

## 4. Trạng Thái & Bàn Giao

- Gói **T00 Hoàn thành đạt chuẩn**.
- Bàn giao hợp đồng sang **T01**: Xử lý H02 — Bắt buộc identity của attempt khi consume continuation trong `VipLoginContinuationHandler.kt` và các host liên quan.
