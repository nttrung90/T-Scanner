# Báo Cáo Triển Khai VIP Vòng 3 — Gói M06: Nối Verifier Android Vào Bootstrap & Session (F02)

**Thời điểm:** 26/09/2026  
**Mục tiêu:** Loại bỏ phát hiện F02, kết nối `PlayPurchaseVerifier` với vòng đời ứng dụng Android (`TScannerApplication.kt`, `AppAuthManager.kt`, `BillingManager.kt`), kiểm tra nghiêm ngặt URL HTTPS (loại bỏ `http://`), ràng buộc token với thế hệ phiên xác thực (`sessionGeneration`), và tắt tự động chuyển hướng để ngăn chặn rò rỉ Bearer token sang máy chủ khác.

---

## 1. File Thay Đổi & Tạo Mới

- `app/build.gradle`:
  - Kích hoạt `buildConfig = true` trong `buildFeatures`.
  - Khai báo `buildConfigField "String", "BILLING_VERIFIER_URL", ...` cho phép cấu hình URL từ build property.
- `app/src/main/java/com/tscanner/app/utils/billing/PlayPurchaseVerifier.kt`:
  - Kiểm tra `isConfigured()` nghiêm ngặt theo chuẩn HTTPS (`startsWith("https://")` và valid URI scheme), từ chối `http://` như đã chứng minh trong probe.
  - Bổ sung `sessionGenerationProvider` và `ownerProvider`.
  - Trong `verifyViaRemoteBackend`: đối chiếu `request.ownerAppUserId` với `currentOwner`, bắt buộc có token phiên hợp lệ (nếu token rỗng thì trả về `Rejected(INVALID_SIGNATURE_OR_TOKEN)` yêu cầu đăng nhập lại), và kiểm tra `sessionGeneration` trước/sau khi lấy token để ngăn chặn race condition A -> B -> A.
  - Thiết lập `conn.instanceFollowRedirects = false` trong transport HTTP mặc định để bảo mật Bearer authorization header.
- `app/src/main/java/com/tscanner/app/utils/AppAuthManager.kt`:
  - Bổ sung các hàm cung cấp phiên: `getSessionToken()` và `getSessionOwnerId()`.
- `app/src/main/java/com/tscanner/app/TScannerApplication.kt`:
  - Khởi tạo `PlayPurchaseVerifier.configure(...)` ngay trong `onCreate()` sau khi khởi tạo Auth và trước khi `BillingManager.getInstance(this)` chạy, kết nối trực tiếp với session token và session generation của `AppAuthManager`.
- `app/src/test/java/com/tscanner/app/PlayPurchaseVerifierHttpTest.kt`:
  - Bổ sung các bài kiểm thử:
    1. `testSessionBoundToken_missingToken_rejectsWithReauth`: thiếu token thì từ chối và yêu cầu đăng nhập lại.
    2. `testSessionBoundToken_ownerMismatch_rejectsWithConflict`: tài khoản phiên khác chủ sở hữu thì từ chối `OWNERSHIP_CONFLICT`.
    3. `testSessionBoundToken_sessionChangedDuringAcquisition_returnsTransientError`: phiên thay đổi trong khi chờ lấy token thì huỷ an toàn bằng `TransientError`.
    4. `testHttpsStrict_httpAndMalformedUrls_notConfigured`: từ chối URL `http://`, `ftp://`, rỗng; chỉ chấp nhận `https://`.
- `REPORT_VIP_R3_M06.md`: Báo cáo nội bộ gói M06.

---

## 2. Bằng Chứng Red -> Green & Thực Thi Thực Tế

### 2.1. Probe Đỏ M00 Chuyển Xanh
- Test: `com.tscanner.app.VipRound3RegressionTest.httpEndpointMustNotBeReady`
  - Trước (M00): **FAIL** (URL `http://audit.invalid` bị coi là `isConfigured() == true`)
  - Sau (M06): **PASS** (Bắt buộc HTTPS, URL `http://` bị từ chối)

### 2.2. Kiểm Tra Focused Http Suite
- Lệnh: `.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.PlayPurchaseVerifierHttpTest --tests com.tscanner.app.BillingReadinessTest --offline --console=plain`
- Kết quả: **BUILD SUCCESSFUL**, toàn bộ các test HTTP và Readiness đều **PASS**.

---

## 3. Handoff Cho Gói Sau (M07)

- Khung kết nối verifier Android và session token đã sẵn sàng và được bảo vệ nghiêm ngặt.
- Gói M07 tiếp nhận: Sửa F05 và F06 Android:
  - File: `PlayPurchaseVerifier.kt`, `PurchaseVerifier.kt`, `BillingManager.kt`.
  - Xử lý probe `malformedStateMustNotBecomeActive`: kiểm tra mã phản hồi HTTP và schema phản hồi nghiêm ngặt; trạng thái `UNKNOWN` và thiếu `expiryTimeMillis` tuyệt đối không được tự ý cấp VIP lifetime.
  - Xử lý probe `rejectionMustPersistAuthoritativeRevocation`: mang theo snapshot/tombstone trong `VerificationResult.Rejected` và lưu vào `BillingEntitlementStore` để thu hồi quyền trong cache cục bộ khi Google Play báo `PURCHASE_REVOKED`.
- Chuyển tiếp tự động sang M07.
