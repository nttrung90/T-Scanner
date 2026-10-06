# Báo Cáo Triển Khai VIP Vòng 3 — Gói M10: Gate VIP/Session Của Drive Worker Sau Chờ (F10)

**Thời điểm:** 26/09/2026  
**Mục tiêu:** Loại bỏ phát hiện F10, bổ sung các chốt kiểm tra (checkpoints) quyền VIP và thế hệ phiên xác thực (`sessionGeneration`) trong `GoogleDriveBackupWorker` ngay sau khi chờ lấy token OAuth từ Google và sau khi tìm kiếm/tạo thư mục Drive, ngăn chặn việc tải dữ liệu lên Drive khi quyền VIP đã bị thu hồi hoặc người dùng đã đổi phiên/đăng xuất trong lúc chờ.

---

## 1. File Thay Đổi & Tạo Mới

- `app/src/main/java/com/tscanner/app/utils/GoogleDriveBackupWorker.kt`:
  - Ghi nhận `initialSessionGen = AppAuthManager.getSessionGeneration()` ngay khi bắt đầu tiến trình sao lưu.
  - **Checkpoint 3A (sau khi lấy token OAuth):** Tái kiểm tra `!AppAuthManager.isUserVip()`. Nếu quyền VIP đã bị thu hồi trong khi chờ OAuth, lập tức dừng upload, cập nhật trạng thái `LOCAL_ONLY` qua CAS và trả về `Result.success()`. Đồng thời kiểm tra nếu người dùng đã đăng xuất hoặc thế hệ phiên thay đổi thì hủy sao lưu ngay lập tức.
  - **Checkpoint 4A (sau khi tìm/tạo thư mục Drive, ngay trước upload):** Tái kiểm tra quyền VIP và thế hệ phiên trước khi thực hiện bất kỳ lệnh gửi request mạng (remote mutation) nào lên Google Drive.
  - **Checkpoint 5A (ngay trước commit cục bộ):** Kiểm tra lại thế hệ phiên trước khi cập nhật `SyncStatus.SYNCED`. Nếu người dùng đã đổi tài khoản trong lúc upload đang chạy, từ chối đánh dấu `SYNCED` sai phiên.
- `REPORT_VIP_R3_M10.md`: Báo cáo nội bộ gói M10.

---

## 2. Bằng Chứng Red -> Green & Thực Thi Thực Tế

### 2.1. Probe Đỏ Cuối Cùng Của Android Đã Chuyển Xanh
- Test: `com.tscanner.app.VipRound3RegressionTest.driveWorkerMustRecheckVipAfterTokenWait`
  - Trước (M00): **FAIL** (Quyền VIP bị thu hồi trong khi chờ token, nhưng lệnh upload vẫn được gọi 1 lần: `expected: <0> but was: <1>`)
  - Sau (M10): **PASS** (Checkpoint 3A phát hiện VIP đã bị thu hồi sau khi chờ token, chặn đứng hoàn toàn lệnh upload: `uploads == 0`)

### 2.2. Kiểm Tra Toàn Bộ Bộ Test Android Permanent Regression
- Lệnh: `.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipRound3RegressionTest --offline --console=plain`
- Kết quả: **BUILD SUCCESSFUL**, **9/9 PASS (100% GREEN)**, 0 failures, 0 errors, thời gian: ~4s.

---

## 3. Handoff Cho Gói Sau (M11)

- Toàn bộ 9/9 permanent regression probes trên Android và 7/7 permanent regression probes trên Backend đều đã **CHUYỂN XANH 100%**.
- Gói M11 tiếp nhận: Chạy toàn bộ test suites tích hợp của cả Android và Backend, chạy build/lint, đối soát toàn bộ ma trận F01–F12, đính chính các báo cáo lịch sử và chuẩn bị bảng đối soát release gates.
- Chuyển tiếp tự động sang M11.
