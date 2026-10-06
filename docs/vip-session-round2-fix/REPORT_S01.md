# Báo Cáo Gói S01 — Continuation Nhận Đúng Chuyển Phiên Của Attempt (G01)

**Thời gian:** 04/10/2026  
**Workspace:** `E:\DU AN AI\T-Scanner`  
**Trạng thái gói:** **PASS** (P01 & P02 chuyển ĐỎ $\to$ XANH; C01 & C02 giữ XANH; full suites PASS)

---

## 1. Mục tiêu và phạm vi gói S01

- Khắc phục triệt để lỗi G01: Khách (guest) bấm Mua hoặc Khôi phục VIP, sau đó đăng nhập Google thành công thì bị mất continuation (`UPGRADE`/`RESTORE`), do `AppAuthManager` tăng `sessionGeneration` hợp lệ khi commit user mới từ guest ($G \to G+1$), nhưng `VipLoginContinuationHandler` so sánh chặt chẽ và hủy action.
- Ràng buộc continuation với:
  - `initialOwnerId`: `null` đối với khách, hoặc `userId` đối với tài khoản đang đăng nhập.
  - `originatingRequestId`: request ID của attempt khởi tạo.
  - `processEpoch`: UUID của tiến trình ứng dụng (chống replay sau process death).
  - `pendingSessionGeneration`: generation tại thời điểm bắt đầu flow.
- Cho phép chuyển phiên hợp lệ: Khách (`initialOwner == null`) commit thành công tài khoản đầu tiên (`currentOwner != null`), generation tăng từ $G \to G+1$.
- Bắt buộc same-owner reauth: Người đã đăng nhập (`initialOwner == A`) bắt buộc commit đúng `A` và giữ nguyên generation.
- Đồng nhất cách tiêu thụ continuation giữa Credential Manager và Intent fallback trong `MoreFragment`.
- Không bỏ kiểm tra generation; không truyền `-1L` trên production.

---

## 2. Bằng chứng kiểm thử trước và sau sửa đổi (Red $\to$ Green)

### 2.1 Bộ Probes độc lập (`VipSessionReauditProbeTest`)

Lệnh chạy:
```powershell
$env:JAVA_HOME='C:\Users\nguye\.jdks\openjdk-21.0.1'
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
.\gradlew.bat :app:testDebugUnitTest --offline -I docs/vip-session-reaudit-20261004/audit.init.gradle --tests 'com.tscanner.app.VipSessionReauditProbeTest' --console=plain
```

| Probe ID | Test Name | Trước S01 (Baseline) | Sau S01 | Ý nghĩa |
|---|---|---|---|---|
| **P01** | `P01_guestUpgradeSurvivesSuccessfulLoginGenerationChange` | **FAIL** | **PASS** | Guest `UPGRADE` tiếp tục mở dialog nâng cấp sau khi đăng nhập thành công |
| **P02** | `P02_guestRestoreSurvivesSuccessfulLoginGenerationChange` | **FAIL** | **PASS** | Guest `RESTORE` tiếp tục thực hiện khôi phục sau khi đăng nhập thành công |
| **C01** | `C01_sameOwnerReauthContinuesOnce` | **PASS** | **PASS** | Reauth cùng tài khoản dispatch đúng 1 lần (idempotent) |
| **C02** | `C02_boundReauthRejectsOtherOwner` | **PASS** | **PASS** | Reauth khác tài khoản bị chặn trước commit |

*(4 probes còn lại: P03 thuộc S02; P04/P05 thuộc S05; P06 thuộc S04).*

### 2.2 Suite kiểm thử trạng thái Continuation (`VipLoginContinuationTest`)

Chạy 25 tests bao gồm các kịch bản hồi quy mới:
- `testContinuationHandler_duplicateSuccess_executesOnlyOnce`: **PASS** (idempotency, callback trùng không kích hoạt lần 2).
- `testContinuationHandler_staleAttempt_mismatchedRequestId_dropsContinuation`: **PASS** (attempt cũ / sai ID bị từ chối).
- `testContinuationHandler_accountSwitch_A_to_B_dropsContinuation`: **PASS** (đổi account A sang B bị loại bỏ).
- `testContinuationHandler_logoutBeforeSuccess_dropsContinuation`: **PASS** (đăng xuất trước callback bị hủy).
- `testContinuationHandler_processDeath_restoringObsoleteEpoch_resetsPending`: **PASS** (process restart không replay continuation cũ).
- `testContinuationHandler_rotation_sameProcessEpoch_preservesAndExecutesContinuation`: **PASS** (xoay màn hình trong cùng tiến trình vẫn giữ pending).
- `testContinuationHandler_cancelledWithMatchingAttemptId_resetsPending`: **PASS** (hủy đúng attempt ID).
- Tổng: **25/25 PASS**.

---

## 3. Chi tiết các file đã sửa

1. **`app/src/main/java/com/tscanner/app/utils/VipLoginContinuationHandler.kt`**:
   - Thêm các trường: `initialOwnerId`, `originatingRequestId`, `processEpoch`.
   - `requestContinuation`: nhận diện và lưu trữ `initialOwnerId` và `processEpoch`.
   - `bindAttempt(attempt: GoogleLoginAttempt)`: đồng bộ ID và generation của attempt đang chạy.
   - `onSignInSuccessWithAction`:
     - Kiểm tra `processEpoch`: từ chối nếu khác UUID tiến trình hiện tại.
     - Kiểm tra `originatingRequestId`: từ chối nếu lệch request ID của attempt.
     - Kiểm tra `initialOwnerId`: nếu đã đăng nhập thì `currentOwnerId == initialOwnerId` (không cho phép đổi tài khoản hoặc đăng xuất).
     - Kiểm tra `sessionGeneration`: cho phép bước nhảy $G \to G+1$ khi và chỉ khi `initialOwnerId == null && currentOwnerId != null` (khách đăng nhập lần đầu); các trường hợp còn lại yêu cầu thế hệ phiên trùng khớp.
   - `saveInstanceState` / `restoreInstanceState`: lưu và khôi phục đầy đủ ngữ cảnh; tự động reset nếu `processEpoch` từ instance trước khác tiến trình hiện tại (chống process death replay).
2. **`app/src/main/java/com/tscanner/app/ui/more/MoreFragment.kt`**:
   - `performGoogleSignIn`: trả về `Boolean` biểu thị việc attempt có được khởi chạy hay bị từ chối do busy. Binds `pendingSignInAttempt` vào `vipContinuationHandler`.
   - `startSignInForVipContinuation`: kiểm tra `vipContinuationHandler.isPending`, nếu chưa thì khởi tạo với `initialOwnerId` và `processEpoch`; nếu `performGoogleSignIn()` trả về `false` (bận), tự động `reset()` tránh treo pending.
   - Đồng nhất nhánh Credential Manager và nhánh fallback Intent: đều gọi `vipContinuationHandler.onSignInSuccessWithAction(startGen, startUser, attemptId) { action, product -> executeVipContinuation(action, product) }`.
   - Giữ và chuyển tiếp `targetProductId` qua `executeVipContinuation`.

---

## 4. Hợp đồng bàn giao cho Gói S02 (Contract Handover)

1. **Vấn đề cần giải quyết ở S02:** G02 — Trong `PdfViewerActivity` và `IdCardComposeActivity`:
   - `signInWithGoogle` đang được gọi mà KHÔNG truyền `expectedOwnerId = currentUser?.id`. Khi user A có tài khoản (dù token hết hạn), việc chọn account B sẽ đè A giữa lúc xuất/xem tài liệu.
   - Cả hai host khởi tạo `VipUpgradeDialog` chỉ truyền `onRequestSignIn`, thiếu `onRequestSignInForAction`. Khi người dùng bấm `RESTORE` trong dialog, do thiếu action callback nên dialog rơi về `onRequestSignIn`, dẫn tới flow `UPGRADE` thay vì `RESTORE`.
   - `vipContinuationHandler.requestContinuation()` đang được gọi với tham số mặc định (không session generation, không owner).
2. **Quy tắc áp dụng cho S02:**
   - Cả 2 host phải truyền `expectedOwnerId = AppAuthManager.getCurrentUser()?.id` vào `signInWithGoogle`.
   - Cả 2 host phải truyền `onRequestSignInForAction` vào `VipUpgradeDialog`.
   - Trong `handleSignInSuccess`, sử dụng `vipContinuationHandler.onSignInSuccessWithAction` để route đúng: `UPGRADE` $\to$ hiển thị dialog xác nhận mua; `RESTORE` $\to$ thực hiện restore purchases.
