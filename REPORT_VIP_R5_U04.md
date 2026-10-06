# Báo cáo U04 — Restore backend không bị metadata client vô hiệu hóa (F03 B05)

## 1. Mục tiêu và phạm vi
- Sửa lỗi F03 (probe B05) theo kế hoạch Round 5:
  - File: `backend/billing-verifier/src/verifier.ts`
  - Vấn đề: `restorePurchases` gộp các purchase tokens từ DB (`knownEntitlements`) và từ request client (`req.purchases`) vào `candidateMap`. Trước đây, vòng lặp sau ghi đè mù quáng `candidateMap` bằng `p.productId` từ client. Khi client gửi metadata rác/không hợp lệ (`invalid-client-sku`), `verifyPurchase` bị chặn sớm ở bước `isValidProduct` (`PRODUCT_NOT_ALLOWED`) trước khi kịp gọi Google Play API, khiến token đã bị hoàn tiền trong DB không được refresh trạng thái `REVOKED`.
  - Khắc phục: Giữ bản ghi DB là authoritative cho các token đã tồn tại trong `knownEntitlements`, chỉ bổ sung candidate mới từ `req.purchases` nếu token đó chưa từng có trong DB (`if (!candidateMap.has(p.purchaseToken))`). Qua đó đảm bảo `verifyPurchase` luôn dùng đúng `productId` và `productType` hợp lệ đã lưu để truy vấn Google Play và cập nhật trạng thái `REVOKED` chính xác.

## 2. Kết quả kiểm tra trước / sau sửa đổi

### Focused Probes (Round 5 Regression Test: `backend/billing-verifier/test/round5-regression.test.ts`)
- Trước U04: 7 tests (6 PASS, 1 FAIL: B05 FAIL).
- Sau U04: **7 tests (7 PASS, 0 FAIL)** — **100% PASS**
  - ✔ `B07 late RTDN must not overwrite newer verify expiry` (**PASS**)
  - ✔ `B01 first-binding late active must not resurrect committed expiry` (**PASS**)
  - ✔ `B02 conflict must not discard authoritative expiry and return active` (**PASS**)
  - ✔ `B03 on-hold V2 must invalidate stored active grant` (**PASS**)
  - ✔ `B04 missing V2 lifecycle state must not grant active` (**PASS**)
  - ✔ `B05 client metadata must not suppress refresh of known refunded receipt` (**ĐÃ SỬA - PASS**)
  - ✔ `B06 control empty restore refreshes refund with no conflicting metadata` (**PASS đối chứng**)

### Toàn Bộ Suite Backend Hiện Có
- Lệnh: `node --experimental-strip-types --test backend/billing-verifier/test/*.test.ts`
- Kết quả: **96 tests, 96 PASS, 0 FAIL**.
- Không có bất kỳ regression nào trên toàn bộ backend billing verifier.

## 3. Thay đổi Contract và Thiết kế
- `candidateMap` trong `restorePurchases`:
  - Token đã biết trong DB (`knownEntitlements`): DB metadata (`productId`, `productType`) là authoritative. Client payload không được phép ghi đè.
  - Token mới chưa có trong DB: Được thêm vào `candidateMap` để chuyển tới `verifyPurchase` xác thực và lưu vào DB nếu hợp lệ.
  - Snapshot trả về phản ánh chính xác trạng thái thực tế sau khi refresh từ Google Play.

## 4. Handoff cho Gói Tiếp Theo
- Toàn bộ 7/7 backend probes (B01–B07) đã hoàn toàn xanh.
- Chuyển tiếp sang tầng Client Android: **U05 — Android PurchaseVerifier & PlayPurchaseVerifier (F04: A01, A02, A03, A04, A07)**.
