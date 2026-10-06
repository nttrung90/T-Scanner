# Báo cáo kết quả VIP Billing Vòng 2 — Gói Q04

**Ngày thực hiện:** 26/09/2026  
**Kho mã:** `E:\DU AN AI\T-Scanner`  
**Mục tiêu gói Q04:** Thực thi Authoritative Reconciliation & Thu hồi quyền có phân lập nguồn gốc (Defect R03).

---

## 1. Tóm tắt nguyên nhân gốc & Giải pháp kỹ thuật

### Nguyên nhân gốc (Defect R03)
1. **Catalog rỗng từ Google Play không thu hồi receipt cũ**:
   - Khi người dùng hủy gia hạn, được hoàn tiền hoặc gỡ receipt trên Google Play, việc khôi phục giao dịch (`restorePurchases` / `reconcile`) nhận kết quả OK nhưng rỗng từ Google Play Billing SDK.
   - Mã cũ không cập nhật trạng thái `REVOKED` vào `BillingEntitlementStore`, dẫn đến việc reload ứng dụng hoặc nạp lại phiên đăng nhập tiếp tục hồi sinh VIP từ receipt cục bộ đã mất hiệu lực.
2. **Thu hồi nhầm các quyền không thuộc Google Play**:
   - Khi Google Play trả về danh sách rỗng, nếu xóa mù quáng toàn bộ quyền trong store, các gói VIP có nguồn gốc khác (ví dụ `PROMOTIONAL`, mã ưu đãi, voucher nội bộ) sẽ bị xóa oan dù không liên quan đến Play Store.
3. **Pending purchases bị revoke nhầm khi query chưa hoàn tất**:
   - Nếu trong danh sách giao dịch có giao dịch đang ở trạng thái `PENDING` (chờ thanh toán chậm), việc vội vàng kết luận không có quyền hoạt động và thu hồi là sai quy trình.
4. **Phản hồi tiến độ không đầy đủ**:
   - `ReconciliationResult.Restored` trước đây chỉ chứa số lượng thành công `count`, thiếu `totalCount` và `failedCount`, khiến UI/Caller không phân biệt được trường hợp khôi phục một phần (partial failure) so với thành công toàn bộ.

### Giải pháp kỹ thuật trong Q04
1. **Thu hồi quyền có phân lập nguồn gốc (Source-Isolated Revocation)**:
   - Trong `BillingReconciliation.kt`:
     - Khi cả truy vấn `SUBS` và `INAPP` từ Play Billing thành công (ResponseCode = OK) và danh sách giao dịch trả về hoàn toàn rỗng (`allPurchases.isEmpty()`):
     - Đọc snapshot của người dùng từ `BillingEntitlementStore`.
     - Chỉ đánh dấu `REVOKED` và tăng `snapshotVersion + 1L` cho các entitlement có nguồn gốc từ Google Play (`GOOGLE_PLAY_SUBSCRIPTION`, `GOOGLE_PLAY_INAPP`, `LEGACY_LOCAL`).
     - **Bảo toàn tuyệt đối** các entitlement có nguồn khác như `EntitlementSource.PROMOTIONAL`.
     - Lưu snapshot cập nhật vào store và đồng bộ sang profile người dùng thông qua `AppAuthManager.applyEntitlementSnapshot`.
2. **Bảo vệ an toàn khi có giao dịch PENDING**:
   - Kiểm tra `hasPendingItems = allPurchases.any { it.purchaseState == Purchase.PurchaseState.PENDING }`.
   - Nếu có bất kỳ giao dịch PENDING nào, không thực hiện thu hồi quyền cục bộ và trả về `ProcessingFailed` với thông điệp rõ ràng yêu cầu người dùng chờ Google xử lý.
3. **Mở rộng `ReconciliationResult.Restored`**:
   - Bổ sung `totalCount: Int` và `failedCount: Int` vào `ReconciliationResult.Restored(val count: Int, val totalCount: Int, val failedCount: Int)`.
   - Giúp Caller và giao diện người dùng hiển thị đúng thông tin khi có giao dịch xác thực thất bại trong một mẻ khôi phục nhiều sản phẩm.
4. **Bảo toàn trạng thái khi gặp lỗi mạng**:
   - Khi truy vấn Google Play gặp lỗi kết nối (ví dụ `SERVICE_UNAVAILABLE`), reconciliation trả về `NetworkError` và giữ nguyên trạng thái VIP đã lưu, không thu hồi hoặc xóa bỏ bất kỳ quyền nào.

---

## 2. Danh sách tệp chỉnh sửa & tạo mới (Whitelist Q04)

| STT | Đường dẫn tệp | Loại thay đổi | Trách nhiệm |
|---|---|---|---|
| 1 | `app/src/main/java/com/tscanner/app/utils/billing/BillingReconciliation.kt` | Chỉnh sửa | Mở rộng `ReconciliationResult.Restored`, thực thi thu hồi có phân lập nguồn Play, bảo toàn `PROMOTIONAL`, bảo vệ PENDING. |
| 2 | `app/src/test/java/com/tscanner/app/BillingRevocationReconciliationTest.kt` | Tạo mới | Bộ kiểm thử 7 kịch bản cho authoritative reconciliation, empty query revocation, non-Play preservation, pending guard, partial restore. |
| 3 | `app/src/test/java/com/tscanner/app/BillingManagerTest.kt` | Chỉnh sửa nhỏ | Bổ sung purchase vào fake client trong `testGuestPurchase_savedLocally_andBindsToUserOnSignIn` để phản ánh đúng giao dịch Google Play trong post-login sync. |
| 4 | `REPORT_VIP_BILLING_ROUND2_Q04.md` | Tạo mới | Báo cáo kiểm định gói Q04. |

---

## 3. Bằng chứng kiểm thử: Trước đỏ (Red) / Sau xanh (Green)

### 3.1. Toàn bộ Probe Regression Vòng 2 — Đạt 10/10 PASS (100% GREEN)

Lệnh thực thi:
```powershell
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.BillingRound2RegressionTest
```

Kết quả:
- **`probeRevokedReceiptMustNotReturnOnReload`**:
  - *Trước sửa:* **FAIL** (Restore với empty query không revoke; reload profile hồi sinh VIP active).
  - *Sau sửa:* **PASS** (Store ghi nhận tombstone REVOKED v2; reload giữ nguyên VIP inactive = false).
- **`probeEmptyPlayQueryMustPreservePromotion`**:
  - *Trước sửa:* **FAIL** (Empty Play query xóa toàn bộ quyền hoặc không phân lập nguồn).
  - *Sau sửa:* **PASS** (Gói PROMOTIONAL được bảo toàn trọn vẹn; VIP active = true).
- **Tổng thể bộ probe**: **10/10 PASS** — Đã hoàn thành 100% mục tiêu của toàn bộ các probe R02, R03, R04, R05, R06, R11 và 2 control probes!

### 3.2. Bộ kiểm thử mới `BillingRevocationReconciliationTest` (7/7 PASS)

Lệnh thực thi:
```powershell
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.BillingRevocationReconciliationTest
```

Danh sách ca kiểm thử:
1. `testEmptyPlayCatalog_revokesPlayEntitlements_andDoesNotResurrectOnReload`: **PASS** (Query rỗng thu hồi receipt Play, tăng snapshotVersion, không hồi sinh khi reload).
2. `testEmptyPlayCatalog_strictlyPreservesNonPlayPromotions`: **PASS** (Query rỗng từ Play không ảnh hưởng voucher/promotion nội bộ).
3. `testPendingPurchaseInCatalog_doesNotRevoke_andReturnsProcessingFailed`: **PASS** (Giao dịch PENDING chặn việc thu hồi quyền, trả về lỗi xử lý an toàn).
4. `testMultiplePlayPurchases_allVerified_restoresAll`: **PASS** (Khôi phục thành công đồng thời cả SUBS và INAPP).
5. `testStaleReconciliation_doesNotRevokeNewUserSession`: **PASS** (Truy vấn trễ của session cũ không thu hồi tài khoản mới đăng nhập).
6. `testPartialVerificationFailure_reportsCorrectCounts`: **PASS** (Khôi phục 1/2 sản phẩm báo đúng `count=1`, `totalCount=2`, `failedCount=1`).
7. `testNetworkErrorDuringQuery_preservesCachedState`: **PASS** (Lỗi mạng trả `NetworkError`, giữ nguyên VIP hiện có trong bộ nhớ và store).

### 3.3. Kiểm tra hồi quy toàn diện 8 bộ kiểm thử Billing (70/70 PASS)

Lệnh thực thi:
```powershell
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
.\gradlew.bat :app:testDebugUnitTest `
  --tests com.tscanner.app.BillingRound2RegressionTest `
  --tests com.tscanner.app.BillingSnapshotPersistenceTest `
  --tests com.tscanner.app.BillingRevocationReconciliationTest `
  --tests com.tscanner.app.BillingOperationSessionTest `
  --tests com.tscanner.app.BillingGuestOwnershipTest `
  --tests com.tscanner.app.BillingPurchaseFixtureTest `
  --tests com.tscanner.app.BillingEntitlementStoreTest `
  --tests com.tscanner.app.BillingManagerTest
```

Bảng tổng hợp kết quả:
| Suite kiểm thử | Số ca test | Thất bại | Bỏ qua | Trạng thái |
|---|---|---|---|---|
| `BillingEntitlementStoreTest` | 9 | 0 | 0 | **PASS** |
| `BillingGuestOwnershipTest` | 7 | 0 | 0 | **PASS** |
| `BillingManagerTest` | 14 | 0 | 0 | **PASS** |
| `BillingOperationSessionTest` | 7 | 0 | 0 | **PASS** |
| `BillingPurchaseFixtureTest` | 6 | 0 | 0 | **PASS** |
| `BillingRevocationReconciliationTest` | 7 | 0 | 0 | **PASS** |
| `BillingRound2RegressionTest` | 10 | 0 | 0 | **PASS** |
| `BillingSnapshotPersistenceTest` | 10 | 0 | 0 | **PASS** |
| **TỔNG CỘNG** | **70** | **0** | **0** | **100% PASS** |

---

## 4. Bàn giao gói tiếp theo (Handoff sang Q05)

- **Mục tiêu Q05:** Backend Auth (Defect R07 — Hardening Authentication & Identity on Verifier Service).
- **Phạm vi tệp whitelist Q05:**
  - `backend/billing-verifier/src/index.ts`
  - `backend/billing-verifier/src/auth.ts` (mới)
  - `backend/billing-verifier/src/types.ts`
  - `docs/billing/ENTITLEMENT_CONTRACT.md`
  - `backend/billing-verifier/test/http-auth.test.ts` (mới)
  - `REPORT_VIP_BILLING_ROUND2_Q05.md`
- **Yêu cầu cốt lõi:**
  - Xác thực Bearer JWT / Google idToken trên `/restore`, `/verify`, `/ack`.
  - Trích xuất canonical user ID (`sub`) trực tiếp từ token đã xác thực, không tin cậy `ownerAppUserId` từ client payload.
  - Xác thực token RTDN Pub/Sub webhook (`/webhook/play-rtdn`).
  - Trả về mã lỗi HTTP 401/403 cho các truy vấn không có token hoặc sai lệch danh tính.
