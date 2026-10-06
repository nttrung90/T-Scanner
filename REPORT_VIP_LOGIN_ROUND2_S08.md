# BÁO CÁO HOÀN THÀNH GÓI S08 — SNAPSHOT KHÔNG PHỤ THUỘC CACHE DỄ BỊ THU HỒI
*Thời gian thực hiện: 2026-09-25*

---

## 1. Mục tiêu và phạm vi gói S08

Gói **S08** giải quyết triệt để rủi ro mất mát dữ liệu snapshot do hệ điều hành Android tự động thu hồi bộ nhớ cache (OS Cache Eviction) khi thiết bị chịu áp lực bộ nhớ (Storage/Memory Pressure) trong lúc tác vụ backup WorkManager đang chờ mạng (Network Constraint) hoặc đang trong chu kỳ retry (R08 trong `RECHECK_VIP_LOGIN_ROUND2_2026-09-25.md`):

1. **Di dời vị trí lưu trữ Snapshot sang bộ nhớ riêng bền vững (Persistent Storage)**:
   - Chuyển thư mục lưu trữ snapshot từ `context.cacheDir` sang `context.noBackupFilesDir ?: context.filesDir`.
   - Android OS tuyệt đối không bao giờ tự ý dọn dẹp thư mục `noBackupFilesDir` hoặc `filesDir` ngay cả khi bộ nhớ cạn kiệt.
2. **Bảo đảm độ bền dữ liệu khi tạo snapshot (Durable & Atomic Snapshot Publish)**:
   - Sao chép dữ liệu qua stream với `output.flush()` và ép đồng bộ vật lý qua file descriptor `output.fd.sync()`.
   - Sử dụng cơ chế ghi qua file tạm (`.tmp`) rồi thực hiện đổi tên nguyên tử (`tempFile.renameTo(destFile)`), ngăn chặn việc worker đọc phải file snapshot đang ghi dở hoặc hỏng do crash bất ngờ.
3. **Tương thích ngược an toàn cho các WorkRequest cũ (Backward Compatibility)**:
   - Các tác vụ WorkManager đã được enqueue từ phiên bản cũ mang đường dẫn `cacheDir` tuyệt đối vẫn được xử lý an toàn: nếu file tại đường dẫn cũ vẫn còn, worker đọc bình thường; nếu file đã được di chuyển hoặc cần phân giải, worker tự động tìm kiếm `persistentFallback` (theo filename trong thư mục persistent mới) hoặc `legacyFallback`.
4. **Bảo toàn nghiêm ngặt bất biến an toàn — Tuyệt đối không fallback sang file sống**:
   - Nếu file snapshot thực sự bị mất (do OS đã thu hồi cache trước khi nâng cấp gói S08), worker ghi log lỗi rõ ràng, đánh dấu trạng thái tài liệu thành `SyncStatus.FAILED` thông qua Compare-And-Swap (CAS), và trả về `Result.failure()`.
   - **Tuyệt đối không bao giờ** đọc fallback từ `doc.pdfPath` (file sống), đảm bảo không bao giờ đẩy nhầm nội dung revision mới đã bị sửa đổi lên bản sao lưu của revision cũ.
5. **Dọn dẹp snapshot terminal và giữ lại khi retry**:
   - Khi worker kết thúc ở trạng thái terminal (thành công hoặc thất bại vĩnh viễn), dọn dẹp sạch sẽ cả file snapshot resolved lẫn file snapshot gốc (nếu đường dẫn khác nhau).
   - Khi worker gặp lỗi mạng tạm thời và retry (`isRetrying == true`), file snapshot được giữ nguyên vẹn để phục vụ lần thử tiếp theo.
6. **Dọn dẹp triệt để thư mục cache cũ (Legacy Cache Safe Draining)**:
   - Hàm `cleanOrphanSnapshots` quét dọn an toàn cả thư mục persistent mới lẫn thư mục cache cũ, loại bỏ các file snapshot mồ côi sót lại từ các phiên bản trước mà không chạm vào các tác vụ đang chạy.

---

## 2. Chi tiết thay đổi mã nguồn

### 2.1. `app/src/main/java/com/tscanner/app/utils/BackupSnapshotStore.kt`
- **Thêm biến cấu hình Persistent Root**:
  - `var persistentRootOverride: File? = null` phục vụ cô lập môi trường trong unit test.
- **Tách biệt và làm rõ hàm lấy thư mục lưu trữ**:
  - `getSnapshotDir(context)`: Trả về `File(persistentRootOverride ?: (context.noBackupFilesDir ?: context.filesDir), SNAPSHOT_DIR_NAME)`.
  - `getLegacyCacheSnapshotDir(context)`: Trả về `File(context.cacheDir, SNAPSHOT_DIR_NAME)`.
- **Nâng cấp `createSnapshot` bảo đảm tính bền vững (Durability)**:
  - Tạo file tạm `tempFile = File(snapshotDir, "snap_${docId}_rev${revision}_${UUID.randomUUID()}.tmp")`.
  - Stream copy dữ liệu từ `sourceFile` sang `tempFile`, thực hiện gọi `output.flush()` và `output.fd.sync()`.
  - Gọi `tempFile.renameTo(destFile)` để công bố snapshot một cách nguyên tử. Nếu đổi tên thất bại, xóa `tempFile` và trả về `null`.
- **Nâng cấp `cleanOrphanSnapshots` dọn dẹp cả 2 vùng lưu trữ**:
  - Quét dọn các file snapshot mồ côi trong `getSnapshotDir(context)` (persistent storage).
  - Quét dọn các file snapshot mồ côi còn sót lại trong `getLegacyCacheSnapshotDir(context)` (legacy cache storage).
  - Tôn trọng kiểm tra `checkWorkStatus(context, file)`: chỉ xóa khi trạng thái là `DEAD_WORK`, giữ nguyên tuyệt đối khi là `ACTIVE_WORK` hoặc `QUERY_ERROR`.
- **Cập nhật `resetForTesting()`**:
  - Reset `persistentRootOverride = null` sau mỗi ca test.

### 2.2. `app/src/main/java/com/tscanner/app/utils/GoogleDriveBackupWorker.kt`
- **Cơ chế phân giải file snapshot đa tầng**:
  - Khởi tạo `uploadFile = File(snapshotPath)`.
  - Nếu `!uploadFile.exists() || uploadFile.length() == 0L`:
    - Tìm kiếm `persistentFallback = File(BackupSnapshotStore.getSnapshotDir(context), uploadFile.name)`.
    - Tìm kiếm `legacyFallback = File(BackupSnapshotStore.getLegacyCacheSnapshotDir(context), uploadFile.name)`.
    - Nếu tìm thấy file hợp lệ ở fallback, chuyển `uploadFile` sang file fallback đó.
- **Bảo toàn bất biến không đọc file sống**:
  - Nếu sau tất cả các bước phân giải mà file snapshot vẫn không tồn tại hoặc rỗng:
    ```kotlin
    Log.e(TAG, "Snapshot file does not exist or is empty: $snapshotPath. Aborting backup to avoid reading mutating live file.")
    repo.updateSyncStatusCas(docId, ownerId, revision, SyncStatus.FAILED)
    return Result.failure()
    ```
- **Quản lý vòng đời dọn dẹp trong khối `finally`**:
  - Kiểm tra cờ `isRetrying`. Nếu không phải retry (terminal result):
    - Xóa `uploadFile` (file snapshot đã giải quyết).
    - Nếu `snapshotPath != uploadFile.absolutePath`, xóa tiếp file tại `snapshotPath` để tránh rò rỉ file thừa.

---

## 3. Kiểm thử và xác minh (Verification)

### 3.1. Bộ test mới `BackupSnapshotStorageRecoveryTest.kt`
Đã xây dựng bộ unit test toàn diện gồm 8 ca kiểm thử chuyên biệt tại `app/src/test/java/com/tscanner/app/BackupSnapshotStorageRecoveryTest.kt`:

1. `testSnapshotCreation_usesPersistentAppDirectory_andSurvivesCacheEviction`:
   - Xác minh file snapshot được tạo trong `noBackupFilesDir`.
   - Giả lập hệ điều hành xóa sạch toàn bộ thư mục `cacheDir`.
   - Kết quả: File snapshot trong persistent storage vẫn còn nguyên vẹn 100% với nội dung chính xác.
2. `testWorker_readsSnapshotFromLegacyCacheDir_forBackwardCompatibility`:
   - Tạo file snapshot trong thư mục `legacyCacheDir`.
   - WorkRequest chỉ định đường dẫn legacy cache này.
   - Kết quả: Worker đọc thành công từ legacy cache và tải lên đúng dữ liệu lên Google Drive.
3. `testWorker_whenLegacyCachePurgedByOS_failsCleanlyAndDoesNotFallbackToLiveFile`:
   - Giả lập file snapshot trong cache cũ đã bị OS xóa sạch.
   - File tài liệu sống trên đĩa vẫn còn (`doc.pdfPath`).
   - Kết quả: Worker từ chối chạy, trả về `Result.failure()`, cập nhật `SyncStatus.FAILED` qua CAS, và **tuyệt đối không đọc byte nào từ file sống**.
4. `testWorker_abortsObsoleteUpload_whenDocumentRevisionMovedPastEnqueuedRevision`:
   - Tài liệu tại local được chỉnh sửa lên revision 2.
   - Worker nhận lệnh upload cho revision 1.
   - Kết quả: Worker phát hiện revision đã lỗi thời, hủy tác vụ trước khi gọi Drive Uploader, xóa snapshot rác.
5. `testSnapshotCreation_handlesEmptyOrMissingSourceGracefully`:
   - Kiểm tra trường hợp file nguồn không tồn tại hoặc file nguồn rỗng (0 bytes).
   - Kết quả: `createSnapshot` trả về `null` một cách an toàn, không tạo file snapshot rác trên đĩa.
6. `testWorker_cleansUpSnapshotOnTerminalSuccess_butPreservesOnRetry`:
   - Lần 1: Drive API trả về `TransientError(503)`. Worker trả về `Result.retry()` -> File snapshot được **giữ nguyên vẹn**.
   - Lần 2: Drive API trả về `Success`. Worker trả về `Result.success()` -> File snapshot được **xóa sạch sẽ**.
7. `testCleanOrphanSnapshots_cleansBothPersistentAndLegacyCacheDirSafely`:
   - Đặt snapshot rác (không có work active) vào cả thư mục persistent và legacy cache.
   - Chạy `cleanOrphanSnapshots`.
   - Kết quả: Cả 2 thư mục đều được dọn dẹp sạch sẽ các file mồ côi.
8. `testLookupTimeout_preservesSnapshotInBothDirectories_failSafeRetention`:
   - Giả lập WorkManager query gặp timeout/lỗi.
   - Chạy `cleanOrphanSnapshots`.
   - Kết quả: Cơ chế Fail-safe retention bảo vệ toàn bộ snapshot, không có bất kỳ file nào bị xóa nhầm.

### 3.2. Kết quả thực thi kiểm thử

- **Bộ test mới của gói S08**:
  ```powershell
  .\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.BackupSnapshotStorageRecoveryTest --offline --console=plain
  # Kết quả: 8/8 tests passed (100%), BUILD SUCCESSFUL
  ```
- **Hồi quy các gói liên quan (Lifecycle & Dispatch)**:
  ```powershell
  .\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.BackupSnapshotLifecycleTest --tests com.tscanner.app.CloudBackupDispatchTest --offline --console=plain
  # Kết quả: 100% tests passed
  ```
- **Toàn bộ Test Suite của dự án**:
  ```powershell
  .\gradlew.bat :app:testDebugUnitTest --offline --console=plain
  # Kết quả: 638/638 tests passed (100%), 0 Failures, 0 Skipped
  ```

---

## 4. Tuyên bố nghiệm thu gói S08

- [x] Snapshot mới được tạo hoàn toàn trong thư mục persistent (`noBackupFilesDir ?: filesDir`), không bao giờ bị OS cache eviction xóa mất khi máy chịu áp lực bộ nhớ.
- [x] Quá trình ghi snapshot đạt chuẩn Durable & Atomic (flush, sync file descriptor, atomic rename).
- [x] Đảm bảo 100% Backward Compatibility: các WorkRequest cũ trỏ vào cache vẫn được resolve an toàn qua cơ chế fallback đa tầng.
- [x] Đảm bảo Strict Invariant: nếu snapshot bị mất trước đó, worker fail an toàn, cập nhật CAS FAILED, tuyệt đối không bao giờ fallback đọc file sống (`doc.pdfPath`).
- [x] Quản lý vòng đời chuẩn xác: Giữ snapshot khi retry, dọn dẹp sạch sẽ khi terminal (success/failure).
- [x] Dọn dẹp an toàn cả thư mục persistent lẫn legacy cache mà không ảnh hưởng đến tác vụ đang hoạt động.
- [x] Toàn bộ **638/638 unit tests** của toàn dự án đều PASS xanh.
