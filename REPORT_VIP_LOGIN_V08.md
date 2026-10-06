# Báo cáo thực hiện Gói V08: Snapshot an toàn cho sao lưu đám mây

- **Thời gian thực hiện**: 25/09/2026
- **Gói thực hiện**: `V08`
- **Mã kế hoạch tham chiếu**: `PLAN_FIX_VIP_LOGIN_SMALL_MODEL_2026-09-25.md`
- **Trạng thái**: HOÀN THÀNH (14/14 test V08 đạt, toàn bộ 98/98 test liên quan đạt 100%)

---

## 1. Mục tiêu và bối cảnh gói V08

Trước gói V08, cơ chế snapshot sao lưu tài liệu trong `CloudBackupManager.kt` có các lỗ hổng:
1. Xóa mù quáng tệp snapshot có tuổi > 24 giờ mà không kiểm tra trạng thái công việc trong WorkManager, dẫn đến tình huống khi thiết bị ngoại tuyến hoặc worker đang retry backoff, tệp snapshot bị xóa mất và worker thất bại khi thức dậy.
2. Nếu việc tạo snapshot gặp lỗi (ví dụ không đủ dung lượng hoặc lỗi copy), hệ thống ghi log cảnh báo và tiếp tục đưa tệp gốc trực tiếp ("live file") vào WorkManager. Khi người dùng chỉnh sửa tệp gốc song song, dữ liệu tải lên Google Drive bị hỏng hoặc byte không nhất quán.
3. Không có cơ chế gán nhận diện công việc (work identity / tag) cho từng tệp snapshot, gây khó khăn cho việc dọn dẹp an toàn khi công việc bị thay thế hoặc hủy bỏ.

Gói V08 đã giải quyết triệt để các vấn đề trên:
- **Snapshot bắt buộc (Mandatory Immutable Snapshot)**: Chỉ đưa vào hàng đợi WorkManager khi snapshot bất biến đã được tạo thành công và có dung lượng hợp lệ; tuyệt đối không fallback tệp trực tiếp đang hoạt động (live file).
- **Gắn danh tính công việc (Work Identity Binding)**: Mỗi tệp snapshot được gắn thẻ định danh duy nhất `snap_file_${snapshotFileName}` vào `OneTimeWorkRequest` và truyền đường dẫn tuyệt đối qua `KEY_SNAPSHOT_PATH`.
- **Bảo lưu snapshot khi đang hoạt động (Active Retention)**: Tuyệt đối không xóa snapshot của công việc đang chờ (`ENQUEUED`), đang chạy (`RUNNING`) hoặc đang retry backoff (`BLOCKED`/`ENQUEUED`), kể cả khi snapshot đã tồn tại hơn 24 giờ.
- **Fail-safe khi lỗi truy vấn WorkManager**: Khi việc truy vấn trạng thái WorkManager gặp lỗi (database locked, exception), hệ thống bảo lưu snapshot thay vì xóa nhầm ("không coi lỗi truy vấn là bằng chứng công việc đã xong").
- **Dọn dẹp snapshot terminal & thay thế an toàn**: Snapshot của các công việc đã kết thúc (`SUCCEEDED`, `FAILED`, `CANCELLED` hoặc bị thay thế bởi bản sửa đổi mới) được giải phóng an toàn.

---

## 2. Chi tiết các file sửa đổi và tạo mới

### A. Helper mới `app/src/main/java/com/tscanner/app/utils/BackupSnapshotStore.kt`
- Định nghĩa thư mục snapshot: `cache/backup_snapshots`.
- `createSnapshot(context, docId, revision, sourcePdf)`: Tạo bản chụp bất biến với quy trình ghi an toàn (atomic write qua file tạm `.tmp` rồi đổi tên), kiểm tra toàn vẹn byte.
- `deleteSnapshot(snapshotFile)`: Xóa an toàn tệp snapshot khi công việc kết thúc.
- `getSnapshotTag(snapshotFile)`: Tạo tag `snap_file_${name}` gắn vào `WorkRequest`.
- `checkWorkStatus(context, snapshotFile)`: Truy vấn WorkManager để xác định trạng thái công việc (`ACTIVE`, `TERMINAL`, `UNKNOWN`, `QUERY_ERROR`).
- `cleanOrphanSnapshots(context, maxAgeMs)`: Dọn dẹp tệp mồ côi với chính sách an toàn tối đa:
  - `ACTIVE`: Giữ nguyên tuyệt đối (kể cả > 24h).
  - `QUERY_ERROR`: Giữ nguyên (fail-safe).
  - `TERMINAL`: Xóa ngay lập tức.
  - `UNKNOWN`: Chỉ xóa nếu tuổi > 24h (tránh race-condition khi vừa tạo snapshot xong).
  - Tệp `.tmp` tồn đọng > 15 phút: tự động dọn dẹp.

### B. `app/src/main/java/com/tscanner/app/utils/CloudBackupManager.kt`
- Cập nhật `enqueueBackup(context, docItem)`:
  - Kiểm tra điều kiện tệp gốc (`sourcePdf.exists() && sourcePdf.length() > 0`).
  - Gọi `BackupSnapshotStore.cleanOrphanSnapshots(context)` dọn dẹp an toàn trước khi lên lịch mới.
  - Tạo snapshot bắt buộc qua `BackupSnapshotStore.createSnapshot(...)`. Nếu thất bại: ghi log lỗi và hủy bỏ việc enqueue, không fallback file sống.
  - Gắn `KEY_SNAPSHOT_PATH` và tag `BackupSnapshotStore.getSnapshotTag(snapshotFile)` vào `OneTimeWorkRequestBuilder`.
  - Bổ sung `enqueueWork` cùng điểm neo kiểm thử `@VisibleForTesting workEnqueuer` và `workManagerProvider`.

### C. `app/src/main/java/com/tscanner/app/utils/GoogleDriveBackupWorker.kt`
- Tách logic cốt lõi vào `companion object` `performBackup(context, inputData, runAttemptCount)` giúp kiểm thử độc lập mà không cần giả lập nội bộ phức tạp của WorkerParameters.
- Xác thực nghiêm ngặt `KEY_SNAPSHOT_PATH`: Nếu thiếu hoặc tệp không tồn tại -> cập nhật CAS sang `FAILED` và trả về `Result.failure()`.
- Sử dụng tệp snapshot cho toàn bộ quy trình tải lên Google Drive REST API.
- Trong khối `finally`: Chỉ xóa tệp snapshot khi công việc kết thúc dứt điểm (`!isRetrying`). Khi xảy ra lỗi tạm thời (5xx, rate limit) và `runAttemptCount < 3`, cờ `isRetrying = true` giúp snapshot được bảo toàn nguyên vẹn trên đĩa cho lượt retry tiếp theo.

### D. Bộ kiểm thử đơn vị (`app/src/test/java/com/tscanner/app/BackupSnapshotLifecycleTest.kt`)
Xây dựng 14 kịch bản kiểm thử toàn diện:
1. `testEnqueueBackup_whenSourcePdfMissing_abortsWithoutEnqueuingWork`: Tệp nguồn thiếu -> không gọi WorkManager.
2. `testEnqueueBackup_whenSourcePdfZeroBytes_abortsWithoutEnqueuingWork`: Tệp 0 byte -> không gọi WorkManager.
3. `testEnqueueBackup_whenValid_createsSnapshotAndAttachesWorkTagAndSnapshotPath`: Lên lịch thành công gắn đúng tag và đường dẫn snapshot.
4. `testWorker_whenSourceFileModifiedAfterEnqueue_uploadsOriginalSnapshotBytes`: Tệp nguồn bị sửa sau khi enqueue -> worker vẫn tải lên chính xác byte từ snapshot.
5. `testWorker_whenSnapshotPathMissing_abortsImmediatelyWithoutReadingLiveFile`: Thiếu snapshot path -> worker thất bại ngay lập tức, không đọc file sống.
6. `testWorker_onTransientError_retriesAndPreservesSnapshot`: Lỗi tạm thời (503) -> worker retry và giữ nguyên tệp snapshot trên đĩa.
7. `testWorker_onPermanentError_failsAndDeletesSnapshot`: Lỗi vĩnh viễn (400) -> worker fail và dọn dẹp snapshot.
8. `testCleanOrphanSnapshots_whenWorkIsActive_preservesSnapshotEvenOlderThan24Hours`: Công việc đang active (retry) > 27h -> không bị dọn dẹp.
9. `testCleanOrphanSnapshots_whenWorkManagerQueryFails_preservesSnapshot`: Lỗi truy vấn WorkManager -> fail-safe giữ snapshot.
10. `testCleanOrphanSnapshots_whenWorkIsTerminal_cleansUpImmediately`: Công việc đã xong -> snapshot bị xóa ngay.
11. `testCleanOrphanSnapshots_whenUnknownAndYoungerThan24h_preservesFile`: Tệp mới tạo (5 phút) chưa có bản ghi -> được giữ lại.
12. `testCleanOrphanSnapshots_whenUnknownAndOlderThan24h_cleansUpFile`: Tệp mồ côi thật sự > 26h không có bản ghi -> được dọn dẹp.
13. `testCancelAndReplace_cleansUpReplacedSnapshotAndRetainsNewOne`: Thay thế tác vụ cũ -> snapshot cũ bị xóa, snapshot mới được giữ.
14. `testProcessRecreation_retainsDiskSnapshotAndCompletesUpload`: Giả lập khởi động lại tiến trình (kill app) -> khôi phục snapshot từ đĩa và hoàn tất tải lên.

---

## 3. Kết quả kiểm thử

Toàn bộ 98 kiểm thử đơn vị thuộc 11 bộ test (bao gồm V08 và các bộ hồi quy từ V01-V07) đã vượt qua 100%:
- `BackupSnapshotLifecycleTest`: 14 passed (0 failed)
- `PostAuthorizationSyncResultTest`: 11 passed (0 failed)
- `DriveAuthorizationFlowTest`: 8 passed (0 failed)
- `VipLoginContinuationTest`: 13 passed (0 failed)
- `DemoAccountIsolationTest`: 5 passed (0 failed)
- `GoogleIdentityOptionsTest`: 9 passed (0 failed)
- `AppAuthDriveAuthorizationTest`: 4 passed (0 failed)
- `AppAuthCanonicalIdentityTest`: 8 passed (0 failed)
- `GoogleLoginFlowTest`: 10 passed (0 failed)
- `GoogleCredentialRequestFactoryTest`: 5 passed (0 failed)
- `GoogleSignInResultRouterTest`: 11 passed (0 failed)

**Tổng số**: 98 tests executed, 98 passed, 0 failed (100% success rate).
BUILD SUCCESSFUL in 28s.

---

## 4. Tóm tắt bàn giao

Gói V08 đã hoàn thành trọn vẹn và an toàn. Hệ thống đã sẵn sàng cho gói tiếp theo: `V09` (Cổng nghiệm thu tổng thể, kiểm tra lint, assemble debug/release và ma trận kiểm thử thiết bị).
