# Báo cáo kiểm thử & Bàn giao Gói T03 (H01: Auth Recovery Cho Giao Dịch Đã Có Receipt & Credential Expiry)

Ngày thực hiện: 05/10/2026.
Workspace: `E:\DU AN AI/T-Scanner`

## 1. Mục tiêu và Phạm vi
- Khắc phục khiếm khuyết H01:
  1. Token null, blank hoặc hết hạn (`isTokenExpired(token)`) phải trả `VerificationResult.AuthRequired` ngay trước khi gọi transport mạng (không gọi HTTP transport và không trả `Rejected(INVALID_SIGNATURE_OR_TOKEN)` nhầm lẫn giữa credential người dùng và tính hợp lệ của receipt).
  2. Giao dịch đã có receipt gặp `AuthRequired` từ verifier phải phát sự kiện định kiểu `PurchaseAuthRequiredEvent` tới host (`VipUpgradeDialog`), yêu cầu người dùng xác thực lại chính chủ (same-owner reauth) với action `RESTORE`.
  3. Sau khi xác thực lại thành công, hệ thống khôi phục (verify & acknowledge) lại receipt hiện có qua luồng restore, không gọi thêm bất kỳ lệnh `launchBillingFlow` nào và không mở lại hộp thoại mua hàng.
  4. Tránh lặp vô hạn: nếu giao dịch đã thử recovery mà vẫn tiếp tục gặp `AuthRequired` (lần thứ 2), hệ thống ngắt chu kỳ reauth tự động và dừng lại bằng thông báo lỗi/hướng dẫn retry chủ động.
  5. Không phát sinh 2 terminal events (không hạ `AuthRequired` thành generic failure callback khi listener định kiểu đang lắng nghe).

## 2. Bằng chứng kiểm thử trước và sau sửa đổi
- **Trước sửa đổi (Baseline T00):**
  - Probe P03 (`P03_missingCredentialMustBeAuthRequiredNotReceiptRejection`): **FAIL** (trả về `Rejected` thay vì `AuthRequired`).
  - Probe P04 (`P04_expiredCredentialMustStopBeforeTransport`): **FAIL** (gọi 1 cuộc gọi mạng transport dù token đã hết hạn).
  - Đứt gãy ở UI: `BillingManager` hạ `AuthRequired` thành chuỗi failure thông thường, `VipUpgradeDialog` chỉ hiện Toast lỗi mà không dẫn tới xác thực lại.
- **Sau sửa đổi (T03):**
  - Probe P03: **PASS** (trả về `AuthRequired` trước transport, 0 calls).
  - Probe P04: **PASS** (kiểm tra token hết hạn trước transport, 0 calls).
  - Probe C03 (`C03_backend401WithFreshTokenIsTyped`): **PASS** (fresh token gặp HTTP 401 trả `AuthRequired`).
  - Toàn bộ 7/7 Round 3 probes (`VipSessionRound3ProbeTest`): **PASS** (7 tests completed, 0 failed).
  - Kiểm thử tích hợp `VipSessionRound3IntegrationTest.receiptAuthRequiredRequestsSameOwnerRecoveryWithoutNewPurchase_contractSpecification`: **PASS** (kiểm tra toàn diện: nhận typed event, 0 duplicate generic failure, 0 extra `launchBillingFlow`, không cấp VIP sớm, chặn vòng lặp reauth lần 2 với `isRetry=true`, và recovery thành công sau reauth).

## 3. Danh sách file thay đổi
- `app/src/main/java/com/tscanner/app/utils/billing/PlayPurchaseVerifier.kt`:
  - Trong `verifyViaRemoteBackend`: kiểm tra `tokenProvider != null && (token.isNullOrBlank() || isTokenExpired(token))` và trả `VerificationResult.AuthRequired` ngay trước khi cấu hình header hoặc gọi HTTP transport.
- `app/src/main/java/com/tscanner/app/utils/BillingManager.kt`:
  - Định nghĩa data class `PurchaseAuthRequiredEvent` và mở rộng `PurchaseCallback.onAuthRequired(event)`.
  - Bổ sung `addAuthRequiredListener` và `removeAuthRequiredListener`.
  - Trong `processPurchase`: khi kết quả là `VerificationResult.AuthRequired`, tạo `PurchaseAuthRequiredEvent` với thông tin receipt, owner, epoch, và trạng thái retry (`authRecoveryAttemptedTokens`). Nếu ở foreground purchase, phát sự kiện định kiểu qua `notifyPurchaseAuthRequired`.
  - Triển khai `notifyPurchaseAuthRequired` ưu tiên dispatch tới các listener định kiểu và triệt tiêu generic failure duplicate.
  - Xóa token retry và listener khi `destroy()`.
- `app/src/main/java/com/tscanner/app/ui/dialogs/VipUpgradeDialog.kt`:
  - Lắng nghe `PurchaseAuthRequiredEvent` qua `addAuthRequiredListener`.
  - Khi nhận `PurchaseAuthRequiredEvent`: đóng dialog, hiện thông báo xác thực lại, và gọi `onRequestSignInForAction(VipContinuationAction.RESTORE)`. Nếu là lần retry thứ hai (`event.isRetry == true`), chỉ thông báo lỗi mà không lặp lại reauth.
  - Bổ sung test seam `@VisibleForTesting handlePurchaseAuthRequiredForTesting`.
- `app/src/test/java/com/tscanner/app/VipSessionRound3IntegrationTest.kt`:
  - Hoàn thiện test case `receiptAuthRequiredRequestsSameOwnerRecoveryWithoutNewPurchase_contractSpecification`.

## 4. Nghiệm thu & Chuyển giao
- Tiêu chí nghiệm thu của T03: **ĐẠT (PASS)**.
- Chuyển sang thực hiện T04.
