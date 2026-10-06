# Báo Cáo Triển Khai VIP Vòng 4 — Gói T04: CAS Tổng Quát & Concurrency Mọi State (R04)

**Thời điểm:** 27/09/2026  
**Mục tiêu:** Loại bỏ phát hiện R04, xóa bỏ cơ chế kiểm tra điều kiện đặc thù 1 chiều (chỉ chặn `ACTIVE -> EXPIRED`), triển khai hợp đồng Compare-And-Swap (CAS) tổng quát dựa trên `expectedVersion` cho mọi trạng thái (kể cả first bind với `expectedVersion = 0`), đảm bảo khi có xung đột phiên bản thì giao dịch đến sau không được phép ghi đè trạng thái đã cam kết mới hơn của giao dịch hoàn tất trước.

---

## 1. File Thay Đổi & Tạo Mới

- `backend/billing-verifier/src/storage/sqliteDriver.ts`:
  - Trong `bindOrUpdate`: thay thế khối logic so sánh hardcoded (`ACTIVE` vs `EXPIRED`) bằng cơ chế CAS tổng quát đối soát `options.expectedVersion !== undefined && Number(existingRow.latest_snapshot_version) !== options.expectedVersion`.
  - Nếu phiên bản trong database đã thay đổi trong lúc truy vấn bên ngoài đang chạy, giao dịch lập tức `ROLLBACK` và trả về `{ success: true, record: existingRecord, staleIgnored: true }`.
  - Trong nhánh Insert: nếu caller truyền `expectedVersion > 0` (kỳ vọng bản ghi đã tồn tại) mà bản ghi không có, từ chối và rollback.
- `backend/billing-verifier/src/verifier.ts`:
  - Thu thập `expectedVersion = existingBeforeQuery ? existingBeforeQuery.latestSnapshotVersion : 0` ở đầu hàm `verifyPurchase`.
  - Truyền `{ expectedVersion }` vào tất cả các nhánh (`EXPIRED`, `REVOKED`, `ACTIVE`).
  - Trong nhánh xử lý `bindResult.staleIgnored`: trả về chính xác kết quả trạng thái của bản ghi đã chiến thắng trong database (`EXPIRED` trả về `REJECTED (PURCHASE_EXPIRED)`, `REVOKED` trả về `REJECTED (PURCHASE_REVOKED)`, `ACTIVE` trả về `SUCCESS`).
- `REPORT_VIP_R4_T04.md`: Báo cáo nội bộ gói T04.

---

## 2. Bằng Chứng Red -> Green & Thực Thi Thực Tế

### 2.1. Probe Đỏ T00 Chuyển Xanh
- Test: `late ACTIVE verification must not resurrect newer EXPIRED snapshot`
  - Trước (T00): **FAIL** (`actual: 'VERIFIED_ACTIVE', expected: 'EXPIRED'`)
  - Sau (T04): **PASS** (CAS phát hiện version conflict, ngăn chặn request ACTIVE cũ ghi đè snapshot EXPIRED mới hơn trong database)

### 2.2. Kiểm Tra Focused Concurrency Suites
- `node --experimental-strip-types --test backend/billing-verifier/test/round3-regression.test.ts`: **7/7 PASS** (Đảm bảo chiều nghịch `late expired cannot overwrite renewal` vẫn tiếp tục xanh)
- `node --experimental-strip-types --test backend/billing-verifier/test/storage-integration.test.ts`: **6/6 PASS**
- `node --experimental-strip-types --test backend/billing-verifier/test/rtdn_and_lifecycle.test.ts`: **6/6 PASS**

---

## 3. Handoff Cho Gói Sau (T05)

- Concurrency và CAS tổng quát hai chiều đã hoạt động đối xứng và hoàn hảo.
- Gói T05 tiếp nhận: Sửa R01 phía backend (Server restore known tokens và freshness):
  - File: `backend/billing-verifier/src/verifier.ts`.
  - Khắc phục probe đỏ: `empty-candidate restore must refresh known lifetime receipt before success`.
  - Trong `restorePurchases`: tập hợp ứng viên candidate set = các bản ghi đã biết trong store của owner + các purchases do client gửi lên (deduplicate theo purchaseToken).
  - Tự động làm mới (refresh) trạng thái đối với các token đã biết bằng cách truy vấn Play API (đặc biệt là in-app lifetime và subscriptions), phát hiện hoàn tiền (`REVOKED`) hoặc hết hạn (`EXPIRED`) ngay cả khi client gửi danh sách `purchases: []`.
- Chuyển tiếp tự động sang T05.
