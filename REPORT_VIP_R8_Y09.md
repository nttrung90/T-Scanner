# Báo Cáo Gói Y09 — UI Auth Recovery Thực & Continuation Đúng Action (G09)

## 1. Mục tiêu và Phạm vi
- **Mục tiêu:** Giải quyết khiếm khuyết G09 — Cung cấp đường dẫn phục hồi xác thực thực tế (auth recovery) từ giao diện người dùng và tiếp tục hành động (continuation) phù hợp sau khi đăng nhập thành công:
  - Tiếp tục Upgrade (mở lại dialog nâng cấp hoặc tiếp tục thanh toán) nếu hành động ban đầu là Mua VIP.
  - Tiếp tục Restore (tự động kích hoạt khôi phục giao dịch với session mới) nếu hành động ban đầu là Khôi phục VIP.
  - Không bỏ qua luồng đăng nhập đối với tài khoản đã đăng nhập nhưng token hết hạn (`HomeFragment`).
  - Ghi nhận trạng thái kiểm chứng thiết bị thực tế là `NOT_RUN/BLOCKED_EXTERNAL`.
- **Files thay đổi:**
  - `app/src/main/java/com/tscanner/app/utils/VipLoginContinuationHandler.kt`
  - `app/src/main/java/com/tscanner/app/utils/BillingManager.kt`
  - `app/src/main/java/com/tscanner/app/ui/dialogs/VipUpgradeDialog.kt`
  - `app/src/main/java/com/tscanner/app/ui/home/HomeFragment.kt`
  - `app/src/main/java/com/tscanner/app/ui/more/MoreFragment.kt`
  - `app/src/main/java/com/tscanner/app/MainActivity.kt`
  - `app/src/test/java/com/tscanner/app/VipLoginContinuationTest.kt`

## 2. Chi tiết Triển khai
- **Action-Aware Continuation State Machine (`VipLoginContinuationHandler`):**
  - Thêm enum `VipContinuationAction { UPGRADE, RESTORE }`.
  - Hỗ trợ lưu trữ và khôi phục `pendingAction` cùng `pendingSessionGeneration` qua Bundle và Map (hỗ trợ xoay màn hình và lifecycle recreation).
  - Phương thức `onSignInSuccessWithAction(onExecuteAction: (VipContinuationAction) -> Unit)` thực thi chính xác 1 lần (`single-resume`), reset trạng thái pending và terminal state rõ ràng khi hủy (`onSignInCancelled`) hoặc lỗi (`onSignInError`).
- **Typed AuthRequired trong `BillingManager`:**
  - Bổ sung tham số `onAuthRequired: ((message: String) -> Unit)? = null` vào `restorePurchases`.
  - Khi reconciler trả về `ReconciliationResult.AuthRequired`, nếu có `onAuthRequired` thì kích hoạt callback để UI biết cần chuyển sang luồng đăng nhập, ngược lại fallback về `onComplete(false, result.message)` để đảm bảo tương thích ngược.
- **Xử lý phục hồi trong `VipUpgradeDialog`:**
  - Bổ sung `onRequestSignInForAction: ((VipContinuationAction) -> Unit)? = null`.
  - Khi bấm Restore và nhận `onAuthRequired`: hiển thị thông báo, đóng dialog và kích hoạt `onRequestSignInForAction(VipContinuationAction.RESTORE)` (hoặc fallback `onRequestSignIn`).
  - Khi Coordinator yêu cầu đăng nhập trong flow Upgrade: kích hoạt `onRequestSignInForAction(VipContinuationAction.UPGRADE)`.
- **Sửa đường gọi tại `HomeFragment`:**
  - Luôn truyền `onRequestSignIn` và `onRequestSignInForAction` đến `VipUpgradeDialog` ngay cả khi người dùng đã đăng nhập (khắc phục lỗi người dùng có session nhưng token hết hạn bị kẹt ở toast mà không được điều hướng đăng nhập lại).
- **Điều hướng và thực thi Continuation tại `MainActivity` & `MoreFragment`:**
  - `MainActivity.navigateToMoreForVipSignIn(action: String?)` truyền `EXTRA_VIP_ACTION` sang `MoreFragment`.
  - `MoreFragment` nhận fragment result: nếu chưa đăng nhập hoặc token đã hết hạn (`isTokenExpired`), bắt đầu luồng Google Sign-In với `targetAction`. Nếu token còn hạn, thực thi trực tiếp hành động tương ứng.
  - Khi đăng nhập thành công: `onSignInSuccessWithAction` kích hoạt `executeVipContinuation(action)`: mở lại `VipUpgradeDialog` nếu là UPGRADE, hoặc tự động gọi `billingManager.restorePurchases` nếu là RESTORE.

## 3. Kết quả Kiểm thử
- **Unit Suite:**
  - `com.tscanner.app.VipLoginContinuationTest`: **15/15 tests PASSED** (0 failures, 0 skipped).
  - `com.tscanner.app.Vip*Login*`: **ALL PASSED** (20s).
  - `com.tscanner.app.RootRound8AuthAuditTest`: **8/8 PASSED** (C801–C808).
- **Device Validation:**
  - `NOT_RUN/BLOCKED_EXTERNAL`: Không có thiết bị Android/máy ảo kết nối ADB tại host kiểm thử.

## 4. Trạng thái & Chuyển giao
- Gói Y09: **DONE**
- Tự động chuyển tiếp: **Y10 (Chặn SDK/listener callbacks của manager đã dispose — G06)**
