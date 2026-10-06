# Báo Cáo Gói S03 — Giữ Lý Do Auth-Required Qua Điều Hướng/Restore (G03, Nhánh Restore)

**Thời gian:** 04/10/2026  
**Workspace:** `E:\DU AN AI\T-Scanner`  
**Trạng thái gói:** **PASS** (Hoàn thành bảo toàn lý do auth-required/force-reauth qua navigation, xử lý 401 khi token local chưa hết hạn, chặn đệ quy vô hạn ở restore)

---

## 1. Mục tiêu và phạm vi gói S03

- Khắc phục lỗi G03 (nhánh Restore qua Navigation):
  1. Khi người dùng bấm Mua/Khôi phục VIP trong dialog tại `HomeFragment` và backend trả về HTTP 401 (`AuthRequired`), flow điều hướng sang `MoreFragment` qua `(activity as? MainActivity)?.navigateToMoreForVipSignIn()`.
  2. Tại `MoreFragment`, listener trước đây chỉ kiểm tra:
     `if (user == null || PlayPurchaseVerifier.isTokenExpired(user.idToken))`
     Nếu idToken của user trên client vẫn còn hạn (trường `exp` trong tương lai) nhưng backend đã từ chối (401), điều kiện trên trả về `false`, dẫn tới `MoreFragment` không kích hoạt đăng nhập lại mà lại gọi thẳng `executeVipContinuation()`. Tại đây, backend tiếp tục trả về 401, tạo thành vòng lặp hoặc lỗi không giải quyết được.
  3. Cần phân biệt điều hướng fresh "Mở VIP" thông thường với điều hướng do "Backend/Dialog đã yêu cầu xác thực lại (`forceReauth = true`)".
  4. Ràng buộc `expectedOwnerId` và `operationId` qua navigation Bundle để ngăn chặn tài khoản khác nhận nhầm yêu cầu reauth cũ.
  5. Khi restore continuation chạy sau reauth, restore thực thi đúng 1 lần; nếu tiếp tục nhận `onAuthRequired` (lần 401 thứ 2), dừng lại và hiển thị thông báo lỗi, không redirect lặp vô hạn.

---

## 2. Các thay đổi Production

1. **`app/src/main/java/com/tscanner/app/MainActivity.kt`**:
   - Mở rộng hàm điều hướng `navigateToMoreForVipSignIn`:
     ```kotlin
     fun navigateToMoreForVipSignIn(
         action: String? = null,
         forceReauth: Boolean = false,
         authReason: String? = null,
         expectedOwnerId: String? = null,
         operationId: String? = null
     )
     ```
   - Đóng gói đầy đủ các trường `EXTRA_FORCE_REAUTH`, `EXTRA_AUTH_REQUIRED_REASON`, `EXTRA_EXPECTED_OWNER_ID`, `EXTRA_OPERATION_ID` vào Fragment Result bundle gửi sang `MoreFragment`.

2. **`app/src/main/java/com/tscanner/app/ui/home/HomeFragment.kt`**:
   - Trong `btnVipHome.setOnClickListener`:
     - `onRequestSignIn`: gọi `navigateToMoreForVipSignIn(action = UPGRADE.name, forceReauth = false)` (dành cho guest).
     - `onRequestSignInForAction`: gọi `navigateToMoreForVipSignIn(action = action.name, forceReauth = true, authReason = "AUTH_REQUIRED", expectedOwnerId = currentUser?.id, operationId = UUID.randomUUID().toString())`.

3. **`app/src/main/java/com/tscanner/app/ui/more/MoreFragment.kt`**:
   - Thêm các constants vào `companion object`:
     `EXTRA_FORCE_REAUTH`, `EXTRA_AUTH_REQUIRED_REASON`, `EXTRA_EXPECTED_OWNER_ID`, `EXTRA_OPERATION_ID`.
   - Trong `parentFragmentManager.setFragmentResultListener(REQUEST_KEY_VIP_SIGN_IN, this)`:
     - Đọc `forceReauth`, `expectedOwnerId`, `operationId`.
     - Tính `needsSignIn = isExpired || forceReauth`.
     - Nếu `expectedOwnerId != null && user != null && user.id != expectedOwnerId`: bỏ qua request reauth lệch owner để tránh ô nhiễm phiên.
     - Nếu `needsSignIn`: kích hoạt `startSignInForVipContinuation(targetAction)` ngay cả khi `isExpired == false`.
   - Trong `executeVipContinuation` (RESTORE):
     - Truyền callback `onAuthRequired = { authMessage -> Toast.makeText(ctx, authMessage, Toast.LENGTH_LONG).show() }` vào `billingManager.restorePurchases(...)` để nếu backend 401 lần 2 sau reauth thì dừng và thông báo, không đệ quy reauth.

4. **`app/src/main/java/com/tscanner/app/ui/viewer/PdfViewerActivity.kt`** & **`app/src/main/java/com/tscanner/app/ui/idcard/IdCardComposeActivity.kt`**:
   - Cập nhật `executeRestorePurchases` truyền `onAuthRequired` handler thông báo cho người dùng, ngăn chặn lặp reauth nếu backend tiếp tục từ chối.

---

## 3. Kết quả kiểm thử (Verification Evidence)

Lệnh thực thi:
```powershell
$env:JAVA_HOME = 'C:\Users\nguye\.jdks\openjdk-21.0.1'
$env:GRADLE_USER_HOME = 'C:\Users\nguye\.gradle'
.\gradlew.bat :app:testDebugUnitTest --tests "*MainActivityNavigationTest*" --tests "*VipViewerLoginContinuationTest*" --tests "*VipIdCardLoginContinuationTest*" --tests "*VipLoginContinuationTest*" --tests "*VipSessionExpiryRegressionTest*" --offline --console=plain
```

### Kết quả: **BUILD SUCCESSFUL** (Tất cả test passes 100%)
- `MainActivityNavigationTest`:
  - `testVipNavigationBundleContract_preservesForceReauthAndOwner`: PASS.
  - `testReauthDecisionLogic_forcesReauthWhenBackend401EvenIfTokenNotExpired`: PASS (G03: forceReauth kích hoạt reauth ngay cả khi `isTokenExpired == false`).
  - `testReauthDecisionLogic_rejectsMismatchedOwner`: PASS (loại bỏ reauth request nếu lệch ownerId).
- `VipViewerLoginContinuationTest`: PASS.
- `VipIdCardLoginContinuationTest`: PASS.
- `VipLoginContinuationTest`: PASS.
- `VipSessionExpiryRegressionTest`: PASS (10/10).

---

## 4. Hợp đồng bàn giao cho Gói S04 (Contract Handover)

1. **Vấn đề cần giải quyết ở S04:** G03 (Nhánh Verify receipt sau mua):
   - Trong `PurchaseVerifier.kt` / `PlayPurchaseVerifier.kt`: HTTP 401 khi xác thực biên lai đang bị bọc thành `TransientError` (coi là lỗi mạng tạm thời). Cần chuyển thành typed outcome `VerificationResult.AuthRequired`.
   - Trong `BillingManager.kt`: khi verify trả về `AuthRequired`, lưu/giữ recovery context đúng owner và biên lai (receipt). Sau reauth, thực hiện verify/reconcile lại receipt hiện có, KHÔNG gọi `launchBillingFlow` lần nữa (không mua lại).
   - Mục tiêu: Chuyển Probe **P06** từ FAIL $\to$ PASS.
