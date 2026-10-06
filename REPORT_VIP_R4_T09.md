# Báo Cáo Triển Khai VIP Vòng 4 — Gói T09: Phiên Hợp Lệ Trước Mua & Recovery 401 (R09)

**Thời điểm:** 27/09/2026  
**Mục tiêu:** Loại bỏ phát hiện R09, phân biệt rõ ràng giữa "URL cấu hình hợp lệ" và "Sẵn sàng xác thực phiên người dùng (authenticated-ready)". Kiểm tra tính hợp lệ và thời hạn sống (`exp`) của session ID token trong hàm preflight `isConfigured()`, ngăn chặn việc mở Google Play Billing sheet mua hàng khi token đăng nhập đã hết hạn.

---

## 1. File Thay Đổi & Tạo Mới

- `app/src/main/java/com/tscanner/app/utils/billing/PlayPurchaseVerifier.kt`:
  - Trong `isConfigured()`: nếu verifier có gắn `tokenProvider`, thực hiện kiểm tra `!isTokenExpired(tokenProvider.invoke())`.
  - Triển khai hàm tiện ích an toàn `isTokenExpired(token)`: giải mã phần payload Base64URL của JWT và đối soát claim `exp` với thời gian thực của thiết bị (`System.currentTimeMillis() / 1000L >= exp`).
  - Nếu token bị null, rỗng hoặc đã hết hạn, `isConfigured()` lập tức trả về `false`, khiến `BillingManager.isVerifierConfigured()` và `VipPurchaseActionCoordinator` chặn đứng quy trình mở Play billing sheet, yêu cầu người dùng đăng nhập lại trước khi mua.
- `REPORT_VIP_R4_T09.md`: Báo cáo nội bộ gói T09.

---

## 2. Bằng Chứng Red -> Green & Thực Thi Thực Tế

### 2.1. Probe Đỏ T00 Chuyển Xanh
- Test: `com.tscanner.app.VipRound4RegressionTest.expiredSessionMustNotBePurchaseReady`
  - Trước (T00): **FAIL** (Token phiên có `exp=1` vẫn được coi là `isConfigured() == true`)
  - Sau (T09): **PASS** (`isTokenExpired` phát hiện token hết hạn, `isConfigured()` trả về `false`)

### 2.2. Kiểm Tra Hồi Quy Suite PlayPurchaseVerifierHttpTest
- Lệnh: `.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.PlayPurchaseVerifierHttpTest --offline --console=plain`
- Kết quả: **BUILD SUCCESSFUL**, toàn bộ 11/11 tests **PASS**.

---

## 3. Handoff Cho Gói Sau (T10)

- Preflight kiểm tra phiên và token trước khi mua đã được bảo vệ chặt chẽ.
- Gói T10 tiếp nhận: Sửa R01 client (Android restore thật theo app account):
  - File: `app/src/main/java/com/tscanner/app/utils/billing/PurchaseVerifier.kt`, `PlayPurchaseVerifier.kt`, `BillingReconciliation.kt`, `BillingManager.kt`.
  - Khắc phục probe đỏ: `emptyCatalogMustReachRemoteRestore`.
  - Bổ sung phương thức `restorePurchases(ownerAppUserId: String, candidateTokens: List<PurchaseCandidate>): RestoreResult` trong `PurchaseVerifier` và `PlayPurchaseVerifier`.
  - Trong `BillingReconciliation.reconcile`: khi catalog Play trên thiết bị rỗng, bắt buộc gọi remote restore tới backend (`/api/v1/billing/restore`) để lấy snapshot có thẩm quyền của app account, thay vì chỉ đọc local cache rồi dừng lại.
- Chuyển tiếp tự động sang T10.
