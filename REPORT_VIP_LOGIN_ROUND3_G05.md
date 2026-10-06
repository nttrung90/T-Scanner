# BÁO CÁO GÓI G05 — REPOSITORY KHÔNG CHẠY BACKUP BLOCKING TRONG CALLBACK UI (T05/P2)

## 1. Baseline & Status trước khi thực hiện
- **Baseline:** Các gói G00 - G04 đã giải quyết xong T01 - T04. Toàn bộ 20 tests trong `VipLoginRound3RegressionTest` xanh.
- **Vấn đề T05:** Trong `DocumentRepo.kt`, tại 3 vị trí kích hoạt sao lưu đám mây (`addDocument`: line 299, `renameDocument`: line 421, `markDocumentModified`: line 714), mã nguồn gọi trực tiếp phương thức đồng bộ `CloudBackupManager.enqueueBackup(context, updated)` bên trong khối đồng bộ `@Synchronized`. Phương thức này thực thi đồng bộ việc dọn orphan snapshot, copy toàn bộ file PDF (có thể lên tới hàng chục MB) vào snapshot directory kèm `fd.sync()`, và gọi WorkManager SQLite enqueue. Khi người dùng thao tác đổi tên tài liệu từ `HomeFragment` hoặc `FilesFragment`, luồng UI bị block và toàn bộ các luồng khác truy cập `DocumentRepo` đều bị chặn do kẹt monitor của repository.

## 2. File thay đổi
- `app/src/main/java/com/tscanner/app/utils/CloudBackupManager.kt`:
  - Cập nhật `enqueueBackupAsync` hỗ trợ tham số bất biến tùy chọn `expectedUserId` và `expectedSessionGen`.
  - Bảo đảm snapshot phiên làm việc được chụp chính xác tại thời điểm dispatch, đồng thời duy trì các guard xác thực phiên trước khi backup và sau khi copy snapshot.
- `app/src/main/java/com/tscanner/app/data/repository/DocumentRepo.kt`:
  - Thay thế 3 lệnh gọi đồng bộ `CloudBackupManager.enqueueBackup` tại `addDocument`, `renameDocument`, `markDocumentModified` bằng phương thức điều phối bất đồng bộ an toàn `dispatchCloudBackup(docItem)`.
  - Trong `dispatchCloudBackup`:
    - Lưu dữ liệu (`saveData()`) và phát thông báo (`publishDocuments()`) hoàn tất trước dưới khóa đồng bộ của repository.
    - Lấy snapshot bất biến của người dùng và session generation hiện hành.
    - Chuyển toàn bộ các tác vụ nặng (dọn dẹp orphan, copy PDF snapshot, WorkManager enqueue) ra ngoài monitor của repository và chạy trên `ioDispatcher` (mặc định `Dispatchers.IO`).
    - Bổ sung `@VisibleForTesting var autoBackupEnabled: Boolean = true`, `backupDispatcher` và `backupScope` để kiểm soát môi trường kiểm thử.
    - Lỗi enqueue không biến việc đổi tên/sửa đổi đã lưu thành thất bại giả; trạng thái `syncStatus = SyncStatus.LOCAL_ONLY` và `isSynced = false` được bảo toàn để tự động thử lại khi có kết nối/sync sau.
- `app/src/test/java/com/tscanner/app/CloudBackupDispatchTest.kt`:
  - Trong `setUp()`, tắt `autoBackupEnabled = false` của `DocumentRepo` để việc tạo dữ liệu mẫu không phát sinh background dispatch gây nhiễu các ca test batch enqueue chuyên biệt của `CloudBackupManager`.
- `app/src/test/java/com/tscanner/app/DocumentRepoBackupDispatchTest.kt` (Test mới):
  - `regressionRenameDocument_doesNotBlockCallerOrHoldMonitorDuringBackup`: Dùng latch chặn background IO, chứng minh `repo.renameDocument` trả về `true` ngay lập tức và luồng khác truy cập monitor `DocumentRepo` tức thì mà không bị block.
  - `regressionSessionSwitchWhileBackupQueued_doesNotEnqueueOldUserWork`: Chứng minh khi phiên thay đổi (đăng xuất/chuyển user) trong lúc backup đang xếp hàng, tác vụ backup của user cũ bị hủy bỏ an toàn, không enqueue nhầm sang user mới.
  - `regressionSaveFailure_doesNotDispatchBackup`: Khi `saveData` thất bại (lỗi ổ đĩa/storage), repository trả về `false` và hoàn toàn không dispatch backup.
  - `regressionMarkDocumentModified_enqueuesCorrectRevisionAndBytes`: Chứng minh sửa đổi tài liệu enqueue đúng revision tăng dần và đúng kích thước byte.

## 3. Production paths được test
- `DocumentRepo.renameDocument` thực tế với VIP user và file PDF.
- `DocumentRepo.markDocumentModified` với cập nhật kích thước byte và revision.
- `DocumentRepo.addDocument` với auto-backup trên background IO.
- Cơ chế giải phóng monitor của `DocumentRepo` ngay lập tức khi gọi từ UI.

## 4. Lỗi trước/sau và lệnh chạy
- **Lệnh thực thi:**
  ```powershell
  $env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
  .\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.DocumentRepoBackupDispatchTest --tests com.tscanner.app.CloudBackupDispatchTest --offline --console=plain
  ```
- **Kết quả:** `BUILD SUCCESSFUL` (10 tests completed, 0 failures).
- **Kiểm tra hồi quy Repository:**
  - `.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.DocumentRevisionCasTest --tests com.tscanner.app.DocumentMetadataSyncAndTombstoneTest --tests com.tscanner.app.DocumentCatalogSyncAndConflictTest --tests com.tscanner.app.DocumentRepoAccountIsolationTest --offline --console=plain` → `BUILD SUCCESSFUL` (0 failures).

## 5. Regression còn đỏ cho các gói tiếp theo
- G06: T06/P2 — Thao tác retry/cấp quyền thực sự trong `SyncResultPresenter`.

## 6. Runtime chưa chạy
- Đo lường độ trễ (latency/jank) trên giao diện thực tế khi đổi tên tài liệu PDF dung lượng lớn trên thiết bị: **NOT RUN** (thiếu thiết bị ADB).
- Lưu ý: Không tuyên bố cả repository hoàn toàn không có I/O trên UI vì `saveData()` lưu file JSON cục bộ vẫn thuộc phạm vi đồng bộ của repository.

## 7. Điểm dừng gói G05
Hoàn tất G05. Toàn bộ các side effect sao lưu đám mây nặng (copy PDF snapshot, WorkManager) đã được tách khỏi monitor repository và chuyển sang IO bất đồng bộ.
