# Báo cáo U03 — RTDN và verify dùng chung cơ chế đồng thời (F01 B07)

## 1. Mục tiêu và phạm vi
- Sửa lỗi F01 cho probe B07:
  - `backend/billing-verifier/src/rtdnHandler.ts`:
    - Chụp `expectedVersion` từ bản ghi token trước khi await truy vấn Google Play.
    - Áp dụng CAS guard `{ eventTimeMillis: eventTime, expectedVersion }` khi gọi `store.bindOrUpdate`.
    - Bọc việc xử lý RTDN trong vòng lặp bounded retry (tối đa 3 lần). Khi phát hiện CAS conflict do một thao tác verify/restore khác commit trước, RTDN sẽ tự động re-read snapshot version mới và refetch Google Play API.
    - Đảm bảo khi retry/conflict thất bại không đánh dấu event processed trong `lastProcessedEventTimes` để Pub/Sub có thể redeliver/retry.
    - Áp dụng mapping authoritative V2, không để logic expiry ghi đè trạng thái `REVOKED`.

## 2. Kết quả kiểm tra trước / sau sửa đổi

### Focused Probes (Round 5 Regression Test)
- Trước U03: 7 tests (5 PASS, 2 FAIL: B07, B05 FAIL).
- Sau U03: **7 tests (6 PASS, 1 FAIL)**
  - ✔ `B07 late RTDN must not overwrite newer verify expiry` (**ĐÃ SỬA - PASS**)
  - ✔ `B01 first-binding late active must not resurrect committed expiry` (**PASS từ U02**)
  - ✔ `B02 conflict must not discard authoritative expiry and return active` (**PASS từ U02**)
  - ✔ `B03 on-hold V2 must invalidate stored active grant` (**PASS từ U01**)
  - ✔ `B04 missing V2 lifecycle state must not grant active` (**PASS từ U01**)
  - ✔ `B06 control empty restore refreshes refund with no conflicting metadata` (**PASS đối chứng**)
  - ✖ `B05 client metadata must not suppress refresh of known refunded receipt` (FAIL - Bàn giao cho U04)

### Suite Kiểm thử RTDN Hiện Có
- Lệnh: `node --experimental-strip-types --test backend/billing-verifier/test/rtdn_and_lifecycle.test.ts backend/billing-verifier/test/rtdn-recovery.test.ts`
- Kết quả: **12 tests, 12 PASS, 0 FAIL**.

## 3. Thay đổi Contract và Thiết kế
- RTDN handler và Verifier giờ đây cùng tham gia vào một cơ chế đồng thời nhất quán thông qua CAS `expectedVersion`.
- Event timestamp chỉ dùng để ordering các event RTDN (loại bỏ event cũ hơn); `expectedVersion` bảo vệ trạng thái khỏi race conditions giữa các kênh khác nhau (RTDN ↔ verify purchase ↔ restore).

## 4. Handoff cho Gói Tiếp Theo
- Bàn giao taxonomy kết quả xác thực và cơ chế CAS cho **U04 — Restore backend không bị metadata client vô hiệu hóa (F03)**.
- Gói tiếp theo: U04 (Tự động thực hiện).
