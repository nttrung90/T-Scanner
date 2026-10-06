# Báo cáo Kiểm chứng VIP Vòng 8 — Gói Y05 (Typed Pending/Unresolved/No-Active Trong Android — G05)

**Ngày thực hiện:** 01/10/2026  
**Trạng thái:** HOÀN THÀNH (DONE) — Tự động chuyển sang Y06  
**Lỗi giải quyết:** G05 (P2: Pending approval và unresolved restore bị ép thành NoActivePurchases hoặc xóa metadata thất bại)

---

## 1. Mục tiêu & Nguyên nhân gốc (Root Cause)

- **Hiện tượng lỗi (Pre-fix):**
  1. Trong `BillingReconciliation.executeRemoteRestore()`:
     - Khi `isVip` là `true` (snapshot có VIP), biến `itemFailureCount` chỉ đếm `it.status == "TRANSIENT_ERROR" || it.status == "REJECTED"`, bỏ qua `PENDING`. Do đó, khi backend trả về một giao dịch active và một giao dịch pending, `failedCount` bị tính bằng 0 và báo thành công trọn vẹn (`failedCount = 0`), vi phạm invariant phân biệt incomplete/pending.
     - Khi `isVip` là `false` (snapshot không có VIP đang kích hoạt), nhánh `else` gọi thẳng `onResult(ReconciliationResult.NoActivePurchases)`. Bất kể kết quả trả về là `PENDING` (chờ thanh toán), `TRANSIENT_ERROR` (lỗi mạng/server tạm thời), hay thiết bị có purchase pending tại local, hệ thống đều đánh đồng với `NoActivePurchases`. Người dùng bị mất thông tin hướng dẫn và trạng thái pending/retry bị xóa bỏ.
  2. Tại luồng gửi yêu cầu khôi phục lên backend:
     - `RestoreRequest` trước đây bị truyền cứng `emptyList()` (`RestoreRequest(targetOwnerId, emptyList())`), khiến các purchase pending trên máy không được chuyển thành `PurchaseCandidate` gửi lên backend để nhận diện trạng thái chờ duyệt.
- **Giải pháp (Fix):**
  1. Mở rộng `ReconciliationResult`: Thêm kiểu kết quả phân loại rõ ràng:
     - `data class PendingApproval(val message: String) : ReconciliationResult()`
  2. Mở rộng `RestoreResult`: Bổ sung kết quả typed:
     - `data class NoActivePurchases(val message: String? = null) : RestoreResult()`
  3. Cập nhật `BillingReconciliation.kt`:
     - Khi `isVip` là `true`: Thêm `it.status == "PENDING"` vào `itemFailureCount` để đảm bảo kết quả khôi phục một phần (partial) phản ánh chính xác số lượng giao dịch chưa hoàn tất (`failedCount > 0`).
     - Khi `isVip` là `false`:
       - Nếu có giao dịch pending (từ `restoreResults`, `devicePurchases`, hoặc thông báo pending): Trả về `ReconciliationResult.PendingApproval`.
       - Nếu có lỗi mạng / transient error: Trả về `ReconciliationResult.NetworkError` với mã lỗi và thông điệp retryable.
       - Chỉ khi toàn bộ giao dịch thực sự hết hạn, bị thu hồi (`REVOKED`), hoặc không tồn tại receipt nào: Trả về `ReconciliationResult.NoActivePurchases`.
     - Chuyển `devicePurchases` vào `executeRemoteRestore()` và map sang `PurchaseCandidate` trong `RestoreRequest` để các pending purchase từ local tiếp cận được backend verifier.
  4. Cập nhật `BillingManager.kt`:
     - Xử lý nhánh `ReconciliationResult.PendingApproval` trong `restorePurchases` (thông báo người dùng giao dịch đang chờ xử lý mà không cấp VIP ảo) và `syncPurchasesSilently` (kết thúc an toàn với `false`).

---

## 2. File thay đổi

- `app/src/main/java/com/tscanner/app/utils/billing/PurchaseVerifier.kt`: Bổ sung `RestoreResult.NoActivePurchases`.
- `app/src/main/java/com/tscanner/app/utils/billing/BillingReconciliation.kt`: Bổ sung `ReconciliationResult.PendingApproval`, tính `PENDING` vào failure/incomplete count, phân biệt `PendingApproval`/`NetworkError`/`NoActivePurchases`, truyền candidate list vào `RestoreRequest`.
- `app/src/main/java/com/tscanner/app/utils/BillingManager.kt`: Xử lý nhánh `PendingApproval` trong `restorePurchases` và `syncPurchasesSilently`.

---

## 3. Kết quả kiểm tra Regression & Acceptance

| Test Case | Mô tả kiểm tra | Trước sửa | Sau sửa | Ghi chú |
|---|---|---|---|---|
| `A801` | Active + Pending không báo full restore thành công | **FAIL** (failedCount = 0) | **PASS** | `result.count = 1`, `result.failedCount > 0` |
| `A802` | All-pending không bị ép thành NoActivePurchases | **FAIL** (is NoActivePurchases) | **PASS** | Trả `PendingApproval`, không cấp VIP ảo |
| `A803` | Lỗi remote không active VIP không thành NoActivePurchases | **FAIL** (is NoActivePurchases) | **PASS** | Trả `NetworkError` giữ trạng thái retry |
| `A806` | Local pending được gửi tới backend và kết thúc đúng pending state | **FAIL** (is NoActivePurchases) | **PASS** | `RestoreRequest` nhận candidates, trả `PendingApproval` |
| `A810` | Revocation/Expired hợp lệ vẫn về NoActivePurchases | **PASS** | **PASS** | Invariant kiểm soát thu hồi giữ vững |
| Billing suite (`Billing*`) | 146 unit tests | 146 PASS | **146 PASS** | Không regression trong toàn bộ module billing |

---

## 4. Bước kế tiếp

Tự động chuyển sang gói **Y06** (Android Token Identity, Final Authority & Fresh Count — G04 Android).
