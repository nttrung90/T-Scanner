# Báo Cáo VIP Vòng 8 — Gói Y11: Xử Lý Voided Full Refund Lifetime Bằng RTDN (G10)

**Mã gói:** Y11  
**Lỗi giải quyết:** G10 (B812, B813)  
**Thời gian thực hiện:** 01/10/2026 16:15 – 16:25  
**Môi trường:** `E:\DU AN AI\T-Scanner` | Node v22.12.0 | `backend/billing-verifier`

---

## 1. Mục tiêu & Scope
- **Mục tiêu:** Xử lý PubSub notification chứa envelope `voidedPurchaseNotification` từ Google Play RTDN cho các giao dịch hoàn tiền đầy đủ (full refund) của sản phẩm trọn đời (lifetime VIP) hoặc in-app products. Thu hồi ngay lập tức quyền lợi VIP (`REVOKED`), cập nhật snapshot version và lưu thời gian sự kiện vào durable SQLite store, tránh để entitlement trọn đời tồn tại vĩnh viễn sau khi đã hoàn tiền.
- **Phạm vi file:**
  - `backend/billing-verifier/src/rtdnHandler.ts`: Cập nhật schema `DeveloperNotificationPayload` (thêm `voidedPurchaseNotification`, hỗ trợ `eventTimeMillis` kiểu `number | string`), bổ sung khối xử lý chuyên biệt cho voided notification trong `processDeveloperNotification`.
  - `backend/billing-verifier/test/round8-regression.test.ts`: Bổ sung regression tests và các biến thể bao phủ (idempotency, out-of-order rejection, unknown token safety).
- **Không thay đổi:** Không thay đổi SKU, pricing, không phá vỡ hợp đồng của `subscriptionNotification` hay `oneTimeProductNotification`.

---

## 2. Hiện trạng trước khi sửa & Regressions (B812, B813)
- Trước khi sửa:
  - `rtdnHandler.ts` chỉ kiểm tra `payload.subscriptionNotification` và `payload.oneTimeProductNotification`. Khi Google PubSub gửi envelope `voidedPurchaseNotification`, hàm trả về `{ status: 'PROCESSED', message: 'Non-billing notification noted' }`.
  - Entitlement lifetime trong SQLite store vẫn giữ trạng thái `VERIFIED_ACTIVE` vĩnh viễn (do lifetime không có thời hạn hết hạn `expiryTimeMillis`).
  - Test B812 thất bại:
    ```
    AssertionError [ERR_ASSERTION]: Full paid lifetime refund was ignored as PROCESSED/Non-billing notification noted; stored lifetime remains active without expiry
    + actual: 'VERIFIED_ACTIVE'
    - expected: 'REVOKED'
    ```

---

## 3. Chi tiết các thay đổi trong `rtdnHandler.ts`
1. **Mở rộng `DeveloperNotificationPayload`:**
   - Thêm trường `voidedPurchaseNotification`:
     ```typescript
     voidedPurchaseNotification?: {
       purchaseToken: string;
       orderId?: string;
       productType?: number; // 1: subs, 2: inapp
       refundType?: number;  // 1: full refund, 2: partial refund
     };
     ```
   - Cho phép `eventTimeMillis` có kiểu `number | string` tương thích với các envelope PubSub thực tế từ Google Cloud PubSub.
2. **Khối xử lý `voidedPurchaseNotification` trong `processDeveloperNotification`:**
   - **Trích xuất thông tin:** Trích xuất `purchaseToken`, `orderId`, `productType`, `refundType`.
   - **Bảo vệ Stale / Thứ tự sự kiện:** So khớp `eventTime` với `this.lastProcessedEventTimes` và `last_event_time_millis` trong store; nếu sự kiện cũ hoặc trùng lặp, trả về `SKIPPED_STALE`.
   - **Xác thực token tồn tại:** Tra cứu token trong store bằng `store.getRecordByToken(purchaseToken)`; nếu chưa có record, ghi log cảnh báo và trả về `TOKEN_UNKNOWN` an toàn.
   - **Kiểm tra quyền hạn (Authority Check):** Đối với full refund (`refundType === 1` hoặc authority báo `purchaseState === 1`), xác nhận thu hồi. Nếu Google Play API ném lỗi tạm thời (503 / 429), trả về `ERROR` để PubSub retry.
   - **Thu hồi và cập nhật nguyên tử:** Cập nhật trạng thái entitlement thành `REVOKED`, tăng snapshot version đơn điệu, gọi `store.bindOrUpdate` với CAS guard và `eventTimeMillis`.
   - Cập nhật watermark in-memory `lastProcessedEventTimes`.

---

## 4. Kết quả kiểm thử
Lệnh chạy kiểm thử:
```pwsh
node --experimental-strip-types --test backend/billing-verifier/test/round8-regression.test.ts
node --experimental-strip-types --test docs/vip-round8-20261001/backend-round8-probes.test.ts
node --experimental-strip-types --test backend/billing-verifier/test/*.test.ts
```

Kết quả:
- **`round8-regression.test.ts`:** 20 / 20 tests **PASS** (100%), bao gồm:
  - `B812 current Google full voided-purchase refund envelope must revoke verified lifetime VIP`: **PASS**
  - `B812-variant duplicate identical voided RTDN is idempotent and safely skipped`: **PASS**
  - `B812-variant stale out-of-order voided RTDN is blocked by store event time`: **PASS**
  - `B812-variant unknown voided token returns TOKEN_UNKNOWN safely`: **PASS**
  - `B813 control ordinary account restore observes the same full lifetime refund and revokes it`: **PASS**
- **`backend-round8-probes.test.ts` (Harness gốc):** 13 / 13 probes **PASS** (100%).
- **Toàn bộ backend test suite:** 128 / 128 tests **PASS** (100%), 0 failures.

---

## 5. Đánh giá tiêu chí chấp nhận (Acceptance)
- [x] B812 và B813 đều PASS.
- [x] Verified lifetime VIP bị thu hồi ngay lập tức (`REVOKED`) sau RTDN full refund mà không cần chờ user kích hoạt restore.
- [x] Sự kiện trùng lặp trả về `SKIPPED_STALE` một cách idempotent, không làm thay đổi trạng thái hoặc phiên bản.
- [x] Sự kiện cũ đến muộn (out-of-order) bị chặn triệt để.
- [x] Token chưa xác định trả về `TOKEN_UNKNOWN` an toàn không gây crash server.
- [x] Không làm ảnh hưởng tới các luồng RTDN subscription hay one-time khác.

Tự động chuyển tiếp sang **Y12 (Kiểm chứng host xuyên tầng và ma trận đầy đủ)**.
