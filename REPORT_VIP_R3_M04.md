# Báo Cáo Triển Khai VIP Vòng 3 — Gói M04: Serialize/CAS Trạng Thái & RTDN Bền Vững (F08)

**Thời điểm:** 26/09/2026  
**Mục tiêu:** Loại bỏ phát hiện F08 (race condition giữa RTDN và Verify, tăng version nhưng không bảo vệ thứ tự nghiệp vụ), triển khai cơ chế Optimistic Concurrency Control (CAS) theo `expectedVersion` và lưu trữ bền vững watermark thời gian sự kiện (`last_event_time_millis`) trong cơ sở dữ liệu SQLite.

---

## 1. File Thay Đổi & Tạo Mới

- `backend/billing-verifier/src/storage/schema.ts`:
  - Bổ sung cột `last_event_time_millis INTEGER DEFAULT 0` vào bảng `token_records` để lưu trữ watermark sự kiện RTDN bền vững xuyên suốt qua restart và nhiều process.
- `backend/billing-verifier/src/storage/types.ts`:
  - Bổ sung `staleIgnored?: boolean` vào `BindResult` và định nghĩa interface `BindOptions { eventTimeMillis?: number; expectedVersion?: number }`.
- `backend/billing-verifier/src/storage/sqliteDriver.ts`:
  - Tự động chạy migration idempotent bổ sung cột `last_event_time_millis` nếu bảng đã tồn tại.
  - Kiểm tra thứ tự sự kiện RTDN: nếu `eventTimeMillis <= last_event_time_millis`, từ chối ghi đè trạng thái cũ hơn và trả về `{ success: true, record: existingRecord, staleIgnored: true }`.
  - Triển khai CAS `expectedVersion`: nếu phiên bản trong database đã được cập nhật bởi một giao dịch mới hơn (ví dụ gói vừa được gia hạn thành công `VERIFIED_ACTIVE`), một lời gọi `verify` cũ vừa trở về với trạng thái `EXPIRED` sẽ bị chặn đứng, không được phép hạ cấp trạng thái đã gia hạn mới hơn.
- `backend/billing-verifier/src/store.ts`:
  - Chuyển tiếp tham số `options?: BindOptions` tới storage driver.
- `backend/billing-verifier/src/rtdnHandler.ts`:
  - Tái kiểm tra `eventTime` sau khi hoàn tất lệnh gọi Play API (`await this.googlePlayApi.getSubscription(...)`), chặn đứng việc callback cũ hoàn thành sau ghi đè kết quả của callback mới hơn.
  - Sử dụng `Math.max` cập nhật in-memory watermark và truyền `eventTimeMillis` vào database để đảm bảo tính bền vững.
- `backend/billing-verifier/src/verifier.ts`:
  - Thu thập `expectedVersion` trước khi truy vấn Play API và truyền vào `bindOrUpdate`.
  - Nếu phát hiện `staleIgnored`, giữ nguyên trạng thái `VERIFIED_ACTIVE` của bản ghi đã được commit mới hơn thay vì báo hết hạn sai.
- `backend/billing-verifier/test/rtdn-recovery.test.ts`:
  - Bổ sung kiểm thử `M04: Durable event watermark survives store restart and rejects older events`: chứng minh watermark sự kiện lưu trong database vật lý, khi restart server sự kiện cũ hơn vẫn bị loại bỏ (`SKIPPED_STALE`).
- `REPORT_VIP_R3_M04.md`: Báo cáo nội bộ gói M04.

---

## 2. Bằng Chứng Red -> Green & Thực Thi Thực Tế

### 2.1. Hai Probe Đỏ M00 Chuyển Xanh
1. Test: `out-of-order RTDN completion must not override newer event`
   - Trước (M00): **FAIL** (`actual: 'VERIFIED_ACTIVE', expected: 'REVOKED'`)
   - Sau (M04): **PASS** (Sự kiện gia hạn cũ đến sau bị chặn, trạng thái `REVOKED` mới hơn được bảo toàn)
2. Test: `late expired verification cannot overwrite newer renewal`
   - Trước (M00): **FAIL** (`actual: 'EXPIRED', expected: 'VERIFIED_ACTIVE'`)
   - Sau (M04): **PASS** (CAS phát hiện version conflict, ngăn chặn kết quả expired cũ hạ cấp bản ghi đã gia hạn)

### 2.2. Kiểm Tra Focused Concurrency/RTDN Suites
- `node --experimental-strip-types --test backend/billing-verifier/test/rtdn-recovery.test.ts`: **6/6 PASS**
- `node --experimental-strip-types --test backend/billing-verifier/test/rtdn_and_lifecycle.test.ts`: **6/6 PASS**
- `node --experimental-strip-types --test backend/billing-verifier/test/storage-integration.test.ts`: **6/6 PASS**

### 2.3. Toàn Bộ Backend Test Suite
- Lệnh: `node --experimental-strip-types --test backend/billing-verifier/test/*.test.ts`
- Kết quả: **75/76 PASS** (1 fail duy nhất còn lại là probe outbox F09 thuộc gói M05).

---

## 3. Handoff Cho Gói Sau (M05)

- Cơ chế CAS và thứ tự sự kiện RTDN bền vững đã được tích hợp hoàn chỉnh.
- Gói M05 tiếp nhận: Xử lý F09 và F11:
  - Sửa probe đỏ cuối cùng của backend: `canceled active unacknowledged receipt must enter outbox` (bao phủ mọi receipt hợp lệ cần ack bao gồm `CANCELED_ACTIVE` còn hạn mà chưa acknowledge).
  - Đảm bảo commit entitlement và enqueue outbox diễn ra trong cùng một transaction database nguyên tử (atomic).
  - Bắt buộc cấu hình storage durable ở production; fail closed nếu thiếu cấu hình hoặc ngầm định chạy memory; bổ sung readiness endpoint kiểm tra thực tế.
- Chuyển tiếp tự động sang M05.
