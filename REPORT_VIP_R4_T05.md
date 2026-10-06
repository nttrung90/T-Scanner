# Báo Cáo Triển Khai VIP Vòng 4 — Gói T05: Server Restore Known Tokens & Freshness (R01 Backend)

**Thời điểm:** 27/09/2026  
**Mục tiêu:** Loại bỏ phát hiện R01 phía backend, đảm bảo endpoint `restorePurchases` tập hợp ứng viên (`candidate set`) bao gồm toàn bộ các bản ghi đã biết trong store của chủ sở hữu cộng với các token do client gửi lên (khử trùng lặp theo `purchaseToken`), tự động làm mới trạng thái thẩm quyền với Google Play đối với các gói trọn đời và gói đăng ký kể cả khi client gửi danh sách `purchases: []` (ví dụ trên thiết bị mới cài đặt hoặc máy không có receipt cục bộ).

---

## 1. File Thay Đổi & Tạo Mới

- `backend/billing-verifier/src/verifier.ts`:
  - Trong `restorePurchases`:
    1. Truy vấn các quyền đã lưu của chủ sở hữu: `knownEntitlements = await this.store.getEntitlementsByOwner(req.ownerAppUserId)`.
    2. Hợp nhất `knownEntitlements` và `req.purchases` vào `candidateMap` (khử trùng lặp theo `purchaseToken`).
    3. Duyệt qua từng ứng viên trong `candidateMap` để chạy `verifyPurchase`, tự động phát hiện các giao dịch trọn đời đã bị Google Play hoàn tiền (`purchaseState: 1`) hoặc các subscription đã hết hạn, cập nhật tombstone `REVOKED`/`EXPIRED` vào store.
    4. Trả về snapshot mới nhất đã được làm mới (`freshEntitlements`), ngăn chặn việc trả về cache cũ không còn hiệu lực.
- `REPORT_VIP_R4_T05.md`: Báo cáo nội bộ gói T05.

---

## 2. Bằng Chứng Red -> Green & Thực Thi Thực Tế

### 2.1. Probe Đỏ T00 Chuyển Xanh
- Test: `empty-candidate restore must refresh known lifetime receipt before success`
  - Trước (T00): **FAIL** (`actual: 'VERIFIED_ACTIVE', expected: 'REVOKED'`)
  - Sau (T05): **PASS** (Server tự động làm mới token đã biết từ Play, phát hiện hoàn tiền và trả về trạng thái `REVOKED`)

### 2.2. Kiểm Tra Toàn Bộ Backend Suite (Round 4 + Baseline)
- Lệnh: `node --experimental-strip-types --test backend/billing-verifier/test/*.test.ts`
- Kết quả: **89 / 89 PASS (100% GREEN)**, 0 failures, 0 errors, thời gian: ~2.0s.
  - Toàn bộ **10/10 probe Vòng 4** trong `round4-regression.test.ts` đều đã **PASS**.
  - Toàn bộ các phát hiện backend Vòng 4 (R02, R03, R04, R05, R01 backend) đã được giải quyết dứt điểm.

---

## 3. Handoff Cho Gói Sau (T06)

- Phân hệ Backend đã hoàn thành 100% và đạt trạng thái 89/89 tests GREEN.
- Gói T06 tiếp nhận: Bắt đầu phân hệ Android Client: Sửa R06 (Android response binding và schema):
  - File: `app/src/main/java/com/tscanner/app/utils/billing/PlayPurchaseVerifier.kt`, `PurchaseVerifier.kt`.
  - Khắc phục probe đỏ: `responseDifferentTokenMustBeRejected`.
  - Ràng buộc chặt chẽ dữ liệu phản hồi từ backend: `purchaseToken`, `productId`, `productType`, `ownerAppUserId` trong response phải khớp tuyệt đối với request; từ chối và trả về lỗi nếu response mang token khác với request token.
- Chuyển tiếp tự động sang T06.
