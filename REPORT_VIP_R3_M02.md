# Báo Cáo Triển Khai VIP Vòng 3 — Gói M02: Schema & State Authoritative Google Play (F06 Backend, Nền F08)

**Thời điểm:** 26/09/2026  
**Mục tiêu:** Loại bỏ phát hiện F06 phía backend, parse strictly phản hồi từ Google Play Developer API (Android Publisher v3), chặn đứng payload rỗng `{}` tự động cấp VIP trọn đời, hỗ trợ Google `subscriptionsv2` current lifecycle states, và che giấu (mask) token trong thông điệp lỗi và logs.

---

## 1. File Thay Đổi & Tạo Mới

- `backend/billing-verifier/src/googlePlayClient.ts`:
  - Bổ sung hàm tiện ích `maskToken(token)` che giấu ký tự nhạy cảm trong toàn bộ các lỗi 404/API (`raw_...6789`).
  - Strict validation cho `getInAppProduct`: phát hiện và từ chối payload rỗng `{}` hoặc thiếu `purchaseState`/`purchaseTimeMillis`, ném `GooglePlayApiError` thay vì ngầm định gán purchaseState = 0 (Purchased).
  - Triển khai `parseSubscriptionV2`: phân tích cấu trúc Google Play `subscriptionsv2`, ánh xạ chuẩn xác các trạng thái `SUBSCRIPTION_STATE_ACTIVE`, `SUBSCRIPTION_STATE_IN_GRACE_PERIOD`, `SUBSCRIPTION_STATE_CANCELED`, `SUBSCRIPTION_STATE_ON_HOLD`, `SUBSCRIPTION_STATE_PAUSED`, `SUBSCRIPTION_STATE_EXPIRED`, `SUBSCRIPTION_STATE_PENDING`.
  - Strict validation cho `getSubscription` v1 legacy: bắt buộc `expiryTimeMillis > 0` và `paymentState` hợp lệ.
  - Cập nhật interface `GooglePlaySubscriptionResult` với `subscriptionState` và `lineItemProductId`.
- `backend/billing-verifier/test/googlePlayTransport.test.ts`:
  - Bổ sung test case `M02: Google Play Transport - Schema strictness and subscriptionsv2 lifecycle mapping` kiểm thử chặt chẽ:
    1. Empty JSON in-app bị từ chối bằng lỗi tạm thời (transient).
    2. Empty JSON subscription bị từ chối bằng lỗi tạm thời (transient).
    3. Phân tích subscriptionsv2 cho ACTIVE, IN_GRACE_PERIOD, CANCELED, ON_HOLD, PAUSED.
    4. Từ chối lineItems rỗng trong subscriptionsv2.
    5. Masking raw token khi gặp mã lỗi 404.
- `REPORT_VIP_R3_M02.md`: Báo cáo nội bộ gói M02.

---

## 2. Bằng Chứng Red -> Green & Thực Thi Thực Tế

### 2.1. Probe Đỏ M00 Chuyển Xanh
- Test: `empty Play JSON must not grant lifetime VIP`
  - Trước (M00): **FAIL** (Expected actual != 'SUCCESS', nhận 'SUCCESS')
  - Sau (M02): **PASS** (Tải về `{}` phát sinh transient error, verifier trả về `TRANSIENT_ERROR`, ngăn chặn cấp quyền VIP trái phép)

### 2.2. Kiểm Tra Focused Transport Suite
- Lệnh: `node --experimental-strip-types --test backend/billing-verifier/test/googlePlayTransport.test.ts`
- Kết quả: **7/7 PASS**, 0 fail, thời gian: ~420ms.

### 2.3. Kiểm Tra Toàn Bộ Backend Suite
- Lệnh: `node --experimental-strip-types --test backend/billing-verifier/test/*.test.ts`
- Kết quả: **69/73 PASS** (4 fail còn lại thuộc về các gói M03, M04, M05). Không phát sinh bất kỳ regression nào trong baseline.

---

## 3. Handoff Cho Gói Sau (M03)

- Schema Google Play authoritative (cả in-app và subscription v1/v2) đã được chốt và validate nghiêm ngặt.
- Gói M03 tiếp nhận: Xử lý F04 và F05 phía backend (`verifier.ts`, `types.ts`), đảm bảo `entitlement.id` giữ nguyên canonical identity ổn định (`GOOGLE_PLAY_SUBSCRIPTION_${token}` hoặc `GOOGLE_PLAY_INAPP_${token}`) xuyên suốt toàn bộ lifecycle kể cả khi EXPIRED hoặc REVOKED (không đổi thành bare token), cung cấp tombstone snapshot có thẩm quyền trong phản hồi `REJECTED`, ngăn chặn xung đột chủ sở hữu khi restore/verify.
- Chuyển tiếp tự động sang M03.
