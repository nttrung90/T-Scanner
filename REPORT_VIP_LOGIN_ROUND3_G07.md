# BÁO CÁO GÓI G07 — TỔNG HỢP VÒNG 3 (HOST, REGRESSION & THIẾT BỊ)

## 1. Kết quả thực thi lệnh kiểm thử & tiêu chuẩn chất lượng (Host Verification)

- **Lệnh thực thi toàn bộ:**
  ```powershell
  $env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
  .\gradlew.bat :app:testDebugUnitTest --rerun :app:lintDebug :app:assembleDebug --offline --console=plain
  ```
- **Kết quả Unit Tests:**
  - **Tổng số tests thực tế:** **668 tests** (Baseline cũ: 638 tests; đã bổ sung **30 tests mới**).
  - **Thất bại (Failures):** 0
  - **Lỗi (Errors):** 0
  - **Bỏ qua (Skipped):** 0
  - **Trạng thái:** **100% BUILD SUCCESSFUL** (32s).
- **Kết quả Lint (`:app:lintDebug`):**
  - **Errors:** 0
  - **Warnings:** 728 (Đúng tuyệt đối theo baseline của dự án).
  - **Trạng thái:** **BUILD SUCCESSFUL** (2m 16s).
- **Kết quả Build (`:app:assembleDebug`):**
  - **Trạng thái:** **BUILD SUCCESSFUL** (6s).

---

## 2. Tổng kết nghiệm thu 6 lỗi vòng 3 (T01 – T06)

| Mã lỗi | Mức độ | Mô tả lỗi | Gói sửa | Kết quả Probe / Host | Phân loại |
|---|---|---|---|---|---|
| **T01** | P1 | Token tái tạo hợp lệ nhưng không giải phóng khóa đăng nhập sau recreate | G01 | Probe T01 + 3 regression tests xanh | **PROBE PASS / CODE VERIFIED** |
| **T02** | P1 | Kết quả thiếu token của host mượn attempt hiện hành từ singleton | G02 | Probe T02 + 3 regression tests xanh | **PROBE PASS / CODE VERIFIED** |
| **T03** | P2 | Drive token qua serialization consume 2 lần do biến transient thiếu registry | G04 | Probe T03 + 4 regression tests xanh | **PROBE PASS / CODE VERIFIED** |
| **T04** | P1 | Logout A trễ xóa user B mới đăng nhập | G03 | Probe T04 + 3 regression tests xanh | **PROBE PASS / CODE VERIFIED** |
| **T05** | P2 | Repository chạy backup blocking trong monitor đồng bộ khi đổi tên trên UI | G05 | 4 regression tests `DocumentRepoBackupDispatchTest` xanh | **CODE VERIFIED** |
| **T06** | P2 | SyncResultPresenter nhận callback retry/cấp quyền nhưng không cung cấp nút bấm tương tác | G06 | 6 regression tests `PostAuthorizationSyncResultTest` xanh | **CODE VERIFIED** |

---

## 3. Rà soát chi tiết Launcher, Saved State, Quyền sở hữu và Threading

### 3.1. Nhất quán Identity của Token và Saved State (`GoogleLoginAttempt`)
- **Vấn đề trước sửa:** Host recreate tạo `GoogleLoginAttempt(reqId, sessionGen)` mới. Do dùng `AtomicReference.compareAndSet(token, null)` (dựa trên con trỏ `===`), instance mới không xóa được active attempt cũ trong singleton, gây kẹt khóa `isSignInInProgress`.
- **Đã khắc phục:**
  - Xây dựng phương thức đồng bộ `matchesActiveAttemptLocked(token)` và `clearActiveAttemptIfMatchingLocked(token)` dưới `authStateLock`. Token được đối chiếu theo bộ nhận diện `requestId` và `initialSessionGeneration`.
  - Áp dụng trên toàn bộ các nhánh: cancel, error, success commit, fallback, và coroutine completion (`job.invokeOnCompletion`).
  - Phân định rõ ràng: Token cũ (attempt A) không thể xóa hoặc giải phóng khóa của attempt mới (attempt B).
  - Cả 3 host UI (`MoreFragment`, `PdfViewerActivity`, `IdCardComposeActivity`) lưu primitive `reqId` và `sessionGen` trong Bundle, tạo token thuần dữ liệu bất biến khi khôi phục.

### 3.2. Triệt tiêu đường mượn Token từ Singleton
- **Vấn đề trước sửa:** `handleGoogleSignInResult` fallback `attempt ?: activeLoginAttempt.get()`, cho phép callback không có token mượn nhầm attempt đang chờ của host khác.
- **Đã khắc phục:**
  - Loại bỏ hoàn toàn fallback `?: activeLoginAttempt.get()`. Callback thiếu token hoặc có token null/stale bị discard ngay tại guard đầu tiên mà không chạy qua parser.
  - Các overload tương thích cũ được đánh dấu `@Deprecated` và truyền `attempt = null` để ngăn chặn bypass ngầm.
  - Các host UI (`PdfViewerActivity`, `IdCardComposeActivity`, `MoreFragment`) capture token ra biến cục bộ và gán `pendingSignInAttempt = null` ngay trước khi gọi xử lý, ngăn chặn duplicate callback re-entrance.

### 3.3. Quyền sở hữu và Hàng rào dọn dẹp Đăng xuất (Sign-Out Fencing)
- **Vấn đề trước sửa:** `signOut` phóng coroutine bất đồng bộ và trong khối `finally` lại gọi `clearSavedUser` cùng `_currentUser.value = null` vô điều kiện, xóa phiên B vừa đăng nhập.
- **Đã khắc phục:**
  - Thiết lập hàng rào `logoutSessionGen = sessionGeneration.get()`.
  - Local state và preferences được dọn dẹp nhất quán ngay lập tức khi người dùng bấm đăng xuất.
  - Hạn chế phạm vi WorkManager: Chỉ hủy các job theo tag `owner_$previousUserId` thay vì gọi `cancelAllWork()` toàn bộ app.
  - Trong coroutine cleanup: Kiểm tra `sessionGeneration.get() == logoutSessionGen && _currentUser.value == null`. Nếu phiên mới B đã active, toàn bộ provider cleanup của A được bỏ qua an toàn.
  - Trong `finally`: Chỉ xóa preferences và null hóa `_currentUser` nếu session generation vẫn trùng khớp với thời điểm đăng xuất.
  - Quản lý Task hoàn tất: `GoogleSignInClient.signOut()` được gắn listener chờ hoàn thành với timeout 3s an toàn.

### 3.4. Authoritative Registry cho Google Drive (Chống Replay & Single-Consume)
- **Vấn đề trước sửa:** `consumed` trong `DriveAuthorizationAttempt` là `@Transient`, khiến token deserialize từ saved state có `consumed = false` và bị tiêu thụ lần hai.
- **Đã khắc phục:**
  - Thiết lập registry authoritative tập trung trong `AppAuthManager`:
    - `pendingDriveAttempts = mutableMapOf<Long, DriveAuthorizationAttempt>()`
    - `consumedDriveRequestIds = mutableSetOf<Long>()`
  - Bỏ `@Transient` khỏi `consumed` trong `DriveAuthorizationAttempt`.
  - Trong `handleDrivePermissionResult`: Kiểm tra và đối chiếu request ID với registry tập trung dưới `authStateLock`. Nếu request ID đã nằm trong `consumedDriveRequestIds`, lập tức discard.
  - Khi launch thất bại hoặc người dùng hủy, gọi `cancelDriveAuthorizationAttempt` để xóa khỏi pending registry và đưa vào consumed registry.
  - Process restart policy: Các request cũ từ process trước không có trong in-memory registry của process mới sẽ bị reject an toàn và cho phép người dùng khởi tạo request mới để retry.

### 3.5. Giải phóng Monitor Repository khỏi Blocking Backup I/O
- **Vấn đề trước sửa:** `addDocument`, `renameDocument`, `markDocumentModified` giữ khóa `@Synchronized` trên toàn bộ instance `DocumentRepo` trong khi gọi đồng bộ `CloudBackupManager.enqueueBackup`, làm block luồng UI khi người dùng đổi tên file từ dialog.
- **Đã khắc phục:**
  - Chuyển 3 vị trí kích hoạt sao lưu sang phương thức bất đồng bộ `dispatchCloudBackup(docItem)`.
  - Repository lưu dữ liệu local (`saveData()`) và publish LiveData trước, sau đó chuyển toàn bộ thao tác nặng (dọn dẹp orphan, copy PDF snapshot, WorkManager SQLite enqueue) sang `Dispatchers.IO` thông qua `CloudBackupManager.enqueueBackupAsync`.
  - Giữ snapshot bất biến của `expectedUserId` và `expectedSessionGen`, duy trì guard kiểm tra phiên làm việc trước và sau khi copy snapshot.
  - Monitor của `DocumentRepo` được giải phóng ngay lập tức, luồng UI không còn bị block khi đổi tên hay chỉnh sửa tài liệu.

### 3.6. Thao tác tương tác Retry / Cấp quyền thực sự (`SyncResultPresenter`)
- **Vấn đề trước sửa:** `onRequestDrivePermission` và `onRetry` bị bỏ qua trong presenter, chỉ hiển thị Toast thụ động.
- **Đã khắc phục:**
  - Thiết kế `ActionPrompt` và hiển thị `Snackbar` với action button gắn trên host Activity content view.
  - `AuthRequired` cung cấp nút "Cấp quyền" (`R.string.grant_permission`) trên toàn bộ 8 ngôn ngữ.
  - `Failure` cung cấp nút "Thử lại" (`R.string.retry`).
  - Cơ chế guard nguyên tử `AtomicBoolean` bảo đảm khi người dùng click action, callback chỉ được gọi đúng 1 lần duy nhất và tự động giải phóng debounce key để kết quả của lượt retry hiển thị ngay.
  - Nếu Activity đã bị hủy (`isFinishing || isDestroyed`), presenter hủy bỏ render và không giữ tham chiếu lambda.
  - Nếu không có callback hành động, chỉ hiển thị thông báo văn bản rõ ràng, không tạo nút bấm vô tác dụng.

---

## 4. Nghiệm thu trên Thiết bị và Google Play Track

| Kịch bản kiểm thử | Hành vi kỳ vọng | Phân loại | Ghi chú |
|---|---|---|---|
| Đăng nhập Google (Credential Manager & Classic fallback) | Đăng nhập thành công, lưu session, claim guest docs | **PROBE PASS / CODE VERIFIED** | Đã nghiệm thu probe host; thiết bị thực tế: **NOT RUN** |
| Hủy đăng nhập sau khi xoay màn hình (recreate) | Giải phóng khóa `isSignInInProgress`, cho phép bấm đăng nhập lại ngay | **PROBE PASS / CODE VERIFIED** | Đã nghiệm thu probe T01; thiết bị thực tế: **NOT RUN** |
| Process kill khi đang mở Google Sign-In hoặc Drive consent | Khôi phục an toàn, không kẹt khóa, cho phép thử lại | **CODE VERIFIED** | Đã nghiệm thu unit test registry; thiết bị thực tế: **NOT RUN** |
| Đăng xuất tài khoản A và đăng nhập nhanh tài khoản B | Tài khoản B không bị xóa bởi cleanup trễ của A | **PROBE PASS / CODE VERIFIED** | Đã nghiệm thu probe T04; thiết bị thực tế: **NOT RUN** |
| Phân quyền Google Drive qua serialization | Single-consume được bảo đảm, không gọi sync trùng lặp | **PROBE PASS / CODE VERIFIED** | Đã nghiệm thu probe T03; thiết bị thực tế: **NOT RUN** |
| Đổi tên tài liệu PDF trên UI (Home / Files) | Dialog đóng ngay, UI mượt mà, backup chạy nền trên IO | **CODE VERIFIED** | Đã nghiệm thu `DocumentRepoBackupDispatchTest`; thiết bị thực tế: **NOT RUN** |
| Bấm nút "Cấp quyền" / "Thử lại" trên Snackbar kết quả sync | Kích hoạt đúng launcher hoặc trigger sync lại đúng 1 lần | **CODE VERIFIED** | Đã nghiệm thu `PostAuthorizationSyncResultTest`; thiết bị thực tế: **NOT RUN** |
| Cài đặt bản release APK/AAB qua Play Internal App Sharing | Đăng nhập thành công không văng lỗi 10 (DEVELOPER_ERROR) | **NOT RUN** | **Yêu cầu SHA-1 Play App Signing và thiết bị thực tế** |

### Khuyến cáo cấu hình Google Play Console & OAuth:
- **Nguyên nhân tiềm ẩn lỗi đăng nhập trên Google Play:** Khi phát hành qua Google Play, Google sử dụng chứng thư **Play App Signing** để ký lại ứng dụng. SHA-1 của Play App Signing khác với SHA-1 của keystore máy phát triển cục bộ (`release.jks` / `debug.keystore`).
- **Hành động bắt buộc:** Cần truy cập **Google Play Console** -> *App Integrity* -> lấy mã vân tay **SHA-1 certificate fingerprint của App signing key**, sau đó đưa vào **Google Cloud Console** (OAuth 2.0 Client IDs for Android) ứng với package `com.tscanner.app`. Khi chưa đối chiếu và chưa có thiết bị kiểm chứng Play track, mục này được phân loại là **NOT RUN**.

---

## 5. Danh mục báo cáo đối chiếu các gói vòng 3
- [G00 — Đưa 4 probe vòng 3 thành regression lâu dài](REPORT_VIP_LOGIN_ROUND3_G00.md)
- [G01 — Nhất quán identity của token sau recreate (T01/P1)](REPORT_VIP_LOGIN_ROUND3_G01.md)
- [G02 — Không mượn token từ singleton (T02/P1)](REPORT_VIP_LOGIN_ROUND3_G02.md)
- [G03 — Đăng xuất có quyền sở hữu cleanup (T04/P1)](REPORT_VIP_LOGIN_ROUND3_G03.md)
- [G04 — Drive consume theo request identity (T03/P2)](REPORT_VIP_LOGIN_ROUND3_G04.md)
- [G05 — Repository không chạy backup blocking trong callback UI (T05/P2)](REPORT_VIP_LOGIN_ROUND3_G05.md)
- [G06 — Thao tác retry/cấp quyền thực sự (T06/P2)](REPORT_VIP_LOGIN_ROUND3_G06.md)
- [G07 — Tổng hợp host và thiết bị](REPORT_VIP_LOGIN_ROUND3_G07.md)

---

## 6. Điểm dừng kế hoạch
Toàn bộ kế hoạch vòng 3 (từ G00 đến G07) đã hoàn tất 100%. Toàn bộ 668 unit tests, lint (0 error), và build assembleDebug đều đạt tiêu chuẩn chất lượng cao nhất.
