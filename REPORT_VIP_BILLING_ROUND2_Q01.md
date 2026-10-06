# Báo cáo Nghiệm thu VIP Billing Vòng 2 — Gói Q01

Ngày thực hiện: 26/09/2026  
Mã gói: **Q01 — Khóa migration legacy và guest ownership (R02)**  
Môi trường: Windows / PowerShell / Gradle 9.7.1  
Repo: `E:\DU AN AI\T-Scanner`

---

## 1. Mục tiêu và Kết quả gói Q01

Gói Q01 giải quyết dứt điểm khiếm khuyết **R02** (Giao dịch đã thuộc User A bị dựng lại thành guest rồi gắn cho User B):
1. **Khóa dựng `VERIFIED_ACTIVE` từ global last receipt:**
   - Khi có cờ boolean `billing_vip_active` từ phiên bản cũ (legacy SharedPreferences), giao dịch được di trú dưới dạng `EntitlementState.UNVERIFIED_CLIENT` (ứng viên chưa xác thực), tuyệt đối không tự động biến thành `VERIFIED_ACTIVE` cấp VIP miễn phí.
   - Cờ legacy `billing_vip_active` được xóa (`remove`) sau khi đánh giá để không tái sinh lặp lại trên mỗi lần đăng nhập.
2. **Khóa chuyển quyền cục bộ giữa các tài khoản người dùng:**
   - Trong `BillingEntitlementStore`, hàm `isTokenBoundToOtherUser` kiểm tra quyền sở hữu của `purchaseToken` trên toàn bộ snapshot người dùng trong store.
   - Hàm `bindGuestEntitlementsToUser` từ chối gắn bất kỳ token nào đã thuộc về tài khoản đã đăng ký khác.
   - Bản ghi của User A trong `user_entitlements_A` được bảo toàn nguyên vẹn, không bị mất hoặc bị ghi đè.
3. **Bảo toàn monotonic version từ server:**
   - Khi bind guest entitlement vào tài khoản cục bộ, không tự tiện tăng `snapshotVersion` (`snapshotVersion = item.snapshotVersion`), giữ đúng version authoritative.

---

## 2. Danh sách file và Tuân thủ Whitelist

| File | Trạng thái | Ghi chú |
|---|---|---|
| `app/src/main/java/com/tscanner/app/utils/BillingManager.kt` | MODIFIED | Chỉ sửa logic di trú legacy và bind trong `bindPurchasesToCurrentUser` |
| `app/src/main/java/com/tscanner/app/utils/billing/BillingEntitlementStore.kt` | MODIFIED | Thêm `isTokenBoundToOtherUser`, chặn bind token của user khác, giữ version |
| `app/src/test/java/com/tscanner/app/BillingGuestOwnershipTest.kt` | CREATED | 7 unit tests bao phủ toàn diện các ca kiểm thử của Q01 |
| `REPORT_VIP_BILLING_ROUND2_Q01.md` | CREATED | Báo cáo gói Q01 |
| Các subsystem khác (OCR/Drive/Auth...) | PRESERVED | Không sửa đổi ngoài whitelist |

---

## 3. Bằng chứng kiểm thử: Trước Đỏ / Sau Xanh

### 3.1. Probe R02 trong `BillingRound2RegressionTest`
- **Trước sửa (Q00):**
  ```text
  BillingRound2RegressionTest > probeOwnedReceiptMustNotReappearAsGuestAndBindToB FAILED
      java.lang.AssertionError: Global last receipt recreated as verified legacy and bound to B
  ```
- **Sau sửa (Q01):**
  ```powershell
  $env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
  .\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.BillingRound2RegressionTest.probeOwnedReceiptMustNotReappearAsGuestAndBindToB
  ```
  **BUILD SUCCESSFUL** (Test case chuyển sang **GREEN**).

### 3.2. Bộ kiểm thử mới: `BillingGuestOwnershipTest`
```powershell
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.BillingGuestOwnershipTest
```
- **Kết quả:** 7/7 tests PASS (100% GREEN):
  1. `testUserAPurchases_thenUserBLogsIn_thenUserCLogsIn_noVipGrantedToBOrC`: A mua -> B login -> C login không được cấp VIP từ A; bản ghi A còn nguyên.
  2. `testTrueGuestPurchase_differsFromLegacy_bindsToUserOnSignIn`: Guest mua thật khác legacy, chuyển giao đúng cho user khi đăng nhập.
  3. `testLegacyLocalPurchase_migratedAsUnverifiedClient_doesNotGrantPaidVip`: Di trú legacy chuyển sang `UNVERIFIED_CLIENT`, không tự cấp VIP.
  4. `testOwnerConflict_guestStoreTokenBelongsToUserA_cannotBindToUserB`: Token đã thuộc A thì guest store không thể bind sang B.
  5. `testMultipleLogins_idempotentAndSafe`: Đăng nhập nhiều lần idempotent, không tăng version hay dời hạn.
  6. `testCrashBetweenBindAndClear_secondCallClearsAndDoesNotDuplicate`: Xử lý an toàn khi crash giữa chừng lúc bind và xóa guest store.
  7. `testLocalBindDoesNotIncrementSnapshotVersion`: Bind cục bộ không tăng version server.

### 3.3. Kiểm thử hồi quy liên quan
```powershell
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.BillingEntitlementStoreTest --tests com.tscanner.app.BillingManagerTest --tests com.tscanner.app.BillingPurchaseFixtureTest --tests com.tscanner.app.BillingGuestOwnershipTest
```
- **Kết quả:** 36 tests completed, 0 failures, 0 errors. **BUILD SUCCESSFUL**.

### 3.4. Trạng thái toàn bộ `BillingRound2RegressionTest`
- Trước Q01: 7 failed, 3 passed.
- Sau Q01: 6 failed, **4 passed** (`probeOwnedReceiptMustNotReappearAsGuestAndBindToB` + `probePendingFixtureMustRepresentPendingPurchase` + 2 controls).

---

## 4. Contract và API Handoff sang Q02

- **Trạng thái di trú:**
  - Token legacy chỉ được lưu với state `UNVERIFIED_CLIENT`.
  - Các gói sau (Q04 reconciliation, Q11 client verifier) sẽ nhận các candidate `UNVERIFIED_CLIENT` này để gửi lên backend xác minh thay vì tự cấp quyền tại client.
- **Quyền sở hữu:**
  - Giao dịch đã xác thực gắn chặt với `ownerAppUserId` của phiên giao dịch. Không thể bị rò rỉ sang tài khoản khác qua SharedPreferences dùng chung.
- **Gói tiếp theo:**
  - **Q02 — Context bất biến cho purchase/restore/reconcile (R04 và phần session R03)**.

---

## 5. Rủi ro và Việc chưa chạy (NOT RUN)

- 6 probe còn lại trong `BillingRound2RegressionTest` (R03, R04, R05, R06) và 3 probe backend (R07, R09, R10) vẫn đang FAIL theo đúng thiết kế để các gói Q02–Q10 giải quyết tuần tự.
- Chưa chạy kiểm thử trên thiết bị thật Google Play Live (giữ nguyên gate theo quy định).
