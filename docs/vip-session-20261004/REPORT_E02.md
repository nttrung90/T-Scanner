# Báo cáo Bàn giao Gói E02 — Giữ đúng continuation qua xác thực lại

**Thời điểm:** 04/10/2026  
**Workspace:** `E:\DU AN AI\T-Scanner`  
**Gói phụ trách:** E02 (Giữ đúng continuation qua xác thực lại)  
**Trạng thái:** HOÀN THÀNH (PASS)

---

## 1. Mục tiêu và Phạm vi gói E02

- Sửa lỗi khiếm khuyết F05: nhánh thành công của Credential Manager trong `MoreFragment` (`onSuccess`) trước đây hardcode gọi `vipContinuationHandler.onSignInSuccess { showVipUpgradeDialog() }`, làm mất hành động `RESTORE` và luôn mở hộp thoại mua VIP.
- Đồng bộ cơ chế continuation giữa nhánh Credential Manager hiện đại và nhánh fallback `googleSignInLauncher` (classic Intent).
- Chuẩn hóa contract continuation: hỗ trợ các hành động `UPGRADE`, `RESTORE`, và `NONE`.
- Bảo toàn ngữ cảnh sản phẩm (`targetProductId`) xuyên suốt vòng đời continuation, không tự đổi gói sản phẩm của người dùng.
- Ngăn chặn rò rỉ continuation xuyên session (cross-session leakage): kiểm tra `sessionGeneration` trước khi kích hoạt continuation; nếu phiên thay đổi do đăng xuất/đăng nhập khác thì hủy continuation an toàn.
- Đảm bảo tính toán tử chính xác 1 lần (exactly-once dispatch): trạng thái pending được reset ngay lập tức trước khi gọi callback, không chạy đúp continuation khi fallback Intent hoàn tất.
- Bảo toàn logic của các màn hình Viewer (`PdfViewerActivity`) và ID Card (`IdCardComposeActivity`).

---

## 2. Chi tiết thay đổi code production

### 2.1 `app/src/main/java/com/tscanner/app/utils/VipLoginContinuationHandler.kt`
- Mở rộng enum `VipContinuationAction` thêm giá trị `NONE`.
- Bổ sung trường `targetProductId: String? = null` vào `requestContinuation`, `saveInstanceState`, `restoreInstanceState`, `saveToMap`, `restoreFromMap`.
- Bổ sung kiểm tra `sessionGeneration` trong `onSignInSuccessWithAction`: nếu `pendingSessionGeneration != -1L` và `currentSessionGeneration != -1L` mà khác nhau thì gọi `reset()` và hủy continuation an toàn.
- Bổ sung overload `onSignInSuccessWithAction(currentSessionGeneration: Long, onExecuteAction: (VipContinuationAction) -> Unit)` và `onSignInSuccessWithAction(currentSessionGeneration: Long, onExecuteAction: (VipContinuationAction, String?) -> Unit)`.
- Hàm `reset()` dọn sạch toàn bộ cờ pending, hành động và ngữ cảnh product.

### 2.2 `app/src/main/java/com/tscanner/app/ui/more/MoreFragment.kt`
- Cập nhật nhánh thành công của Credential Manager (`performGoogleSignIn` -> `onSuccess`):
  ```kotlin
  vipContinuationHandler.onSignInSuccessWithAction(startGen) { action ->
      executeVipContinuation(action)
  }
  ```
  thay vì hardcode `showVipUpgradeDialog()`.
- Cập nhật nhánh Intent fallback (`googleSignInLauncher` -> `onSuccess`):
  ```kotlin
  vipContinuationHandler.onSignInSuccessWithAction(startGen) { action ->
      executeVipContinuation(action)
  }
  ```
- Cập nhật `performGoogleSignIn`: truyền `expectedOwnerId = AppAuthManager.getCurrentUser()?.id` xuống `AppAuthManager.signInWithGoogle` để kích hoạt ràng buộc bảo vệ chủ sở hữu đã xây dựng ở gói E01.
- Cập nhật `startSignInForVipContinuation`: nhận thêm tham số tùy chọn `targetProductId: String? = null`.
- Cập nhật `executeVipContinuation`: xử lý đầy đủ các nhánh `UPGRADE`, `RESTORE`, và `NONE`.

---

## 3. Kết quả kiểm thử và nghiệm thu

### 3.1 Test hồi quy F05: `VipSessionExpiryRegressionTest.kt`
- Test case `testF05_credentialManagerSuccess_preservesRestoreContinuation` trước đây ở gói E00 bị đỏ (FAILED vì nhận `UPGRADE` thay vì `RESTORE`).
- Sau khi sửa ở gói E02: **CHUYỂN SANG XANH (PASS)**.
- Toàn bộ 4 test control và test F05 trong `VipSessionExpiryRegressionTest`: **5/5 PASS**.

### 3.2 Bộ kiểm thử State Machine: `VipLoginContinuationTest.kt`
Bổ sung 4 unit test mới xác nhận các ràng buộc của gói E02:
1. `testContinuationHandler_sessionGenerationMismatch_dropsContinuation`: Phiên làm việc bị lệch thế hệ (generation mismatch) hủy continuation an toàn. -> **PASS**
2. `testContinuationHandler_targetProductId_preservedAcrossContinuation`: Giữ nguyên mã sản phẩm được yêu cầu qua continuation. -> **PASS**
3. `testContinuationHandler_actionNone_doesNotExecuteCallback`: Hành động `NONE` không gọi callback. -> **PASS**
4. `testContinuationHandler_exactlyOnceDispatch_noDuplicateOnFallback`: Continuation chỉ chạy duy nhất 1 lần, lần thứ hai không lặp lại. -> **PASS**

Tổng số kiểm thử trong `VipLoginContinuationTest`: **19/19 PASS**.

### 3.3 Kiểm thử không ảnh hưởng các màn hình khác
- `VipViewerLoginContinuationTest`: **PASS**.
- `VipIdCardLoginContinuationTest`: **PASS**.
- Baseline 28 tests (`VipPurchaseActionCoordinatorTest` 11/11, `VipRound8RegressionTest` 17/17): **28/28 PASS**.
- `AppAuthReauthenticationTest` (Gói E01): **11/11 PASS**.

---

## 4. Kết luận và Bàn giao sang Gói E03

- Gói E02 đã hoàn thành 100% mục tiêu, khắc phục triệt để khiếm khuyết F05.
- Sẵn sàng chuyển giao sang **Gói E03** (Nối Chi tiết tài khoản và phân biệt lời nhắc — khắc phục khiếm khuyết F02 và F04).
