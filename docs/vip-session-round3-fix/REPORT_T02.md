# Báo cáo kiểm thử & Bàn giao Gói T02 (H03: Fallback Launch Failure & Retry Recovery)

Ngày thực hiện: 05/10/2026.
Workspace: `E:\DU AN AI/T-Scanner`

## 1. Mục tiêu và Phạm vi
- Khắc phục khiếm khuyết H03: Khi mở Google Sign-In qua fallback (Intent/launcher) thất bại (throw exception hoặc host finishing/detached), trạng thái continuation và auth attempt phải được giải phóng hoàn toàn để người dùng có thể bấm thử lại (retry).
- Đảm bảo `AppAuthManager.isSignInInProgress` được giải phóng về `false` và `vipContinuationHandler.isPending` được giải phóng về `false`.
- Các click thử lại tiếp theo trên cùng host được chấp nhận và tạo attempt mới bình thường.

## 2. Bằng chứng kiểm thử trước và sau sửa đổi
- **Trước sửa đổi:**
  - Khi launch fallback quăng ngoại lệ, host chỉ log/báo lỗi và gọi `cancelSignInProgress(attemptToCancel)`, nhưng không gọi `vipContinuationHandler.onSignInError(attemptToCancel?.requestId ?: -1L)`.
  - Kết quả: `vipContinuationHandler.isPending` vẫn giữ `true`, khiến các lần bấm tiếp theo trên UI bị chặn lại bởi guard `isPending`.
- **Sau sửa đổi:**
  - Đã thêm lệnh gọi giải phóng continuation tại catch block và tại các kiểm tra lifecycle (`isFinishing`, `isDestroyed`, `!isAdded`) trên cả 3 host: `MoreFragment`, `PdfViewerActivity`, `IdCardComposeActivity`.
  - Kiểm thử tích hợp `VipSessionRound3IntegrationTest.fallbackLaunchFailureAllowsRetryOnSameHost_contractSpecification`: **PASS** (BUILD SUCCESSFUL).
  - Thử lại tạo attempt mới thành công; error cũ không làm ảnh hưởng attempt mới.

## 3. Danh sách file thay đổi
- `app/src/main/java/com/tscanner/app/ui/more/MoreFragment.kt`:
  - Thêm giải phóng continuation trong `onFallbackToIntent` catch block và khi `!isAdded`.
- `app/src/main/java/com/tscanner/app/ui/viewer/PdfViewerActivity.kt`:
  - Thêm giải phóng continuation trong `onFallbackToIntent` catch block và khi `isFinishing || isDestroyed`.
- `app/src/main/java/com/tscanner/app/ui/idcard/IdCardComposeActivity.kt`:
  - Thêm giải phóng continuation trong `onFallbackToIntent` catch block và khi `isFinishing || isDestroyed`.
- `app/src/main/java/com/tscanner/app/utils/AppAuthManager.kt`:
  - Bổ sung `@VisibleForTesting fun isSignInInProgressForTesting(): Boolean` phục vụ kiểm tra trạng thái tiến trình auth.
- `app/src/test/java/com/tscanner/app/VipSessionRound3IntegrationTest.kt`:
  - Triển khai test case kiểm tra hợp đồng retry sau khi launch failure.

## 4. Nghiệm thu & Chuyển giao
- Tiêu chí nghiệm thu của T02: **ĐẠT (PASS)**.
- Chuyển sang thực hiện T03.
