# Báo Cáo Kiểm Thử & Bàn Giao Gói U01 (K01: Hạn Mức Reauth Theo User Operation)

**Ngày thực hiện:** 05/10/2026  
**Workspace:** `E:\DU AN AI\T-Scanner`  
**Gói công việc:** U01 (Theo `PLAN_FIX_VIP_SESSION_ROUND4_GEMINI_2026-10-05.md`)

---

## 1. Mục Tiêu & Phạm Vi (Scope)

- **Mục tiêu:**
  - Khắc phục khiếm khuyết **K01**: Hạn mức reauth bị tiêu thụ trước khi người dùng được xác thực, và tồn tại suốt đời của BillingManager singleton.
  - Tách bạch receipt identity khỏi identity của thao tác người dùng.
  - Xây dựng khoá quản lý recovery: `operationId + targetOwnerId + sessionGeneration + processEpoch + purchaseToken`.
  - Không tiêu tốn hạn mức khi chạy ngầm (`origin == RECONCILE`) hoặc khi chưa có UI listener nào tiếp nhận sự kiện (`authRequiredListeners.isEmpty()`).
  - Cho phép người dùng chủ động thử lại bằng thao tác mới (`new BillingOperationContext`) mà không bị kẹt cờ `isRetry = true` kế thừa từ thao tác trước đó.
  - Bảo toàn giới hạn tối đa 1 lần reauth trong mỗi thao tác người dùng để ngăn chặn vòng lặp xác thực vô hạn.

---

## 2. Thay Đổi Mã Nguồn Sản Xuất

1. **`app/src/main/java/com/tscanner/app/utils/BillingManager.kt`:**
   - Thay thế `authRecoveryAttemptedTokens` (chỉ lưu `purchaseToken`) bằng `attemptedRecoveryKeys = ConcurrentHashMap.newKeySet<String>()`.
   - Bổ sung hàm tiện ích `getRecoveryKey`:
     ```kotlin
     private fun getRecoveryKey(
         opContext: BillingOperationContext?,
         targetOwnerId: String?,
         sessionGeneration: Long,
         processEpoch: String,
         purchaseToken: String
     ): String {
         val opId = opContext?.operationId ?: "standalone_op"
         return "$opId:${targetOwnerId ?: "guest"}:$sessionGeneration:$processEpoch:$purchaseToken"
     }
     ```
   - Trong `destroy()`: giải phóng `attemptedRecoveryKeys.clear()`.
   - Trong nhánh xử lý `VerificationResult.AuthRequired`:
     - Kiểm tra tương tác: `isInteractive = (origin == BillingOperationOrigin.PURCHASE) && !isStale`.
     - Kiểm tra UI listener: `hasUiListener = authRequiredListeners.isNotEmpty()`.
     - Chỉ ghi nhận vào `attemptedRecoveryKeys` khi: `isInteractive && hasUiListener && !alreadyRetried`.
     - Nhờ đó, các kiểm tra nền (`RECONCILE`) hoặc kiểm tra khi UI chưa sẵn sàng không tiêu tốn lượt phục hồi của người dùng.

---

## 3. Bằng Chứng Kiểm Thử (Evidence)

- **Lệnh thực thi:**
  ```powershell
  $env:JAVA_HOME = 'C:\Users\nguye\.jdks\openjdk-21.0.1'; $env:GRADLE_USER_HOME = 'C:\Users\nguye\.gradle'
  .\gradlew.bat :app:testDebugUnitTest --offline -I docs/vip-session-round4-reaudit-20261005/audit.init.gradle --tests 'com.tscanner.app.VipSessionRound4ProbeTest.P01*' --tests 'com.tscanner.app.VipSessionRound4ProbeTest.P02*' --tests 'com.tscanner.app.VipSessionRound4ProbeTest.P03*' --tests 'com.tscanner.app.VipSessionRound4ProbeTest.C01*' --console=plain
  ```
- **Kết quả: 4/4 PASSED (BUILD SUCCESSFUL)**:
  - `P01_backgroundVerificationMustNotSpendInteractiveRecoveryAttempt`: **PASSED** (RECONCILE ngầm không tiêu tốn lượt phục hồi tương tác).
  - `P02_noListenerMustNotSpendRecoveryBeforeUserCanAuthenticate`: **PASSED** (Không có UI listener nhận sự kiện thì không bị tính là đã reauth).
  - `P03_newExplicitOperationMustNotInheritReceiptLifetimeRetryFlag`: **PASSED** (Thao tác mới với operation ID mới có ngân sách reauth mới, không bị thừa kế cờ retry cũ).
  - `C01_firstInteractiveEventIsNotRetryAndDoesNotGrantVip`: **PASSED** (Sự kiện tương tác đầu tiên không bị coi là retry và không tự cấp VIP).

---

## 4. Nghiệm thu & Chuyển giao
- Tiêu chí gói U01: **ĐẠT (PASS)**.
- Tự động chuyển sang gói **U02** để giải quyết **K02** (thẩm tra event tại thời điểm tiêu thụ và ngăn chặn event cũ lọt sang consumer khi đổi session).
