# Báo cáo Checkpoint VIP Round 7 — Gói X01

## 1. Mục tiêu và phạm vi gói X01
- **Mục tiêu:** Khắc phục lỗi F01 — thiếu CAS và explicit absent sentinel trong luồng liên kết token (`resolveLinkedSubscriptionToken`), dẫn đến tình trạng truy vấn Play API bị trễ ghi đè hoặc hồi sinh token đã hết hạn/bị thu hồi bởi authority mới.
- **Phạm vi file:**
  - `backend/billing-verifier/src/verifier.ts`
  - `backend/billing-verifier/test/round7-regression.test.ts`
  - `PROGRESS_VIP_R7_AUTORUN.md`
  - `REPORT_VIP_R7_X01.md`

## 2. Thay đổi chi tiết trong code sản phẩm
- **Trong `verifier.ts` (`resolveLinkedSubscriptionToken`):**
  - Thêm vòng lặp CAS retry với ngân sách `MAX_LINKED_CAS_RETRIES = 5`.
  - Khai báo explicit absent sentinel: Khi record linked token chưa tồn tại trong DB, truyền `expectedVersion: null` và `expectedAbsent: true` vào `store.bindOrUpdate`.
  - Kiểm tra `bindResult`:
    - Nếu `casConflict`: Log cảnh báo và thực hiện retry (đọc lại record mới nhất từ SQLite và truy vấn lại authority Google Play).
    - Nếu hết ngân sách retry CAS: Ném lỗi `CAS conflict retry budget exhausted` để không tiếp tục ghi đè dữ liệu sai lệch.
    - Nếu `conflictOwner`: Ghi log và hủy bỏ việc cấp token thuộc về user khác.
    - Nếu lỗi lưu trữ: Ném ngoại lệ rõ ràng thay vì bỏ qua âm thầm.

## 3. Kết quả kiểm thử trước và sau sửa

### 3.1 Probes mục tiêu
- **B701 (`first-bind linked query must not overwrite a newer authoritative EXPIRED receipt`):**
  - Trước: ✖ FAIL (`AssertionError: Late linked query resurrected VERIFIED_ACTIVE at v3`)
  - Sau: ✔ **PASS** (4.68ms)
- **B711 (`linked first-bind race must remain protected across two SQLite connections and restart`):**
  - Trước: ✖ FAIL (`AssertionError: Wrong durable grant survived restart: VERIFIED_ACTIVE at v3`)
  - Sau: ✔ **PASS** (1025.8ms)
- **B709 (`control same-SKU linked ACTIVE receipt is resolved and owned correctly`):**
  - Trước: ✔ PASS
  - Sau: ✔ **PASS** (1.45ms)
- **B710 (`control linked receipt already owned by another account is preserved`):**
  - Trước: ✔ PASS
  - Sau: ✔ **PASS** (1.16ms)

### 3.2 Bộ test Backend tổng thể
- **Lệnh chạy:** `node --experimental-strip-types --test backend/billing-verifier/test/*.test.ts`
- **Kết quả:** 108 tests (103 PASS, 5 FAIL — 5 test còn lại thuộc phạm vi X02, X03, X04).
- Toàn bộ 99 bài test backend baseline không bị hồi quy (regression-free).

## 4. Hợp đồng và Giới hạn
- Version của entitlement snapshot đảm bảo tính đơn điệu (monotonic).
- Quyền sở hữu owner bất biến khi có xung đột tài khoản.
- Chưa xử lý SKU khác nhau giữa token mới và linked token cũ (được thực hiện ở X02).

## 5. Chuyển giao gói tiếp theo
- **Gói kế tiếp:** **X02 — Linked identity và typed resolution outcome (F02)**.
- **Mục tiêu X02:** Giải quyết B702 và B703 bằng cách xác định SKU thực của linked token (từ DB hoặc lineItems trong Subscriptions V2), tránh dùng SKU mới để truy vấn token cũ khi nâng/hạ cấp gói, và trả về typed resolution outcome rõ ràng cho restore.
