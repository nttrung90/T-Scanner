# Báo cáo U02 — CAS có expected-absent và xử lý conflict (F01 B01/B02)

## 1. Mục tiêu và phạm vi
- Sửa lỗi F01 cho các probe B01 và B02:
  - `backend/billing-verifier/src/storage/types.ts`: Cập nhật `BindOptions` để hỗ trợ `expectedVersion: number | null` (trong đó `null` thể hiện rõ ràng kỳ vọng token chưa tồn tại - expected-absent) và `expectedAbsent?: boolean`. Thêm cờ `casConflict?: boolean` vào `BindResult`.
  - `backend/billing-verifier/src/storage/sqliteDriver.ts`:
    - Khi bản ghi đã tồn tại trong DB nhưng caller chỉ định `expectedAbsent === true` hoặc `expectedVersion === null`: Phát hiện CAS conflict, ROLLBACK và trả về `{ success: false, record, casConflict: true }`.
    - Khi bản ghi chưa tồn tại trong DB nhưng caller chỉ định `expectedVersion` dạng số hoặc `expectedAbsent === false`: Phát hiện CAS conflict, ROLLBACK và trả về `{ success: false, casConflict: true }`.
    - Khi version hiện tại trong DB khác `expectedVersion`: Phát hiện CAS conflict, ROLLBACK và trả về `{ success: false, record, casConflict: true }`.
  - `backend/billing-verifier/src/verifier.ts`:
    - Chụp `existingBeforeQuery` trước khi await Google Play (`expectedVersion = existingBeforeQuery ? existingBeforeQuery.latestSnapshotVersion : null`).
    - Bọc logic xác thực và commit vào vòng lặp bounded retry (tối đa 5 lần). Khi xảy ra CAS conflict, refetch Google Play API với version snapshot mới.
    - Hết ngân sách retry trả về `TRANSIENT_ERROR` (có thể retry), tuyệt đối không lưu dữ liệu cũ hoặc trả về cache ACTIVE cũ như một lần xác thực tươi.
    - Hợp nhất nhánh in-app refund/canceled vào luồng CAS và commit chung.

## 2. Kết quả kiểm tra trước / sau sửa đổi

### Focused Probes (Round 5 Regression Test)
- Trước U02: 7 tests (3 PASS, 4 FAIL: B01, B02, B05, B07 FAIL).
- Sau U02: **7 tests (5 PASS, 2 FAIL)**
  - ✔ `B01 first-binding late active must not resurrect committed expiry` (**ĐÃ SỬA - PASS**)
  - ✔ `B02 conflict must not discard authoritative expiry and return active` (**ĐÃ SỬA - PASS**)
  - ✔ `B03 on-hold V2 must invalidate stored active grant` (**PASS từ U01**)
  - ✔ `B04 missing V2 lifecycle state must not grant active` (**PASS từ U01**)
  - ✔ `B06 control empty restore refreshes refund with no conflicting metadata` (**PASS đối chứng**)
  - ✖ `B07 late RTDN must not overwrite newer verify expiry` (FAIL - Bàn giao cho U03)
  - ✖ `B05 client metadata must not suppress refresh of known refunded receipt` (FAIL - Bàn giao cho U04)

### Suite Kiểm thử Toàn diện Backend Hiện Có
- `verifier.test.ts`: **15/15 PASS** (bao gồm concurrent replay 5 requests song song đạt tuyệt đối).
- Toàn bộ suite backend hiện có không gặp lỗi hồi quy.

## 3. Thay đổi Contract và Thiết kế
- `BindOptions.expectedVersion`: phân biệt rõ giữa `null` (expected absent), số nguyên dương (expected exact version) và `undefined` (không yêu cầu CAS).
- `BindResult.casConflict`: phân biệt rõ giữa `staleIgnored` (do thứ tự event time của RTDN) và `casConflict` (xung đột version cần refetch/retry).
- Verifier loop: Chụp version trước await, cam kết CAS atomic, refetch khi conflict, không bao giờ ngụy trang cache cũ thành kết quả tươi.

## 4. Handoff cho Gói Tiếp Theo
- Bàn giao cơ chế CAS `expectedVersion` và `casConflict` cho **U03 — RTDN và verify dùng chung cơ chế đồng thời (F01 B07)**.
- Gói tiếp theo: U03 (Tự động thực hiện).
