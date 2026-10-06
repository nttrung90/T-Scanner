# Báo cáo U10 — Các gate thiết bị / môi trường thật (Real Device & Environment Audit)

## 1. Mục tiêu và nguyên tắc
- Mục tiêu: Đánh giá trung thực, khách quan và minh bạch về các kịch bản kiểm thử đòi hỏi môi trường thực tế (thiết bị vật lý, dịch vụ đám mây bên ngoài, tài khoản thanh toán thật).
- Nguyên tắc cốt lõi:
  - **TUYỆT ĐỐI KHÔNG** ngụy tạo hoặc giả mạo kết quả `PASS` cho các kịch bản phụ thuộc hạ tầng thực tế.
  - Phân loại chính xác giữa `PASSED_IN_AUTOMATED_TEST` (đã chứng minh bằng unit/integration test) và `BLOCKED_EXTERNAL` / `NOT_RUN` (cần can thiệp từ môi trường/tài khoản production).
  - Không đọc, không ghi log secret, không deploy/publish, không dùng thẻ thanh toán thật.

## 2. Bảng kiểm kê trạng thái các Gate môi trường thật

| Gate / Kịch bản thực tế | Trạng thái | Chi tiết kỹ thuật & Lý do phân loại |
|---|---|---|
| **Google Play License Tester** | `BLOCKED_EXTERNAL` | Đòi hỏi tài khoản Google đã được cấu hình trong License Testing trên Google Play Console và thiết bị có Google Play Store thật. |
| **OAuth ID Token Acquisition thực tế** | `BLOCKED_EXTERNAL` | Đòi hỏi Google Play Services (GMS) và luồng Google Sign-In SDK tương tác trực tiếp với người dùng trên thiết bị thật. |
| **Google Cloud Pub/Sub RTDN Live Delivery** | `BLOCKED_EXTERNAL` | Đòi hỏi Cloud Pub/Sub topic và subscription trực tiếp trỏ về URL production công khai có chứng chỉ SSL hợp lệ. Cơ chế nhận và giải mã RTDN đã được kiểm thử 100% trong `rtdn_and_lifecycle.test.ts` và `rtdn-recovery.test.ts`. |
| **Persistence Database Restart trên Live Volume** | `PASSED_IN_STORAGE_SUITE` / `NOT_RUN_LIVE` | Đã chứng minh bằng test `Storage Integration: Token ownership and snapshot version survive process restart` trong SQLite driver; việc khởi động lại persistent volume trên hạ tầng đám mây live chưa thực hiện (`NOT_RUN_LIVE`). |
| **Multi-Device Concurrent Purchase / Cross-Device Replay** | `PASSED_IN_SIMULATION` / `BLOCKED_EXTERNAL` | Đã kiểm thử race condition và replay concurrency bằng 5 request song song (`B04a Verifier: Concurrent replay is idempotent without race condition`); kiểm thử trên hai thiết bị vật lý đồng thời yêu cầu 2 thiết bị Android thật. |
| **Google Drive Cloud Backup thật** | `BLOCKED_EXTERNAL` | Đòi hỏi OAuth scope `drive.file` và kết nối Google Drive API trực tiếp. Logic token guard và folder check đã được bao phủ trong suite unit test. |
| **ADB Daemon & Physical Device Connection** | `NOT_RUN` | Lệnh ADB trên máy host không kết nối được thiết bị do không có thiết bị thật hoặc daemon bị chặn (`BLOCKED_EXTERNAL`). |

## 3. Khẳng định giới hạn
- Toàn bộ contract logic, mô hình dữ liệu, cơ chế phân xử đồng thời (CAS expected-absent), parser HTTP nghiêm ngặt, xử lý trạng thái V2 lifecycle, và bảo vệ phiên làm việc người dùng đã được kiểm chứng tự động và cô lập một cách vững chắc.
- Các gate phụ thuộc dịch vụ ngoài được bàn giao minh bạch để kiểm tra nghiệm thu (UAT) khi triển khai trên môi trường staging/production có đầy đủ dịch vụ Google Play.

## 4. Handoff cho Gói Tiếp Theo
- Chuyển tiếp sang gói cuối cùng: **U11 — Báo cáo hoàn tất & bàn giao cuối (REPORT_VIP_R5_FINAL.md & Acceptance Matrix)**.
