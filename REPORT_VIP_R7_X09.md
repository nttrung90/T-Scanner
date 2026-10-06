# Báo cáo VIP R7 — Gói X09: Account backend refresh khi Play query lỗi (F05)

## 1. Trạng thái và Mục tiêu
- **Mục tiêu:** Sửa lỗi F05 — khi truy vấn Google Play Client trả về lỗi (lỗi mạng, dịch vụ Play lỗi), hệ thống không được chặn quy trình độc lập làm mới quyền từ backend xác thực của người dùng đang đăng nhập (`AppAuthManager.getCurrentUser()`).
- **Trạng thái:** **DONE** (probe A01 PASS, 17/18 Android probes PASS).

## 2. File thay đổi
- `app/src/main/java/com/tscanner/app/utils/billing/BillingReconciliation.kt`:
  - Trong `evaluateAndProcess`: Khi `subsResult` hoặc `inAppResult` trả về lỗi (khác `OK`), nếu có `targetOwnerId` và `verifier != null`, không vội ngắt với `NetworkError`. Thay vào đó, kích hoạt `executeRemoteRestore` để thực hiện authoritative backend refresh độc lập cho tài khoản người dùng hiện tại, truyền mã lỗi truy vấn Google Play (`playQueryErrorCode = failureCode`).
  - Trong `executeRemoteRestore`: Thêm tham số `playQueryErrorCode: Int? = null`. Nếu backend restore thành công, áp dụng authoritative snapshot của server vào `BillingEntitlementStore` và profile người dùng (kể cả khi trạng thái mới trên server là REVOKED / EXPIRED). Nếu backend trả về `TransientError` và không có thiết bị local nào thành công, trả về `NetworkError(playQueryErrorCode ?: 503)`.

## 3. Kết quả kiểm thử trước và sau
- **Trước X09:**
  - Probe A01 (`A01PlayQueryErrorMustStillRefreshAuthoritativeAccountReceipts`): FAIL (A Play query failure prevented independent backend account refresh).
- **Sau X09:**
  - Probe A01: **PASS**
  - Probe A02: **PASS**
  - Toàn bộ suite `VipRound7RegressionTest`: **17/18 tests PASS** (duy nhất A03 còn lại cho gói X10).

## 4. Contract đã áp dụng
- Google Play query failure không vô hiệu hóa quyền hạn cấp từ server authoritative backend.
- Đảm bảo token và snapshot mới nhất từ backend được commit và chiếu vào `UserProfile` ngay cả khi Play Store client trên máy gặp lỗi.

## 5. Bước kế tiếp
- Tự động chuyển sang gói **X10**: Xử lý `A03` — Partial/pending/unresolved chuyển tiếp chính xác đến UI và thông báo trong `BillingManager.kt`.
