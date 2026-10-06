# Báo Cáo Gói Y08 — Guard HTTPS/Auth/Session Ở Actual Verify & Restore Transport (G08)

## 1. Mục tiêu và Phạm vi
- **Mục tiêu:** Giải quyết khiếm khuyết G08 (C801, C802, C803, C806, C808) — Chặn transport thực tế (fail-closed) ở cả `verifyPurchase` và `restorePurchases` khi endpoint không phải HTTPS bảo mật, token rỗng/hết hạn/malformed, hoặc session generation/owner không hợp lệ.
- **Files thay đổi:**
  - `app/src/main/java/com/tscanner/app/utils/billing/PlayPurchaseVerifier.kt`

## 2. Chi tiết Triển khai
- **HTTPS Enforcement trong `verifyPurchase`:**
  - Trước khi gọi `verifyViaRemoteBackend`, kiểm tra `isBackendConfigured()` và kiểm tra tiền tố `https://`.
  - Nếu URL cấu hình là plain `http://` hoặc không hợp lệ, lập tức trả về `VerificationResult.MissingBackendGate` mà không thực hiện bất kỳ network request nào (`httpTransport` không được kích hoạt).
- **Session & Auth Readiness Guards trong `restorePurchases`:**
  - Kiểm tra `isBackendConfigured()` và tiền tố `https://` của `backendUrl`.
  - Kiểm tra `ownerAppUserId` không bị lệch so với `ownerProvider` đang hoạt động: nếu mismatch, trả về `RestoreResult.Rejected(RejectionReason.OWNERSHIP_CONFLICT)`.
  - Kiểm tra `isAuthReady()` khi có `tokenProvider`: nếu token rỗng, hết hạn hoặc không phải định dạng hợp lệ (malformed), trả về ngay `RestoreResult.AuthRequired` và dừng lại trước khi gọi `httpTransport`.
  - Kiểm tra `sessionGenerationProvider` trước và sau khi lấy token để phòng ngừa race condition khi chuyển đổi tài khoản giữa chừng.

## 3. Kết quả Kiểm thử
- **Trước khi sửa (Regression RED):**
  - C801: FAILED (expired token sent over transport, calls = 1 thay vì 0)
  - C802: FAILED (malformed token sent over transport, calls = 1 thay vì 0)
  - C803: FAILED (http:// url bypassed HTTPS guard, calls = 1 thay vì 0)
  - 3/8 tests FAILED, 5/8 PASSED.
- **Sau khi sửa (Focused tests GREEN):**
  - `RootRound8AuthAuditTest`: **8/8 PASSED** (C801, C802, C803, C804, C805, C806, C807, C808).
  - `VipRound8RegressionTest (C80*)`: **5/5 PASSED** (C801, C802, C803, C806, C808).
  - `PlayPurchaseVerifierHttpTest`: **11/11 PASSED** (0 failures, 0 skipped).

## 4. Trạng thái & Chuyển giao
- Gói Y08: **DONE**
- Tự động chuyển tiếp: **Y09 (UI auth recovery thực & continuation đúng action — G09)**
