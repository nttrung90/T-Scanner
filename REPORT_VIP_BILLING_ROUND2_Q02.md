# Báo cáo kết quả VIP Billing Vòng 2 — Gói Q02

**Ngày thực hiện:** 26/09/2026  
**Kho mã:** `E:\DU AN AI\T-Scanner`  
**Mục tiêu gói Q02:** Ngăn chặn tái sử dụng `activePurchaseOwnerUserId` cho Restore / Sync / Reconcile (Defect R04) và cô lập phiên tài khoản bằng Session Generation (một phần Defect R03).

---

## 1. Tóm tắt nguyên nhân gốc & Giải pháp

### Nguyên nhân gốc (Defect R04 & R03 Session Staleness)
- `BillingManager.kt` dùng biến dùng chung `activePurchaseOwnerUserId` cho cả các luồng RESTORE và RECONCILE. Khi User A mở luồng mua rồi User B đăng nhập và bấm "Khôi phục gói VIP", yêu cầu xác thực (`VerificationRequest`) vẫn mang owner `UserA` thay vì `UserB`.
- Không có đối tượng ngữ cảnh thao tác (`BillingOperationContext`) mang tính bất biến (immutable) ghi lại ID thao tác, user ID chủ thể, session generation tại thời điểm bắt đầu thao tác.
- Khi người dùng chuyển đổi phiên đăng nhập nhanh (A -> B hoặc A -> B -> A), các callback trễ từ Google Play hoặc tiến trình xác thực cũ vẫn phát tín hiệu hoàn tất/hủy/lỗi đến phiên đăng nhập mới.

### Giải pháp kỹ thuật trong Q02
1. **Tạo đối tượng ngữ cảnh bất biến `BillingOperationContext`**:
   - `operationId`: UUID duy nhất cho từng thao tác.
   - `ownerAppUserId`: User ID chủ sở hữu tại thời điểm phát lệnh (hoặc `null` nếu là khách vãng lai).
   - `sessionGeneration`: Thế hệ phiên đăng nhập từ `AppAuthManager.getSessionGeneration()`.
   - `operationType`: Phân loại thao tác (`PURCHASE`, `RESTORE`, `RECONCILE`, `SYNC`).
   - Hàm `isStale(currentUserId, currentGeneration)`: Phát hiện tức thì nếu phiên hiện tại không còn khớp với ngữ cảnh thao tác lúc khởi tạo.
2. **Loại bỏ việc kế thừa `activePurchaseOwnerUserId` trong RESTORE / RECONCILE**:
   - Trong `BillingManager.processPurchase()`, `targetOwnerId` được xác định ưu tiên từ `operationContext.ownerAppUserId` (kể cả khi bằng `null` cho khách).
   - Nếu `operationContext` không được cung cấp, các nguồn gốc `RESTORE` và `RECONCILE` chỉ lấy `AppAuthManager.getCurrentUser()?.id` hiện tại, tuyệt đối không bao giờ mượn `activePurchaseOwnerUserId` của luồng mua trước.
3. **Bảo vệ toàn diện trước Callback trễ (Stale Callbacks)**:
   - Các trường hợp lỗi Google Play (`USER_CANCELED`, `ITEM_ALREADY_OWNED`, `ERROR`, `BILLING_UNAVAILABLE`, v.v.) trong `onPurchasesUpdated()` kiểm tra `activePurchaseContext.isStale()`. Nếu phiên đã thay đổi, toàn bộ callback UI bị hủy bỏ, không hiển thị thông báo lỗi sai lệch cho tài khoản mới.
   - Khi dispatch callback ra Main Thread qua `Handler(Looper.getMainLooper()).post(...)`, kiểm tra lại `operationContext.isStale()` tại thời điểm thực thi để loại trừ race condition khi người dùng đổi tài khoản ngay trước khi Runnable chạy.
   - Trong `BillingReconciliation.kt`, kiểm tra staleness ở cả 2 chốt chặn: trước khi xử lý danh sách purchase và sau khi hoàn tất toàn bộ các purchase con.

---

## 2. Danh sách tệp chỉnh sửa & tạo mới (Whitelist Q02)

| STT | Đường dẫn tệp | Loại thay đổi | Trách nhiệm |
|---|---|---|---|
| 1 | `app/src/main/java/com/tscanner/app/utils/billing/BillingOperationContext.kt` | Tạo mới | Định nghĩa ngữ cảnh bất biến `BillingOperationContext` & `BillingOperationType`. |
| 2 | `app/src/main/java/com/tscanner/app/utils/billing/BillingReconciliation.kt` | Chỉnh sửa | Nhận `operationContext`, hủy bỏ kết quả reconciliation nếu phiên/user đã thay đổi. |
| 3 | `app/src/main/java/com/tscanner/app/utils/BillingManager.kt` | Chỉnh sửa | Tách biệt owner context cho RESTORE/SYNC/PURCHASE; lọc bỏ stale callbacks ở UI & main thread. |
| 4 | `app/src/test/java/com/tscanner/app/BillingOperationSessionTest.kt` | Tạo mới | Bộ kiểm thử chuyên biệt 7 kịch bản cho ngữ cảnh thao tác & phiên đăng nhập. |
| 5 | `REPORT_VIP_BILLING_ROUND2_Q02.md` | Tạo mới | Báo cáo kiểm định gói Q02. |

---

## 3. Bằng chứng kiểm thử: Trước đỏ (Red) / Sau xanh (Green)

### 3.1. Probe Regression Vòng 2

Lệnh thực thi:
```powershell
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.BillingRound2RegressionTest
```

Kết quả:
- **`probeRestoreMustNotReusePreviousPurchaseOwner`**:
  - *Trước sửa:* **FAIL** (Restore thừa kế stale owner "A" từ `activePurchaseOwnerUserId` thay vì "B").
  - *Sau sửa:* **PASS** (Requested owner = "B").
- **`probeOldEmptyQueryMustNotRevokeNewAccount`**:
  - *Trước sửa:* **FAIL** (Query trễ của User A làm hạ cấp tài khoản User B về FREE).
  - *Sau sửa:* **PASS** (Reconciliation trễ của User A bị loại bỏ, User B giữ nguyên VIP).

### 3.2. Bộ kiểm thử mới `BillingOperationSessionTest` (7/7 PASS)

Lệnh thực thi:
```powershell
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.BillingOperationSessionTest
```

Danh sách ca kiểm thử:
1. `testUserALaunchesPurchase_thenUserBRestores_restoreRequestsUnderBNotA`: **PASS** (Restore của B gửi request xác thực mang `ownerAppUserId = UserB`).
2. `testDelayedQueryForAArrivesAfterUserBLogsIn_doesNotMutateOrRevokeB`: **PASS** (Query rỗng từ phiên User A đến sau khi User B đăng nhập bị hủy, VIP của B được bảo toàn).
3. `testRapidUserSwitch_A_to_B_to_A_sessionGenerationProtectsCallbacks`: **PASS** (Chuyển nhanh A -> B -> A làm tăng `sessionGeneration`; callback từ thế hệ 1 của A bị chặn lại).
4. `testOverlappingOperations_purchaseAndRestoreConcurrently_isolatedContexts`: **PASS** (Purchase và Restore chạy song song không đè context hay ID của nhau).
5. `testGuestPurchaseInFlight_userLogsInBeforeVerifyAckCompletes_appliedToStore_callbackGuarded`: **PASS** (Khách mua chưa xong thì đăng nhập: quyền lưu vào namespace khách an toàn trong store, callback UI cho user mới được chặn).
6. `testStaleCallbacks_canceledAndErrorAndAlreadyOwned_suppressedOnSessionChange`: **PASS** (Các sự kiện CANCELED, ITEM_ALREADY_OWNED và ERROR bị triệt tiêu khi đổi phiên).
7. `testMainThreadDispatch_staleCheckAtExecutionTime`: **PASS** (Runnable trễ trên main thread tự kiểm tra staleness trước khi phát callback).

### 3.3. Kiểm tra hồi quy toàn diện các bộ test Billing hiện hữu (43/43 PASS)

Lệnh thực thi:
```powershell
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.BillingGuestOwnershipTest --tests com.tscanner.app.BillingPurchaseFixtureTest --tests com.tscanner.app.BillingEntitlementStoreTest --tests com.tscanner.app.BillingManagerTest --tests com.tscanner.app.BillingOperationSessionTest
```
Kết quả: **BUILD SUCCESSFUL**, toàn bộ 43/43 tests PASS.

---

## 4. Bàn giao hợp đồng & Ngữ cảnh cho các gói tiếp theo

1. **Handoff cho Gói Q03 (R05 / R06)**:
   - `BillingManager.kt` và `BillingEntitlementStore.kt` đã sẵn sàng nhận kết quả lưu snapshot có kiểu rõ ràng (`applySnapshot` trả boolean / typed result).
   - Q03 sẽ xử lý việc `store.applyEntitlement()` thất bại không được báo mua thành công lên UI (`probeCommitFailureMustNotReportPurchaseSuccess`) và xử lý xung đột cùng version (`probeEqualVersionCannotResurrectRevokedState`).
2. **Handoff cho Gói Q04 (R03)**:
   - `BillingReconciliation.kt` đã có `operationContext` tích hợp; Q04 sẽ mở rộng việc thu hồi bền vững vào `BillingEntitlementStore` và bảo vệ nguồn `PROMOTIONAL` khi Play query trả rỗng.
3. **Phạm vi bảo toàn**:
   - Tất cả mã unstaged / untracked / staging hiện có được giữ nguyên 100%. Không thực hiện `git reset` hay `git checkout`.
