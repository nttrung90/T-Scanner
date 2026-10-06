# Báo cáo kết quả VIP Billing Vòng 2 — Gói Q03

**Ngày thực hiện:** 26/09/2026  
**Kho mã:** `E:\DU AN AI\T-Scanner`  
**Mục tiêu gói Q03:** Thực thi Monotonic Snapshot (Defect R06) và truyền báo lỗi lưu trữ cục bộ, không báo mua thành công giả mạo (Defect R05).

---

## 1. Tóm tắt nguyên nhân gốc & Giải pháp kỹ thuật

### Nguyên nhân gốc (Defect R05 & R06)
1. **R06 — Snapshot cùng version được phép hồi sinh quyền đã revoke**:
   - `BillingEntitlement.kt:183` trước đây dùng toán tử `>=` (`incomingItem.snapshotVersion >= existingItem.snapshotVersion`), cho phép snapshot cùng phiên bản (version) ghi đè lên dữ liệu cũ bất kể nội dung khác nhau. Khi một receipt có trạng thái REVOKED version 2 nhận snapshot ACTIVE version 2, trạng thái REVOKED bị xóa bỏ, dẫn đến hồi sinh VIP trái phép.
2. **R05 — Lưu entitlement thất bại vẫn báo mua thành công**:
   - `BillingManager.kt` trước đây bỏ qua giá trị `Boolean` trả về từ `BillingEntitlementStore.applyEntitlement` và `AppAuthManager.applyEntitlement`.
   - Khi `SharedPreferences.commit()` trả về `false` (lỗi I/O, hết bộ nhớ, quyền truy cập), `applyVerifiedEntitlement` vẫn không báo lỗi và `processPurchase` tiếp tục gọi `tryNotifyPurchaseSuccess(...)` và `onComplete(true)`.
   - Ngoài ra, mã cũ gọi lưu 2 lần (lần 1 ở `store.applyEntitlement`, lần 2 ở `AppAuthManager.applyEntitlement`) gây dư thừa và không truyền đúng trạng thái lỗi.

### Giải pháp kỹ thuật trong Q03
1. **Quy tắc Monotonic Merge & Bảo vệ xung đột cùng Version (R06)**:
   - Trong `UserEntitlementSnapshot.mergeNewerSnapshot()`:
     - `incoming.snapshotVersion > existing.snapshotVersion`: Cập nhật phiên bản mới hơn.
     - `incoming.snapshotVersion == existing.snapshotVersion`:
       - Nếu payload giống hệt (`isPayloadIdentical`): Replay bất biến (idempotent no-op), giữ nguyên dữ liệu không đột biến ngày hết hạn.
       - Nếu payload xung đột/khác nhau (ví dụ một bên REVOKED, một bên VERIFIED_ACTIVE): Ném `IllegalArgumentException` / trả `ApplySnapshotResult.Conflict`. Giữ nguyên dữ liệu authoritative hiện tại, tuyệt đối không ghi đè hoặc hồi sinh VIP.
     - `incoming.snapshotVersion < existing.snapshotVersion`: Bỏ qua snapshot lỗi thời.
     - Kiểm tra `ownerAppUserId` của từng item con phải khớp với `ownerAppUserId` của snapshot.
2. **API lưu trữ có kiểu (Typed Result) trong `BillingEntitlementStore`**:
   - Bổ sung `sealed class ApplySnapshotResult`:
     - `Success(snapshot)`: Ghi nhận và commit thành công vào SharedPreferences.
     - `Conflict(reason)`: Phát hiện xung đột phiên bản hoặc xung đột tài khoản sở hữu.
     - `PersistenceFailed(message)`: Lỗi khi commit SharedPreferences hoặc ngoại lệ lưu trữ.
   - Thêm phương thức `applySnapshotTyped()` và chuẩn hóa `applySnapshot()` trả về `Boolean` theo kết quả typed.
   - Trong `persistSnapshot()`, không xâu chuỗi trực tiếp `.putString().commit()` để bảo toàn nguyên vẹn tham chiếu wrapper của Editor trong môi trường kiểm thử lỗi.
3. **Lan truyền lỗi lưu trữ và chặn thông báo thành công (R05)**:
   - Trong `BillingManager.applyVerifiedEntitlement()`: Trả về `Boolean`. Nếu `store.applyEntitlement()` thất bại, hàm trả về `false` ngay lập tức.
   - Trong `BillingManager.processPurchase()`:
     - Khi `applyVerifiedEntitlement()` trả về `false`: Kích hoạt `tryNotifyPurchaseFailure()` với thông báo lỗi rõ ràng ("Lỗi lưu quyền VIP trên thiết bị") và gọi `onComplete?.invoke(false)`.
     - Nếu receipt được xác thực thành công nhưng trạng thái không phải `VERIFIED_ACTIVE` (ví dụ `REVOKED` hoặc `EXPIRED`): Lưu tombstone vào store, nhưng thông báo `tryNotifyPurchaseFailure` ("Gói VIP không còn hoạt động") và `onComplete(false)`, không bao giờ báo VIP đã kích hoạt.
   - Loại bỏ hoàn toàn việc lưu 2 lần giữa `BillingManager` và `AppAuthManager`: sau khi store lưu thành công, `AppAuthManager.applyEntitlementSnapshot()` chỉ cần nạp snapshot tươi từ store để cập nhật profile.

---

## 2. Danh sách tệp chỉnh sửa & tạo mới (Whitelist Q03)

| STT | Đường dẫn tệp | Loại thay đổi | Trách nhiệm |
|---|---|---|---|
| 1 | `app/src/main/java/com/tscanner/app/utils/billing/BillingEntitlement.kt` | Chỉnh sửa | Cập nhật `mergeNewerSnapshot`, thêm `isPayloadIdentical`, từ chối xung đột cùng version và lệch owner item. |
| 2 | `app/src/main/java/com/tscanner/app/utils/billing/BillingEntitlementStore.kt` | Chỉnh sửa | Bổ sung `ApplySnapshotResult`, `applySnapshotTyped`, tách biệt tham chiếu Editor để bắt đúng lỗi `commit()`. |
| 3 | `app/src/main/java/com/tscanner/app/utils/BillingManager.kt` | Chỉnh sửa | `applyVerifiedEntitlement` trả boolean; `processPurchase` chặn báo thành công khi lưu thất bại hoặc receipt inactive. |
| 4 | `app/src/test/java/com/tscanner/app/BillingSnapshotPersistenceTest.kt` | Tạo mới | Bộ kiểm thử 10 kịch bản cho monotonic snapshot, version conflict và persistence fault injection. |
| 5 | `REPORT_VIP_BILLING_ROUND2_Q03.md` | Tạo mới | Báo cáo kiểm định gói Q03. |

---

## 3. Bằng chứng kiểm thử: Trước đỏ (Red) / Sau xanh (Green)

### 3.1. Probe Regression Vòng 2

Lệnh thực thi:
```powershell
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.BillingRound2RegressionTest
```

Kết quả:
- **`probeEqualVersionCannotResurrectRevokedState`**:
  - *Trước sửa:* **FAIL** (ACTIVE version 2 ghi đè REVOKED version 2 -> VIP active = true).
  - *Sau sửa:* **PASS** (Conflict được phát hiện; giữ nguyên REVOKED version 2 -> VIP active = false).
- **`probeCommitFailureMustNotReportPurchaseSuccess`**:
  - *Trước sửa:* **FAIL** (commit() = false nhưng processPurchase callback true).
  - *Sau sửa:* **PASS** (Lỗi commit được phát hiện; processPurchase callback false).
- **Tổng thể bộ probe**: Hiện tại **8/10 PASS** (R02, R04, R05, R06, R11 + 2 controls). Chỉ còn đúng 2 probe dành riêng cho gói tiếp theo Q04 (`probeRevokedReceiptMustNotReturnOnReload` và `probeEmptyPlayQueryMustPreservePromotion`).

### 3.2. Bộ kiểm thử mới `BillingSnapshotPersistenceTest` (10/10 PASS)

Lệnh thực thi:
```powershell
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.BillingSnapshotPersistenceTest
```

Danh sách ca kiểm thử:
1. `testEqualVersionConflictingPayload_keepsExistingAndRejectsOverwrite`: **PASS** (Xung đột cùng version giữ nguyên trạng thái cũ, trả `Conflict`).
2. `testEqualVersionIdenticalPayload_idempotentReplayNoOp`: **PASS** (Replay cùng version giống payload thành công bất biến, không cộng dồn ngày).
3. `testNewerVersion_overwritesOlderVersion`: **PASS** (Version mới hơn v3 cập nhật đè lên v2).
4. `testOlderVersion_doesNotOverwriteNewerVersion`: **PASS** (Version cũ v2 không làm suy thoái v3).
5. `testItemOwnerMismatchWithSnapshotOwner_rejectedWithConflict`: **PASS** (Item mang owner khác với snapshot owner bị từ chối `Conflict`).
6. `testCommitFailure_returnsPersistenceFailedAndDoesNotReportPurchaseSuccess`: **PASS** (Lỗi commit trả `PersistenceFailed` và callback báo false, UI không hiện thành công).
7. `testCommitThrowsException_returnsPersistenceFailed`: **PASS** (Ngoại lệ I/O/Security trong commit được bắt an toàn và trả `PersistenceFailed`).
8. `testReceiptVerifiedInactive_persistsTombstone_doesNotReportPurchaseSuccess`: **PASS** (Receipt REVOKED được lưu tombstone nhưng báo thất bại, không kích hoạt VIP).
9. `testRestartRecovery_persistedSnapshotSurvivesRecreation`: **PASS** (Dữ liệu đã lưu sống sót qua khởi động lại/tái tạo store và manager).
10. `testReplayAfterRetry_preservesOriginalExpiryAndVersion`: **PASS** (Retry nhiều lần không làm lệch hạn dùng tuyệt đối).

### 3.3. Kiểm tra hồi quy toàn diện các bộ kiểm thử Billing (53/53 PASS)

Lệnh thực thi:
```powershell
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.BillingSnapshotPersistenceTest --tests com.tscanner.app.BillingOperationSessionTest --tests com.tscanner.app.BillingGuestOwnershipTest --tests com.tscanner.app.BillingPurchaseFixtureTest --tests com.tscanner.app.BillingEntitlementStoreTest --tests com.tscanner.app.BillingManagerTest
```
Kết quả: **BUILD SUCCESSFUL**, toàn bộ 53/53 tests PASS.

---

## 4. Bàn giao hợp đồng & Ngữ cảnh cho Gói Q04

1. **Handoff cho Gói Q04 (R03)**:
   - `BillingEntitlementStore.kt` đã có API typed `applySnapshotTyped()` và cơ chế monotonic snapshot hoàn chỉnh.
   - Gói Q04 sẽ chuyển đổi `BillingReconciliation.kt` từ việc hạ `FREE` mù sang tạo snapshot authoritative bền vững trong store theo owner/context/version, đồng thời bảo vệ nguồn quyền `PROMOTIONAL` / `MANUAL_PROMO` khi truy vấn Google Play rỗng.
2. **Bảo toàn workspace**:
   - Tất cả mã unstaged / untracked / staging hiện có được bảo toàn 100%. Không thực hiện `git reset` hay `git checkout`.
