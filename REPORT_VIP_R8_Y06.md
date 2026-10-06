# Báo cáo Kiểm chứng VIP Vòng 8 — Gói Y06 (Android Token Identity, Final Authority & Fresh Count — G04 Android)

**Ngày thực hiện:** 01/10/2026  
**Trạng thái:** HOÀN THÀNH (DONE) — Tự động chuyển sang Y07  
**Lỗi giải quyết:** G04 Android (P2: Token identity không đồng bộ giữa local & remote; double-counting failure; cached VIP inflate fresh restore count)

---

## 1. Mục tiêu & Nguyên nhân gốc (Root Cause)

- **Hiện tượng lỗi (Pre-fix):**
  1. Trong `BillingReconciliation.executeRemoteRestore()`:
     - Số lượng thất bại (`failedCount`) trước đây được tính bằng cách cộng trực tiếp `itemFailureCount` (từ `restoreResults` trả về bởi backend) với `deviceUnresolved = totalDeviceCount - deviceSuccessfulPurchases.size`.
     - Khi một receipt (ví dụ: `failed`) không qua được local verification và sau đó cũng bị backend trả về `TRANSIENT_ERROR` hoặc `REJECTED`, token này bị tính 1 lần trong `itemFailureCount` và thêm 1 lần nữa trong `deviceUnresolved`, khiến `failedCount = 2` thay vì `1` (double-counting).
     - Khi một receipt không qua được local verification nhưng backend xác thực thành công dứt điểm (`status: SUCCESS`), `deviceUnresolved` vẫn giữ nguyên giá trị `1` do local verification trước đó thất bại, khiến lỗi cục bộ cũ ghi đè lên kết quả authoritative success của server.
  2. Về số lượng khôi phục mới (`activeCount`):
     - Khi backend trả về kết quả rỗng (`results = emptyList()`), khối lệnh fallback trước đây lấy trực tiếp toàn bộ số lượng entitlements trong cache máy (`committedSnapshot.getActiveEntitlements().size`).
     - Hậu quả: Người dùng có VIP cũ trong cache cục bộ bị tính là vừa được "khôi phục mới" (`count = 1`), hiển thị sai lệch thông báo UI trong khi thực tế không có receipt mới nào được xác thực từ backend.
- **Giải pháp (Fix):**
  1. Phân loại và deduplicate kết quả theo danh tính token (`purchaseToken`):
     - Tập hợp tokens thành công từ local (`locallySucceededTokens`) và remote (`remotelySucceededTokens`).
     - Tập hợp tokens thất bại từ local (`locallyFailedTokens`) và remote (`remotelyFailedTokens`).
     - Áp dụng nguyên tắc ưu tiên thẩm quyền (authority precedence):
       - Authoritative remote success ghi đè thất bại cục bộ trước đó: `locallyFailedTokens - remotelySucceededTokens`.
       - Receipt thất bại ở cả local và remote chỉ tính một lần: `allFailedTokens = remotelyFailedTokens + (locallyFailedTokens - remotelySucceededTokens)`.
       - `failedCount = allFailedTokens.size + playQueryUnresolved`.
  2. Tính toán chính xác số lượng khôi phục mới (`activeCount`):
     - Chỉ những token thực sự thành công từ remote hoặc local trong phiên khôi phục hiện tại (`allSuccessTokens = remotelySucceededTokens + locallySucceededTokens`) mới được tính vào `activeCount`:
       `activeCount = committedSnapshot.getActiveEntitlements().count { allSuccessTokens.contains(it.purchaseToken) }`.
     - Khi backend snapshot rỗng và không có token mới nào thành công, `activeCount = 0`, không làm sai lệch số liệu UI.

---

## 2. File thay đổi

- `app/src/main/java/com/tscanner/app/utils/billing/BillingReconciliation.kt`:
  - Cập nhật thuật toán tính `failedCount` và `activeCount` dựa trên token identity sets (`allFailedTokens`, `allSuccessTokens`).

---

## 3. Kết quả kiểm tra Regression & Acceptance

| Test Case | Mô tả kiểm tra | Trước sửa | Sau sửa | Ghi chú |
|---|---|---|---|---|
| `A804` | Cùng token fail ở local & remote chỉ tính 1 failure | **FAIL** (failedCount = 2, total = 3) | **PASS** | `failedCount = 1`, `totalCount = 2` |
| `A805` | Remote authoritative success ghi đè local failure cũ | **FAIL** (failedCount = 1, stale local) | **PASS** | `failedCount = 0`, `count = 1`, `total = 1` |
| `A812` | Cached VIP không bị tính là fresh restore khi remote rỗng | **FAIL** (count = 1 từ cached) | **PASS** | `count = 0`, bảo lưu quyền cached |
| `A809` | Control: Full success một receipt duy nhất | **PASS** | **PASS** | `count = 1`, `failed = 0`, `total = 1` |
| Billing suite (`Billing*`) | 146 unit tests | 146 PASS | **146 PASS** | 100% PASS không regression |

---

## 4. Bước kế tiếp

Tự động chuyển sang gói **Y07** (Purchase Operation Owner & Readiness Trước Await & Launch — G07).
