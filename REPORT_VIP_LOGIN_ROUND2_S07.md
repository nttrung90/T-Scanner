# BÁO CÁO HOÀN THÀNH GÓI S07 — KHÔNG BLOCK UI VÌ SNAPSHOT/WORKMANAGER
*Thời gian thực hiện: 2026-09-25*

---

## 1. Mục tiêu và phạm vi gói S07

Gói **S07** giải quyết triệt để rủi ro nghẽn luồng giao diện người dùng (UI blocking / ANR risk) phát sinh từ các thao tác I/O đồng bộ trong luồng hậu login/consent và nút sync danh sách tài liệu:
1. **Triệt tiêu block UI tại `AppAuthManager.runPostAuthorizationSync`**: Không còn thực hiện truy vấn Room DB (`repo.getUnsyncedDocuments`) và sao chép file snapshot đồng bộ trên UI calling thread.
2. **Loại bỏ quét dọn lặp trong batch**: `CloudBackupManager.enqueueBatchBackup` chỉ chạy `BackupSnapshotStore.cleanOrphanSnapshots(context)` đúng **1 lần duy nhất** cho toàn bộ lô tài liệu thay vì quét N lần.
3. **Giới hạn thời gian truy vấn WorkManager**: Thêm timeout 3 giây (`WORK_QUERY_TIMEOUT_SECONDS`) cho `Future.get()` trong `checkWorkStatus`. Khi timeout hoặc lỗi truy vấn, future được cancel ngay và trả về `WorkSnapshotStatus.QUERY_ERROR` với cơ chế fail-safe retention (tuyệt đối không xóa file snapshot).
4. **Bảo vệ ranh giới tài khoản/phiên (Anti-Leak Protection)**: Ghi nhận `expectedUserId` và `expectedSessionGen` trước và sau khi copy snapshot. Nếu tài khoản đăng xuất hoặc đổi sang người dùng khác trong lúc file PDF đang copy, file snapshot nháp bị xóa lập tức và hủy enqueue WorkManager (ngăn chặn rò rỉ tài liệu người dùng A sang Drive của người dùng B).
5. **Chuyển đổi nút sync tại `DocumentAdapter`**: Cập nhật click listener tại dòng 86 sang `CloudBackupManager.enqueueBackupAsync`.

---

## 2. Chi tiết thay đổi mã nguồn

### 2.1. `app/src/main/java/com/tscanner/app/utils/BackupSnapshotStore.kt`
- Thêm hằng số `const val WORK_QUERY_TIMEOUT_SECONDS = 3L`.
- Cập nhật `checkWorkStatus(context, snapshotFile)`:
  - Cho phép inject `workQueryFutureProvider` phục vụ unit test.
  - Gọi `workInfosFuture.get(WORK_QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)`.
  - Bắt `TimeoutException`: ghi warning log, gọi `workInfosFuture.cancel(true)` và trả về `WorkSnapshotStatus.QUERY_ERROR`.
- `cleanOrphanSnapshots`: Giữ nguyên và củng cố nhánh `WorkSnapshotStatus.QUERY_ERROR` (fail-safe retention — không xóa snapshot khi lookup lỗi/timeout). Hỗ trợ `orphanCleanupInterceptor` và `snapshotCopyHook` cho kiểm thử.

### 2.2. `app/src/main/java/com/tscanner/app/utils/CloudBackupManager.kt`
- **Tối ưu Batch Cleanup**:
  - `enqueueBatchBackup` gọi `BackupSnapshotStore.cleanOrphanSnapshots(context)` đúng 1 lần trước vòng lặp.
  - Truyền `skipCleanup = true` vào từng lệnh gọi `enqueueBackup`.
  - Kiểm tra `curUser?.id != targetExpectedUser || curSession != targetExpectedSession || curUser?.isVipActive != true` trước mỗi phần tử trong lô để ngắt sớm nếu phiên thay đổi.
- **Tách biệt Enqueue Đồng bộ và Bất đồng bộ**:
  - `enqueueBackup` bổ sung các tham số `skipCleanup: Boolean = false`, `expectedUserId: String? = null`, `expectedSessionGen: Long? = null`.
  - Recheck sau khi copy snapshot: nếu `postCopyUser?.id != targetExpectedUser || postCopySessionGen != targetExpectedSession || postCopyUser?.isVipActive != true`, gọi `BackupSnapshotStore.deleteSnapshot(snapshotFile)` và `return` mà không enqueue work.
  - Bổ sung hàm `enqueueBackupAsync(context, docItem, coroutineScope, ioDispatcher): Job` chạy trên session scope và `Dispatchers.IO` dành riêng cho UI callers.
- Mở rộng phạm vi `getOrCreateSessionScope(): CoroutineScope` thành `internal` với `ioDispatcher` cấu hình được.

### 2.3. `app/src/main/java/com/tscanner/app/utils/AppAuthManager.kt`
- Tái cấu trúc `runPostAuthorizationSync`:
  - **Preconditions**: Kiểm tra trạng thái đăng nhập, trạng thái VIP và quyền Drive tức thì trên calling thread. Nếu không thỏa mãn, trả về `SyncCatalogResult.Skipped` hoặc `AuthRequired` ngay lập tức mà không dispatch I/O.
  - **I/O Dispatch**: Nếu thỏa mãn, bắt lấy `expectedUserId = user.id` và `expectedSessionGen = sessionGeneration.get()`, sau đó dispatch toàn bộ công việc truy vấn Room DB (`repo.getUnsyncedDocuments`), `enqueueBatchBackup` và `syncCatalogFromDriveWithResult` lên `CloudBackupManager.getOrCreateSessionScope().launch(ioDispatcher)`.
  - Khởi tạo quá trình hoàn toàn bất đồng bộ, UI calling thread thoát ngay lập tức (< 20ms) mà không bị block.
- Thêm `postAuthSyncDispatcher` và `setCurrentUserForTesting`.

### 2.4. `app/src/main/java/com/tscanner/app/ui/adapter/DocumentAdapter.kt`
- Dòng 86: Chuyển đổi từ `CloudBackupManager.enqueueBackup(binding.root.context, item)` sang `CloudBackupManager.enqueueBackupAsync(binding.root.context, item)`.

---

## 3. Kiểm thử và xác minh (Verification)

### 3.1. Bộ test mới `CloudBackupDispatchTest.kt`
Tạo mới file test `app/src/test/java/com/tscanner/app/CloudBackupDispatchTest.kt` với 6 ca kiểm thử chuyên sâu:
1. `testRunPostAuthorizationSync_doesNotBlockCallerThread_whenSnapshotCopySlow`:
   - Dùng `CountDownLatch` giữ worker ở bước copy snapshot.
   - Gọi `AppAuthManager.runPostAuthorizationSync` trên calling thread: trả về trong thời gian < 1000ms (~15ms), chứng minh calling thread không hề bị block.
   - Thả latch để worker nền hoàn tất bình thường.
2. `testEnqueueBackupAsync_doesNotBlockCallerThread_whenSnapshotCopySlow`:
   - Dùng `CountDownLatch` giữ worker ở bước copy snapshot.
   - Gọi `CloudBackupManager.enqueueBackupAsync`: trả về ngay lập tức, trả về `Job` đang active.
3. `testEnqueueBatchBackup_performsOrphanCleanupOnlyOnceForMultipleFiles`:
   - Đưa vào 5 tài liệu chưa sync.
   - Đếm số lần gọi `cleanOrphanSnapshots` thông qua interceptor: kết quả đúng **1 lần** (thay vì 5 lần như trước). Cả 5 tài liệu đều được enqueue thành công.
4. `testEnqueueBackup_abortsAndDeletesSnapshot_whenUserSwitchesDuringCopy`:
   - Người dùng Alice đang thực hiện backup.
   - Tại hook copy snapshot, hoán đổi người dùng đăng nhập sang Bob.
   - Kết quả: Không có bất kỳ `OneTimeWorkRequest` nào được enqueue (`assertNull`). File snapshot vừa copy bị xóa sạch khỏi thư mục snapshot (`assertEquals(0, snapshotFiles.size)`).
5. `testCheckWorkStatus_returnsQueryErrorAndRetainsSnapshot_whenFutureTimesOut`:
   - Giả lập `ListenableFuture` ném `TimeoutException` khi gọi `get(3, TimeUnit.SECONDS)`.
   - Kết quả: `checkWorkStatus` trả về `WorkSnapshotStatus.QUERY_ERROR`, `workInfosFuture.cancel(true)` được gọi. Quá trình `cleanOrphanSnapshots` giữ nguyên file (fail-safe retention).
6. `testRunPostAuthorizationSync_abortsWhenSessionInvalidated`:
   - Hủy session scope trước khi dispatch.
   - Kết quả: tiến trình tự hủy sớm, không enqueue bất kỳ tác vụ nào.

### 3.2. Kết quả chạy kiểm thử toàn bộ dự án
- Kiểm thử gói S07:
  ```powershell
  .\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.CloudBackupDispatchTest --offline --console=plain
  # Kết quả: 6/6 tests passed (100%)
  ```
- Kiểm thử hồi quy các gói liên quan:
  ```powershell
  .\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.BackupSnapshotLifecycleTest --tests com.tscanner.app.PostAuthorizationSyncResultTest --offline --console=plain
  # Kết quả: 100% tests passed
  ```
- Kiểm thử toàn bộ unit test suite:
  ```powershell
  .\gradlew.bat :app:testDebugUnitTest --offline --console=plain
  # Kết quả: 630/630 tests passed (100%), 0 failures, 0 skipped
  ```

---

## 4. Tuyên bố nghiệm thu gói S07

- [x] Đã chứng minh bằng latch rằng lời gọi UI tại `runPostAuthorizationSync` và `enqueueBackupAsync` không bị block khi lookup/copy PDF chậm.
- [x] Đã chuyển toàn bộ I/O nặng hậu login/consent sang `Dispatchers.IO` trên session scope có vòng đời quản lý rõ ràng.
- [x] Đã giảm số lần quét dọn orphan snapshot trong `enqueueBatchBackup` từ N lần xuống đúng 1 lần cho cả batch.
- [x] Đã áp dụng timeout 3s và cancellation cho WorkManager query future; đảm bảo query timeout/error không làm mất file snapshot (fail-safe retention).
- [x] Đã bảo vệ chuyển đổi async không làm tài liệu của user A bị đẩy nhầm vào Drive của user B khi đổi account hoặc logout trong lúc copy snapshot.
- [x] Không còn lệnh gọi `enqueueBackup` đồng bộ trực tiếp trên UI thread tại các luồng đã sửa đổi.
- [x] Toàn bộ 630 unit tests trong dự án đều PASS xanh.
