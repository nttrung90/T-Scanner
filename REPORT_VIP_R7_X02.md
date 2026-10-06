# Báo cáo Checkpoint VIP Round 7 — Gói X02

## 1. Mục tiêu và phạm vi gói X02
- **Mục tiêu:** Khắc phục lỗi F02 — Xác định sai SKU khi truy vấn linked token trong trường hợp nâng cấp/hạ cấp gói (ví dụ yearly ↔ monthly) và nuốt lỗi upstream của linked token khi restore dẫn đến báo thành công giả (SUCCESS).
- **Phạm vi file:**
  - `backend/billing-verifier/src/types.ts`
  - `backend/billing-verifier/src/googlePlayClient.ts`
  - `backend/billing-verifier/src/verifier.ts`
  - `backend/billing-verifier/test/round7-regression.test.ts`
  - `PROGRESS_VIP_R7_AUTORUN.md`
  - `REPORT_VIP_R7_X02.md`

## 2. Thay đổi chi tiết trong code sản phẩm
- **Trong `types.ts`:**
  - Định nghĩa type `LinkedResolutionOutcome` (với các trạng thái `RESOLVED`, `UNRESOLVED_TRANSIENT`, `UNRESOLVED_PERMANENT`, `SKIPPED`).
  - Mở rộng `VerificationResponse` bao gồm trường tùy chọn `linkedResolution?: LinkedResolutionOutcome`.
- **Trong `googlePlayClient.ts`:**
  - Cập nhật `parseSubscriptionV2`: Cho phép `expectedSubscriptionId` có thể rỗng khi truy vấn linked token mới; khi đó tự động trích xuất `productId` từ `lineItems[0]` của Google Play Subscriptions V2 API và trả về qua trường `lineItemProductId`.
  - Vẫn giữ nguyên kiểm tra nghiêm ngặt khi `expectedSubscriptionId` được chỉ định (bảo toàn phòng thủ chống token/product mismatch).
- **Trong `verifier.ts`:**
  - Cập nhật `resolveLinkedSubscriptionToken`:
    - Truy vấn với SKU chính xác: nếu token đã có trong store thì dùng `existingRecord.productId`, nếu chưa có thì để trống để Subscriptions V2 trả về SKU thực tế từ `lineItems`.
    - Đối chiếu SKU nhận được với danh mục cho phép (`ALLOWED_SUBSCRIPTION_IDS`), từ chối nếu không hợp lệ.
    - Trả về `LinkedResolutionOutcome` rõ ràng thay vì `void` và không nuốt lỗi bằng `catch { warn }`.
    - Bảo toàn giới hạn chu kỳ (cycle check) và độ sâu (depth bound).
  - Cập nhật `restorePurchases`:
    - Tổng hợp kết quả từ `linkedResolution` vào mảng `results`.
    - Nếu linked token gặp lỗi tạm thời (ví dụ 503 upstream), ghi nhận `hasTransientError = true` và đưa kết quả về `PARTIAL` thay vì `SUCCESS`.

## 3. Kết quả kiểm thử trước và sau sửa

### 3.1 Probes mục tiêu
- **B702 (`canceled yearly upgrade must recover authoritative linked monthly receipt`):**
  - Trước: ✖ FAIL (`AssertionError: Linked monthly receipt was queried using yearly SKU and silently discarded`)
  - Sau: ✔ **PASS** (27.33ms)
- **B703 (`unresolved linked upstream failure must not be reported as complete restore SUCCESS`):**
  - Trước: ✖ FAIL (`AssertionError: Unresolved linked receipt was swallowed: status was SUCCESS`)
  - Sau: ✔ **PASS** (1.74ms)
- **B701 (`first-bind linked query must not overwrite a newer authoritative EXPIRED receipt`):**
  - Sau: ✔ **PASS** (4.15ms)
- **B709 (`control same-SKU linked ACTIVE receipt is resolved and owned correctly`):**
  - Sau: ✔ **PASS** (1.36ms)
- **B710 (`control linked receipt already owned by another account is preserved`):**
  - Sau: ✔ **PASS** (1.26ms)
- **B711 (`linked first-bind race must remain protected across two SQLite connections and restart`):**
  - Sau: ✔ **PASS** (929.35ms)

### 3.2 Bộ test Backend tổng thể
- **Lệnh chạy:** `node --experimental-strip-types --test backend/billing-verifier/test/*.test.ts`
- **Kết quả:** 108 tests (105 PASS, 3 FAIL — 3 test còn lại là B704, B705, B706 thuộc phạm vi X03 và X04).
- Toàn bộ các test cũ và probes X01 đều tiếp tục xanh.

## 4. Hợp đồng và Giới hạn
- Linked token khác SKU được xác định danh tính chuẩn xác theo Google Subscriptions V2 API.
- Lỗi upstream từ linked token được truyền ra hợp đồng cấp quyền restore, phản ánh đúng trạng thái `PARTIAL` hoặc lỗi tương ứng.
- Token mới bị hủy (`SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED`) vẫn giữ nguyên trạng thái `REVOKED`, không bị biến thành `ACTIVE` nhờ receipt liên kết.

## 5. Chuyển giao gói tiếp theo
- **Gói kế tiếp:** **X03 — RTDN canceled pending và linked authority (F03)**.
- **Mục tiêu X03:** Giải quyết B704 trong `backend/billing-verifier/src/rtdnHandler.ts` khi nhận RTDN cho token pending bị hủy chưa từng bind vào DB, thực hiện truy vấn Google Play authority để tìm linked token và cập nhật trạng thái linked token hợp lệ.
