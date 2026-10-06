# Báo cáo kiểm thử & Bàn giao Gói T01 (H02: Originating Request ID Binding & Consumption)

Ngày thực hiện: 05/10/2026.
Workspace: `E:\DU AN AI\T-Scanner`

## 1. Mục tiêu và Phạm vi
- Khắc phục khiếm khuyết H02: Ràng buộc continuation request ID tại điểm tiêu thụ.
- Đảm bảo kết quả xác thực cũ/không liên quan không xóa hoặc tiêu thụ continuation pending của request mới.
- Các host (`MoreFragment`, `PdfViewerActivity`, `IdCardComposeActivity`) phải truyền đúng `attempt.requestId` tại điểm callback.

## 2. Bằng chứng kiểm thử trước và sau sửa đổi
- **Trước sửa đổi (Baseline T00):**
  - Probe P01 (`P01_oldSuccessMustNotEraseNewPendingRequest`): **FAIL** (kết quả cũ xóa mất pending continuation).
  - Probe P02 (`P02_boundContinuationMustRequireOriginatingAttemptAtConsumption`): **FAIL** (overload thiếu request ID vẫn tiêu thụ continuation đã bound).
- **Sau sửa đổi (T01):**
  - Probe P01: **PASS** (kết quả khác `originatingRequestId` bị bỏ qua an toàn, giữ nguyên active continuation).
  - Probe P02: **PASS** (tiêu thụ bound continuation bắt buộc phải có `attemptRequestId == originatingRequestId`).
  - Probes C01, C02, C03: **PASS** (3/3).
  - Suite hồi quy Round 2 (`VipSessionReauditProbeTest`): **PASS** (7/7 tests).

## 3. Danh sách file thay đổi
- `app/src/main/java/com/tscanner/app/utils/VipLoginContinuationHandler.kt`:
  - Trong `onSignInSuccessWithAction`: nếu `originatingRequestId != -1L`, yêu cầu `attemptRequestId != -1L && attemptRequestId == originatingRequestId`; nếu không khớp thì `return` (ignore) mà không `reset()`.
  - Trong `onSignInCancelled` và `onSignInError`: nếu `originatingRequestId != -1L` và ID không khớp thì bỏ qua mà không `reset()`.
- `app/src/main/java/com/tscanner/app/ui/more/MoreFragment.kt`:
  - Bắt giữ `invocationAttempt` và truyền `attempt.requestId` vào các callback success/cancelled/error.
- `app/src/main/java/com/tscanner/app/ui/viewer/PdfViewerActivity.kt`:
  - Bắt giữ `attemptId` trước khi clear `pendingSignInAttempt`, truyền vào `handleSignInSuccess(profile, attemptId)` và cancel/error handlers.
- `app/src/main/java/com/tscanner/app/ui/idcard/IdCardComposeActivity.kt`:
  - Bắt giữ `invocationAttempt` và truyền vào `handleSignInSuccess(profile, attemptId)` và cancel/error handlers.

## 4. Nghiệm thu & Chuyển giao
- Tiêu chí nghiệm thu của T01: **ĐẠT (PASS)**.
- Chuyển sang thực hiện T02.
