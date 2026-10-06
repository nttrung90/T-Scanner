# Báo cáo VIP R6 — Gói W01: Lifecycle canceled pending và linked receipt (R07)

## 1. Mục tiêu và phạm vi
- Khắc phục **R07** (Probe **B02**): Xử lý canonical `SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED` từ Google Play Developer API (Android Publisher v3 Subscriptions V2).
- Hỗ trợ phân tích và bảo toàn `linkedPurchaseToken` khi pending purchase bị hủy mà không cấp quyền trả phí cho purchase token mới bị hủy; resolve authoritative old linked token qua Play API với CAS version guard và cycle/depth bound.
- Bảo toàn toàn bộ 7/7 probe vòng 5 và các enum lifecycle khác (`ON_HOLD`, `PAUSED`, `IN_GRACE_PERIOD`, `CANCELED`, `EXPIRED`, `PENDING`).

## 2. File thay đổi
- `backend/billing-verifier/src/googlePlayClient.ts`:
  - Bổ sung `'SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED'` vào `VALID_STATES`.
  - Nới lỏng yêu cầu `expiryTime` bắt buộc đối với trạng thái `SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED` (tương tự `SUBSCRIPTION_STATE_PENDING`).
  - Thiết lập `paymentState = 0`, `autoRenewing = false`, `cancelReason = 1`.
- `backend/billing-verifier/src/verifier.ts`:
  - Trong `verifyPurchase`: thêm nhánh `case 'SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED': state = 'REVOKED'; break;`
  - Thêm `resolveLinkedSubscriptionToken` giải quyết authoritative `linkedPurchaseToken` với cycle check, depth bound (`depth >= 1`), owner binding check, và CAS update.
- `backend/billing-verifier/src/rtdnHandler.ts`:
  - Thêm nhánh `case 'SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED': newState = 'REVOKED'; autoRenewing = false; break;`

## 3. Kết quả kiểm tra
- **Trước khi sửa (W00):**
  - B02 FAIL: `Error [GooglePlayApiError]: Subscription is in invalid or unknown state: SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED`
- **Sau khi sửa (W01):**
  - `backend/billing-verifier/test/round6-regression.test.ts`:
    - ✔ B02 canonical pending-purchase-canceled state must be parsed for linked-token recovery (PASS)
    - ✔ B03 control mixed restore accurately declares PARTIAL on transient refresh (PASS)
    - ✖ B01 unresolved known receipt must not return fresh full restore success (FAIL - bàn giao cho W02)
  - `backend/billing-verifier/test/round5-regression.test.ts`: 7/7 PASS (B01–B07)

## 4. Contract đã cập nhật
- Enum `SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED` được công nhận là canonical Play V2 state; không bị phân loại nhầm thành malformed (HTTP 400).
- New canceled purchase token được lưu dưới dạng `REVOKED` (không cấp quyền active VIP).
- `linkedPurchaseToken` được resolve độc lập, bảo toàn quyền cho token cũ nếu hợp lệ.

## 5. Bàn giao gói tiếp theo
- Chuyển sang **W02**: Sửa R02 backend (Probe **B01**): Đảm bảo khi refresh known receipt bị lỗi 404 hoặc permanent error thì không trả về fresh full `SUCCESS` với stale active cache; đồng bộ `results[]` taxonomy và restore status.
