# Báo Cáo Triển Khai VIP Vòng 3 — Gói M05: Durable Store, Readiness & Atomic Grant+Ack Outbox (F09, F11)

**Thời điểm:** 26/09/2026  
**Mục tiêu:** Loại bỏ phát hiện F09 và F11, đảm bảo giao dịch cấp quyền (entitlement grant) và ghi nhận outbox acknowledge diễn ra trong cùng một transaction database nguyên tử (atomic), bao phủ cả gói `CANCELED_ACTIVE` còn hạn, bắt buộc đường dẫn lưu trữ bền vững ở production (cấm fallback `:memory:` ngầm định), bổ sung endpoint `/readiness` kiểm tra điều kiện vận hành thực tế.

---

## 1. File Thay Đổi & Tạo Mới

- `backend/billing-verifier/src/storage/sqliteDriver.ts`:
  - Đưa thao tác `ackUpsert` vào bên trong transaction `BEGIN IMMEDIATE ... COMMIT` của `bindOrUpdate`, đảm bảo việc lưu `token_records` và ghi nhận hàng đợi `ack_retry_queue` diễn ra nguyên tử 100%. Triệt tiêu hoàn toàn khoảng trống crash giữa cấp quyền và tạo job ack.
- `backend/billing-verifier/src/storage/types.ts`:
  - Mở rộng `BindOptions` với `ackRequired?: boolean` và `ackItem?: { productId, productType, ownerAppUserId }`.
- `backend/billing-verifier/src/store.ts`:
  - Kiểm tra môi trường: nếu `NODE_ENV=production` mà thiếu `DATABASE_URL` hoặc `SQLITE_PATH`, hoặc cấu hình `:memory:`, hệ thống dừng khởi động ngay lập tức (fail-closed).
  - Thêm phương thức `isDurable(): boolean` phục vụ kiểm tra trạng thái lưu trữ.
- `backend/billing-verifier/src/verifier.ts`:
  - Xác định nhu cầu acknowledge: `(state === 'VERIFIED_ACTIVE' || state === 'CANCELED_ACTIVE' || state === 'IN_GRACE_PERIOD') && acknowledgementState === 0`.
  - Chuyển `ackRequired` và `ackItem` trực tiếp vào lệnh gọi `bindOrUpdate`, loại bỏ lời gọi tách rời trước đây.
- `backend/billing-verifier/src/index.ts`:
  - Bổ sung endpoint `/readiness` (và `/ready`, `/health/ready`), kiểm tra độ bền vững của storage và tính sẵn sàng của auth cấu hình. Trả về HTTP 200 `READY` khi đủ điều kiện, hoặc HTTP 503 `NOT_READY` khi thiếu cấu hình.
- `backend/billing-verifier/test/ack-restart.test.ts`:
  - Bổ sung 2 test case:
    1. `M05: Atomic grant + outbox for CANCELED_ACTIVE unacknowledged receipt and crash recovery`: xác nhận CANCELED_ACTIVE được đưa vào outbox và phục hồi thành công sau crash giả lập.
    2. `M05: HTTP Server /readiness endpoint checks durability and auth readiness`: kiểm tra phản hồi `/readiness`.
- `docs/billing/DEPLOYMENT_GUIDE.md`:
  - Tài liệu hướng dẫn triển khai production, cấu hình biến môi trường, persistent volume contract, health vs readiness probes.
- `REPORT_VIP_R3_M05.md`: Báo cáo nội bộ gói M05.

---

## 2. Bằng Chứng Red -> Green & Thực Thi Thực Tế

### 2.1. Probe Đỏ Cuối Cùng Của Backend Đã Chuyển Xanh
- Test: `canceled active unacknowledged receipt must enter outbox`
  - Trước (M00): **FAIL** (`0 !== 1`)
  - Sau (M05): **PASS** (1 job được enqueue nguyên tử trong transaction)

### 2.2. Kiểm Tra Focused Ack/Restart Suite
- Lệnh: `node --experimental-strip-types --test backend/billing-verifier/test/ack-restart.test.ts`
- Kết quả: **8/8 PASS**, 0 fail, thời gian: ~1057ms.

### 2.3. Kiểm Tra Toàn Bộ Backend Suite (Round 3 Regression + Baseline)
- Lệnh: `node --experimental-strip-types --test backend/billing-verifier/test/*.test.ts`
- Kết quả: **78/78 PASS (100% GREEN)**, 0 failures, 0 errors, thời gian: ~1937ms.
  - Toàn bộ 7/7 probe độc lập trong `round3-regression.test.ts` đều đã **PASS**.
  - Toàn bộ các phát hiện backend (F01, F04 server, F05 server, F06 backend, F08, F09, F11) đã được giải quyết dứt điểm.

---

## 3. Handoff Cho Gói Sau (M06)

- Backend đã hoàn tất toàn bộ các mục tiêu từ M01–M05 và đạt trạng thái 78/78 PASS.
- Gói M06 tiếp nhận: Bắt đầu phần Android:
  - Giải quyết phát hiện F02: nối `PlayPurchaseVerifier` vào vòng đời ứng dụng Android (`TScannerApplication.kt`, `AppAuthManager.kt`, `BillingManager.kt`).
  - Đọc backend URL từ cấu hình môi trường sản phẩm (buildConfig/res), không hardcode URL giả, không bật sandbox grant cho production.
  - Validate nghiêm ngặt URL HTTPS (bác bỏ `http://` như đã chứng minh trong probe `httpEndpointMustNotBeReady`).
  - Gắn Bearer Token của user session và hỗ trợ refresh token theo phiên.
- Chuyển tiếp tự động sang M06.
