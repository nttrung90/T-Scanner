# Báo Cáo Nghiệm Thu Gói B03 — Chốt Contract Entitlement, Ownership và Backend (F02–F05)

**Thời gian:** 26/09/2026  
**Dự án:** T-Scanner (Android, `:app`)  
**Mục tiêu gói B03:** Thiết lập hợp đồng bất biến về quyền sở hữu (ownership), vòng đời đăng ký (subscription lifecycle), tính lũy đẳng (idempotent snapshot), ngăn chặn cộng dồn hạn vô hạn (F02), ngăn xóa VIP vô cớ khi restore rỗng/lỗi mạng (F03), và chuẩn bị seam cho Backend Verifier (F04, F05).

---

## 1. Danh Sách File Tạo Mới / Sửa Đổi (Tuân thủ nghiêm ngặt Whitelist B03)

| File | Loại | Rationale kỹ thuật |
|---|---|---|
| `docs/billing/ENTITLEMENT_CONTRACT.md` | Mới | Tài liệu hợp đồng toàn diện giữa Android Client và Backend Verifier: REST API (`/verify`, `/restore`), cấu trúc dữ liệu, các trạng thái subscription, ràng buộc sở hữu tài khoản, và kế hoạch di chuyển dữ liệu cũ. |
| `app/src/main/java/com/tscanner/app/utils/billing/BillingEntitlement.kt` | Mới | Domain models bất biến: `EntitlementSource`, `EntitlementState` (9 trạng thái vòng đời), `BillingEntitlement` (hạn tuyệt đối, lifetime không có ngày hết hạn), `UserEntitlementSnapshot` (hợp nhất snapshot đơn điệu, không cộng dồn thời hạn, chặn xung đột tài khoản sở hữu). |
| `app/src/main/java/com/tscanner/app/utils/billing/PurchaseVerifier.kt` | Mới | Seam `PurchaseVerifier`, model `VerificationRequest`, phân loại kết quả có kiểu `VerificationResult` (`Success`, `Pending`, `Rejected`, `TransientError`, `MissingBackendGate`), và lớp cơ sở `NoOpLocalPurchaseVerifier` (chặn đứng việc giả mạo thành công khi chưa có backend). |
| `app/src/test/java/com/tscanner/app/BillingEntitlementContractTest.kt` | Mới | Bộ 13 unit tests kiểm chứng toàn diện mọi ràng buộc của hợp đồng entitlement & ownership. |
| `REPORT_VIP_BILLING_B03.md` | Mới | Báo cáo nghiệm thu và bàn giao gói B03. |

*Lưu ý:* Đúng theo yêu cầu gói B03, mã nguồn persistence của `AppAuthManager` chưa bị sửa đổi trong gói này (sẽ được thực hiện an toàn ở gói B05).

---

## 2. Các Invariants & Ràng Buộc Hợp Đồng Đã Đóng Băng

1. **Biểu Diễn Quyền Lifetime (F02 Fix Foundation):**
   - Gói Lifetime (`tscanner_vip_lifetime`) có `expiryTimeMillis = null` (không có ngày hết hạn).
   - Quyền Lifetime có hiệu lực chừng nào `state == VERIFIED_ACTIVE`. Không bao giờ cộng cố định +10 năm vào timestamp.
2. **Thời Hạn Tuyệt Đối Của Subscription:**
   - Thời hạn `expiryTimeMillis` là timestamp mốc tuyệt đối do Google Play Developer API trả về.
   - Khi áp dụng lại cùng một receipt (replay / restore), thời hạn không bao giờ bị tăng lũy kế (idempotency tuyệt đối).
3. **Phân Biệt Trạng Thái Vòng Đời Chi Tiết:**
   - `VERIFIED_ACTIVE`: Có hiệu lực.
   - `IN_GRACE_PERIOD`: Vẫn cho dùng VIP tạm thời nhưng cảnh báo thẻ lỗi.
   - `CANCELED_ACTIVE`: Đã hủy tự động gia hạn nhưng kỳ thanh toán hiện tại chưa hết hạn; vẫn cho dùng VIP đến đúng `expiryTimeMillis`.
   - `PENDING_PAYMENT`, `ON_HOLD`, `PAUSED`, `EXPIRED`, `REVOKED`, `UNVERIFIED_CLIENT`: Tuyệt đối không cấp quyền VIP.
4. **Ràng Buộc Sở Hữu (Ownership Conflict Guard - F05 Fix Foundation):**
   - Khi sáp nhập snapshot (`mergeNewerSnapshot`), nếu `ownerAppUserId` của snapshot đến khác với snapshot hiện tại (không rỗng), hệ thống quăng ngoại lệ `IllegalArgumentException("Ownership conflict...")`. Receipt của User A không thể bị User B chiếm đoạt.
5. **Đa Quyền Đồng Thời (Multi-Entitlement Coexistence):**
   - Người dùng có thể sở hữu đồng thời nhiều gói (ví dụ: đang có gói tháng thì mua thêm gói Lifetime). Snapshot lưu trữ độc lập từng entitlement. Nếu gói Lifetime bị thu hồi (`REVOKED`), hệ thống tự động suy thoái về gói tháng đang còn hạn mà không xóa mất thông tin.
6. **Không Giả Lập Thành Công (F04 Hard Gate):**
   - `NoOpLocalPurchaseVerifier` trả về `VerificationResult.MissingBackendGate`. Không bao giờ tự tiện cấp `VERIFIED_ACTIVE` trên máy khách khi chưa có phản hồi từ backend có thẩm quyền.

---

## 3. Bằng Chứng Kiểm Tra & Nghiệm Thu (Evidence)

Thư mục lưu trữ: `build/vip-billing-b03/`

### 3.1. Unit Test Suite Hợp Đồng (`BillingEntitlementContractTest`)
Lệnh chạy:
```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.tscanner.app.BillingEntitlementContractTest"
```
Kết quả: **13/13 PASS (100%)**
1. `testLifetimeEntitlement_hasNoExpiry_remainsActiveIndefinitely` — PASS
2. `testSubscriptionEntitlement_activeBeforeExpiry_expiresAfterExpiry` — PASS
3. `testGracePeriodEntitlement_remainsActiveDuringGracePeriod` — PASS
4. `testAccountHoldEntitlement_isNotActive` — PASS
5. `testPendingPaymentEntitlement_isNotActive` — PASS
6. `testCanceledAutoRenewEntitlement_remainsActiveUntilExpiry` — PASS
7. `testRevokedEntitlement_isNeverActive` — PASS
8. `testSnapshotMerge_idempotent_neverAccumulatesExpiry` — PASS
9. `testSnapshotMerge_olderVersionCannotOverwriteNewerVersion` — PASS
10. `testOwnershipConflict_cannotMergeDifferentOwner` — PASS
11. `testMultiEntitlement_prefersHighestActiveTier` — PASS
12. `testUnknownLegacyLocalVip_doesNotBecomeVerified` — PASS
13. `testNoOpLocalPurchaseVerifier_returnsMissingBackendGate_neverFakesVerified` — PASS

### 3.2. Kiểm Tra Hồi Quy Tập Trung
Lệnh chạy:
```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.tscanner.app.BillingEntitlementContractTest" --tests "com.tscanner.app.BillingManagerTest" --tests "com.tscanner.app.VipPurchaseActionCoordinatorTest"
```
Kết quả: **38/38 PASS (0 failures, 0 errors)**:
- `BillingEntitlementContractTest`: 13/13 PASS
- `BillingManagerTest`: 14/14 PASS
- `VipPurchaseActionCoordinatorTest`: 11/11 PASS

### 3.3. Kiểm Tra Đóng Gói Thực Tế (`:app:assembleDebug`)
- Lệnh: `.\gradlew.bat :app:assembleDebug`
- Kết quả: **BUILD SUCCESSFUL in 9s** (39 actionable tasks: 4 executed, 35 up-to-date).

---

## 4. Quyết Định Kiến Trúc Backend Cho B04a / B04b

1. **Hiện trạng kho mã nguồn:**
   - Repo hiện tại chỉ chứa module Android `:app`. Chưa có backend nào được tạo.
2. **Quyết định cho gói B04a:**
   - Thư mục backend độc lập: `backend/billing-verifier/` tại thư mục gốc repository.
   - Công nghệ: Node.js 20+ với TypeScript và thư viện chính thức `googleapis` (`google.androidpublisher('v3')`).
   - Cung cấp:
     - Endpoint `POST /api/v1/billing/verify` và `POST /api/v1/billing/restore`.
     - Unit test độc lập cho backend giả lập Google Play API boundary, kiểm tra trọn vẹn policy ownership, transaction uniqueness, và package verification.
   - Thông tin còn thiếu / Cổng môi trường thật (Integration Gate): Google Service Account Key (JSON) và App Package credentials của Play Console thật cần được giữ ở biến môi trường khi triển khai thực tế.
