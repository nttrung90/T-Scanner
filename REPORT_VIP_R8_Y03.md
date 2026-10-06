# Báo cáo Kiểm chứng VIP Vòng 8 — Gói Y03 (Check Bind/Outbox Result Trong Unknown RTDN — G03)

**Ngày thực hiện:** 01/10/2026  
**Trạng thái:** HOÀN THÀNH (DONE) — Tự động chuyển sang Y04  
**Lỗi giải quyết:** G03 (P2: Unknown canceled token bỏ qua bind failure nhưng báo processed)

---

## 1. Mục tiêu & Nguyên nhân gốc (Root Cause)

- **Hiện tượng lỗi (Pre-fix):**
  - Khi một worker xử lý unknown token có linked purchase, nếu thao tác `store.bindOrUpdate` bị từ chối (ví dụ: lỗi storage, CAS conflict hoặc owner conflict), handler trước đây bỏ qua kết quả trả về, vẫn đánh dấu event watermark và trả `status: 'PROCESSED'`.
  - Hậu quả: Khi webhook gửi lại (redelivery), hệ thống coi như event đã hoàn tất (`SKIPPED_STALE`) trong khi thực tế tombstone hoặc entitlement mới chưa từng được lưu vào database.
- **Giải pháp (Fix):**
  - Kiểm tra toàn diện kết quả `bindResult` sau khi gọi `bindOrUpdate`:
    - `bindResult.casConflict`: Xử lý tình huống concurrent worker vừa bind token này trong lúc Play query đang diễn ra. Đọc lại record mới (`store.getRecordByToken`), kiểm tra tính toàn vẹn owner (từ chối nếu owner khác nhau), và thực hiện retry có version CAS hợp lệ.
    - `bindResult.conflictOwner`: Phát hiện xung đột quyền sở hữu rõ ràng và trả về lỗi có phân loại typed error (`Ownership conflict`).
    - `bindResult.staleIgnored`: Trả về `SKIPPED_STALE` có bảo lưu logic.
    - Thất bại thông thường: Trả về `status: 'ERROR'`, không cập nhật memory watermark `lastProcessedEventTimes`, giữ event có thể retry an toàn.
  - Bảo vệ bước ghi nhận ack outbox: Nếu `store.enqueueAckRetry` thất bại, trả về `status: 'ERROR'` để không đánh dấu event là đã hoàn tất khi persistent outbox chưa được lưu.

---

## 2. File thay đổi

- `backend/billing-verifier/src/rtdnHandler.ts`: Xử lý chi tiết các nhánh `casConflict`, `conflictOwner`, `staleIgnored` và lỗi outbox enqueue cho unknown RTDN tokens.
- `backend/billing-verifier/test/round8-regression.test.ts`: Bổ sung regression tests kiểm tra hành vi bind rejection và CAS handling.

---

## 3. Kết quả kiểm tra Regression & Acceptance

| Bộ kiểm tra / Test Case | Kết quả trước sửa | Kết quả sau sửa | Ghi chú |
|---|---|---|---|
| `B805`: Unknown canceled-token storage rejection | **FAIL** (claim PROCESSED, redelivery SKIPPED_STALE) | **PASS** | Bind rejection trả ERROR; retry ghi nhận REVOKED thành công |
| Full backend baseline (14 suites, 108 tests) | 108 PASS | **108 PASS** | Không regression |
| Original Round 7 backend probes (9 tests) | 9 PASS | **9 PASS** | B701–B711 giữ vững |

---

## 4. Bước kế tiếp

Tự động chuyển sang gói **Y04** (Backend một final outcome cho mỗi receipt — G04 backend).
