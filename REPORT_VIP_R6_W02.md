# Báo cáo VIP R6 — Gói W02: Restore backend giữ fidelity cho unresolved/per-token results (R02 backend)

## 1. Mục tiêu và phạm vi
- Khắc phục **R02** phía backend (Probe **B01**, **B03**):
  - Khi refresh receipt đã biết (known receipt) trong quá trình `restorePurchases` mà gặp lỗi 404 hoặc permanent rejection, trạng thái tổng thể không được trả về `SUCCESS` đầy đủ giả tạo mang entitlement active cũ.
  - Phân định rõ taxonomy: `SUCCESS` (mọi candidate được giải quyết hoàn tất), `PARTIAL` (có candidate active chưa thể refresh hoặc mixed resolution), `TRANSIENT_ERROR` (toàn bộ candidates gặp lỗi mạng tạm thời).
- Bảo toàn toàn bộ 99/99 unit & integration tests của backend (bao gồm 7 probe vòng 5 và 3 probe vòng 6).

## 2. File thay đổi
- `backend/billing-verifier/src/verifier.ts`:
  - Trong `restorePurchases`:
    - Xác định `knownTokenSet` từ các entitlements đã lưu trong DB của owner.
    - Phát hiện `knownActiveUnresolved`: candidate thuộc `knownTokenSet` đang ở trạng thái active (`VERIFIED_ACTIVE`, `CANCELED_ACTIVE`, `IN_GRACE_PERIOD`) mà kết quả refresh trả về `REJECTED` hoặc `TRANSIENT_ERROR`.
    - Phát hiện `hasMixedResolution`: có cả authoritative resolution (`SUCCESS`, `EXPIRED`, `REVOKED`) lẫn failure (`REJECTED`, `TRANSIENT_ERROR`).
    - Nếu `knownActiveUnresolved` hoặc `hasMixedResolution` xảy ra, gán `status = 'PARTIAL'` kèm `message` giải thích rõ thay vì trả về `SUCCESS`.
    - Trả về `message` trong `RestoreResponse`.

## 3. Kết quả kiểm tra
- **Trước khi sửa (W00):**
  - B01 FAIL: `AssertionError [ERR_ASSERTION]: Rejected refresh was reported as fresh SUCCESS containing stale ACTIVE`
- **Sau khi sửa (W02):**
  - `backend/billing-verifier/test/round6-regression.test.ts`:
    - ✔ B01 unresolved known receipt must not return fresh full restore success (PASS)
    - ✔ B02 canonical pending-purchase-canceled state must be parsed for linked-token recovery (PASS)
    - ✔ B03 control mixed restore accurately declares PARTIAL on transient refresh (PASS)
    - **Kết quả: 3/3 PASS (100%)**
  - Toàn bộ backend test suite (`node --experimental-strip-types --test test/*.test.ts`):
    - **99/99 PASS (0 fail, 0 skipped)**

## 4. Contract đã cập nhật
- Khi restore tìm thấy token đã biết nhưng không thể refresh authoritative (ví dụ 404):
  - Per-token status trong `results[]` ghi `REJECTED`.
  - Cache active cũ được bảo toàn trong snapshot (không tự xóa mù quáng).
  - Status tổng thể của `RestoreResponse` chuyển sang `PARTIAL` với `message: 'One or more purchases could not be verified with Google Play.'`
  - Đảm bảo client không hiểu nhầm đây là một lần xác thực mới thành công hoàn chỉnh.

## 5. Bàn giao gói tiếp theo
- Chuyển sang **W03**: Android parser ràng buộc type/source theo catalog (R06 - Probes **A07**, **A08**).
