# Báo Cáo Triển Khai VIP Vòng 4 — Gói T06: Android Response Binding & Schema (R06)

**Thời điểm:** 27/09/2026  
**Mục tiêu:** Loại bỏ phát hiện R06, ràng buộc kiểm tra toàn diện và nghiêm ngặt giữa dữ liệu phản hồi của backend verifier với yêu cầu xác thực (`VerificationRequest`), cấm chấp nhận phản hồi mang purchaseToken, productId, productType hoặc ownerAppUserId khác với request, bắt buộc `snapshotVersion > 0` và `purchaseTimeMillis > 0`.

---

## 1. File Thay Đổi & Tạo Mới

- `app/src/main/java/com/tscanner/app/utils/billing/PlayPurchaseVerifier.kt`:
  - Trong `parseEntitlementStrict`:
    1. Kiểm tra đối soát Token: bắt buộc `responseToken == request.purchaseToken`. Nếu token trả về khác token yêu cầu, từ chối và ghi log cảnh báo.
    2. Kiểm tra đối soát Owner: bắt buộc `responseOwner == request.ownerAppUserId` (nếu request có chỉ định owner).
    3. Kiểm tra đối soát Product & ProductType: bắt buộc `responseProduct == request.productId` và `responseType == request.productType`.
    4. Kiểm tra Snapshot Version: bắt buộc `version > 0L`.
    5. Kiểm tra Timestamp: bắt buộc `purchaseTimeMillis > 0L`.
- `REPORT_VIP_R4_T06.md`: Báo cáo nội bộ gói T06.

---

## 2. Bằng Chứng Red -> Green & Thực Thi Thực Tế

### 2.1. Probe Đỏ T00 Chuyển Xanh
- Test: `com.tscanner.app.VipRound4RegressionTest.responseDifferentTokenMustBeRejected`
  - Trước (T00): **FAIL** (Response mang `another-token` khác với request `receipt` vẫn được parse thành `Success`)
  - Sau (T06): **PASS** (Strict binding phát hiện sai token, trả về `TransientError`, không cấp `Success`)

### 2.2. Kiểm Tra Focused Http Verifier Suite
- Lệnh: `.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.PlayPurchaseVerifierHttpTest --offline --console=plain`
- Kết quả: **BUILD SUCCESSFUL**, 11/11 tests **PASS**.

---

## 3. Handoff Cho Gói Sau (T07)

- Dữ liệu phản hồi đã được ràng buộc toàn diện với yêu cầu xác thực.
- Gói T07 tiếp nhận: Sửa R08 (Migration identity không vượt equal-version guard):
  - File: `app/src/main/java/com/tscanner/app/utils/billing/BillingEntitlement.kt`, `BillingEntitlementStore.kt`.
  - Khắc phục probe đỏ: `equalVersionDifferentIdMustNotResurrectRevokedToken`.
  - Trong `mergeNewerSnapshot`: chuẩn hóa canonical identity trước khi merge; khi `incomingItem.snapshotVersion == existingItem.snapshotVersion` nhưng trạng thái khác nhau (ví dụ một bên `REVOKED`, một bên `ACTIVE`), tuyệt đối không cho phép đổi ID để lách qua và thay thế bản ghi; bắt buộc giữ an toàn fail-closed hoặc ném `IllegalArgumentException`.
- Chuyển tiếp tự động sang T07.
