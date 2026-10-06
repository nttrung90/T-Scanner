# Báo Cáo Triển Khai VIP Vòng 4 — Gói T02: Đường Query V2 & State/Product Contract (R03)

**Thời điểm:** 27/09/2026  
**Mục tiêu:** Loại bỏ phát hiện R03, chuyển đổi URL gọi Google Play Developer API sang endpoint `subscriptionsv2/tokens/{token}` chính thức, cấm fallback `lineItems[0]` khi sai sản phẩm, từ chối các trạng thái `UNKNOWN`/`UNSPECIFIED`, và ánh xạ chính xác trạng thái `PAUSED` không trở thành gói trả phí active.

---

## 1. File Thay Đổi & Tạo Mới

- `backend/billing-verifier/src/googlePlayClient.ts`:
  - Đổi URL truy vấn `getSubscription` sang `purchases/subscriptionsv2/tokens/${encodeURIComponent(token)}`.
  - Trong `parseSubscriptionV2`:
    1. Phát hiện và từ chối các trạng thái không hợp lệ: `SUBSCRIPTION_STATE_UNKNOWN` hoặc `SUBSCRIPTION_STATE_UNSPECIFIED` bị ném lỗi `GooglePlayApiError(..., 400)`.
    2. Khớp sản phẩm nghiêm ngặt: `lineItems.find(li => li.productId === expectedSubscriptionId)`. Nếu không tìm thấy sản phẩm yêu cầu, ném lỗi 400 thay vì tự tiện lấy `lineItems[0]` của sản phẩm khác.
- `backend/billing-verifier/src/verifier.ts`:
  - Ánh xạ đầy đủ các trạng thái Google Play V2: `SUBSCRIPTION_STATE_ACTIVE`, `IN_GRACE_PERIOD`, `CANCELED`, `PAUSED`, `ON_HOLD`, `EXPIRED`, `PENDING`.
  - Gói ở trạng thái `PAUSED` hoặc `ON_HOLD` không được cấp VIP active.
  - Bắt lỗi 400 khi sai sản phẩm và từ chối với lý do `PRODUCT_NOT_ALLOWED`.
- `backend/billing-verifier/test/googlePlayTransport.test.ts`:
  - Cập nhật test transport kiểm tra URL `subscriptionsv2` và cấu trúc phân tích V2.
- `REPORT_VIP_R4_T02.md`: Báo cáo nội bộ gói T02.

---

## 2. Bằng Chứng Red -> Green & Thực Thi Thực Tế

### 2.1. Ba Probe Đỏ T00 Chuyển Xanh
1. Test: `V2 SUBSCRIPTION_STATE_PAUSED must not become active paid entitlement`
   - Trước (T00): **FAIL** (State PAUSED bị parse thành CANCELED_ACTIVE rồi cấp VIP)
   - Sau (T02): **PASS** (State PAUSED được ghi nhận đúng, không nằm trong các trạng thái cấp quyền active)
2. Test: `V2 SUBSCRIPTION_STATE_UNKNOWN must not become active paid entitlement`
   - Trước (T00): **FAIL** (State UNKNOWN bị gán cẩu thả thành active)
   - Sau (T02): **PASS** (State UNKNOWN bị ném lỗi và từ chối `REJECTED`)
3. Test: `V2 product mismatch must not grant requested VIP`
   - Trước (T00): **FAIL** (Line item `different_product` vẫn cấp VIP cho `tscanner_vip_yearly`)
   - Sau (T02): **PASS** (Phát hiện mismatch, từ chối cấp VIP với lý do `PRODUCT_NOT_ALLOWED`)

### 2.2. Kiểm Tra Focused Transport Suite
- Lệnh: `node --experimental-strip-types --test backend/billing-verifier/test/googlePlayTransport.test.ts`
- Kết quả: **7 / 7 PASS (100% GREEN)**, thời gian: ~301ms.

---

## 3. Handoff Cho Gói Sau (T03)

- Truy vấn subscriptionsv2 và hợp đồng trạng thái/sản phẩm đã chuẩn xác.
- Gói T03 tiếp nhận: Sửa R05 (Owner/hash kiểm trước mọi mutation):
  - File: `backend/billing-verifier/src/verifier.ts`.
  - Khắc phục probe đỏ `expired receipt owned by another Play hash must not bind to caller`.
  - Kiểm tra `externalAccountIdFromPlay` (Google obfuscated account hash) và ownership hiện có ngay trước khi thực hiện bind ở bất kỳ nhánh nào (kể cả EXPIRED và REVOKED).
- Chuyển tiếp tự động sang T03.
