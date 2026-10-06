# Báo cáo U01 — Lifecycle Play V2 nhất quán (F02)

## 1. Mục tiêu và phạm vi
- Sửa lỗi F02 (B03, B04):
  - `backend/billing-verifier/src/googlePlayClient.ts`: Validate nghiêm ngặt enum `subscriptionState` Subscriptions V2 (7 trạng thái hợp lệ theo Google Publisher v3), không suy đoán enum số; cho phép pending subscriptions không có expiryTime/grant time; loại bỏ việc gán `paymentState = 0` cho `ON_HOLD`.
  - `backend/billing-verifier/src/verifier.ts`: Đặt kiểm tra lifecycle V2 (`subscriptionState`) lên trước fallback V1; loại bỏ kiểm tra vội vã `paymentState === 0` và khối duplicate `bindOrUpdate` của expiry; ghi nhận và lưu trữ `ON_HOLD`, `PAUSED`, `EXPIRED`, `REVOKED` vào store dưới version mới; đảm bảo không trả `SUCCESS` cho các trạng thái không còn quyền.
  - `backend/billing-verifier/test/googlePlayTransport.test.ts`: Cập nhật assertion kiểm tra `ON_HOLD` xác nhận `subscriptionState` đúng và không đánh đồng với pending payment `paymentState = 0`.

## 2. Kết quả kiểm tra trước / sau sửa đổi

### Focused Probes (Round 5 Regression Test)
- Trước U01: 7 tests (1 PASS, 6 FAIL: B07, B01, B02, B03, B04, B05 FAIL; B06 PASS).
- Sau U01: **7 tests (3 PASS, 4 FAIL)**
  - ✔ `B03 on-hold V2 must invalidate stored active grant` (**ĐÃ SỬA - PASS**)
  - ✔ `B04 missing V2 lifecycle state must not grant active` (**ĐÃ SỬA - PASS**)
  - ✔ `B06 control empty restore refreshes refund with no conflicting metadata` (**PASS**)
  - ✖ `B01 first-binding late active must not resurrect committed expiry` (FAIL - Bàn giao cho U02)
  - ✖ `B02 conflict must not discard authoritative expiry and return active` (FAIL - Bàn giao cho U02)
  - ✖ `B07 late RTDN must not overwrite newer verify expiry` (FAIL - Bàn giao cho U03)
  - ✖ `B05 client metadata must not suppress refresh of known refunded receipt` (FAIL - Bàn giao cho U04)

### Regression Test Suite Backend Hiện Có
- Lệnh: `node --experimental-strip-types --test backend/billing-verifier/test/googlePlayTransport.test.ts backend/billing-verifier/test/verifier.test.ts backend/billing-verifier/test/restore-revocation.test.ts backend/billing-verifier/test/round4-regression.test.ts`
- Kết quả: **40 tests, 40 PASS, 0 FAIL**.

## 3. Thay đổi Contract và Thiết kế
- `googlePlayClient.ts`:
  - `parseSubscriptionV2`: Yêu cầu `subscriptionState` phải là string thuộc tập 7 enum chuẩn Google: `SUBSCRIPTION_STATE_PENDING`, `SUBSCRIPTION_STATE_ACTIVE`, `SUBSCRIPTION_STATE_PAUSED`, `SUBSCRIPTION_STATE_IN_GRACE_PERIOD`, `SUBSCRIPTION_STATE_ON_HOLD`, `SUBSCRIPTION_STATE_CANCELED`, `SUBSCRIPTION_STATE_EXPIRED`. Bất kỳ giá trị số, undefined hoặc chuỗi lạ đều ném lỗi (502 nếu thiếu, 400 nếu không hợp lệ).
  - `SUBSCRIPTION_STATE_ON_HOLD`: Giữ `paymentState = 1` (không coi là pending payment lúc khởi tạo).
- `verifier.ts`:
  - Kiểm tra `subResult.subscriptionState` authoritative trước fallback V1.
  - Lưu `ON_HOLD`, `PAUSED`, `EXPIRED`, `REVOKED` vào store và trả về `REJECTED` (không cấp quyền active VIP).

## 4. Handoff cho Gói Tiếp Theo
- Bàn giao mô hình lifecycle cho **U02 — CAS có expected-absent và xử lý conflict (F01 B01/B02)**.
- Gói tiếp theo: U02 (Tự động thực hiện).
