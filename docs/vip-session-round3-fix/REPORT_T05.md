# Báo cáo kiểm thử & Tổng duyệt Gói T05 (Tổng kiểm chứng & Gate Review)

Ngày thực hiện: 05/10/2026.  
Workspace: `E:\DU AN AI\T-Scanner`  
Gói công việc: T05 (Theo `PLAN_FIX_VIP_SESSION_ROUND3_GEMINI_2026-10-04.md`)

---

## 1. Mục tiêu và Phạm vi
- Chạy toàn bộ bộ kiểm thử đơn vị độc lập trên host để đảm bảo không có bất kỳ hồi quy nào trên toàn bộ ứng dụng.
- Đối chiếu và cập nhật 4 bài kiểm thử hồi quy cũ để phản ánh đúng hợp đồng Vòng 3 (Round 3 contracts):
  1. `VipLoginContinuationTest.testContinuationHandler_staleAttempt_mismatchedRequestId_dropsContinuation`: Cập nhật xác nhận rằng callback với request ID không khớp (từ attempt khác/lỗi thời) không được xóa pending continuation đang kích hoạt (khớp Probe P01 / H02).
  2. `PlayPurchaseVerifierHttpTest.testRemoteVerifier_sendsAuthAndCorrectJsonPayload`: Chuyển mock bearer token sang chuỗi synthetic JWT hợp lệ (`VALID_TEST_JWT`) có `exp` tương lai, tránh bị từ chối sớm bởi bộ lọc `isTokenExpired`.
  3. `PlayPurchaseVerifierHttpTest.testSessionBoundToken_missingToken_rejectsWithReauth`: Khẳng định token rỗng/thiếu dẫn tới `VerificationResult.AuthRequired` (yêu cầu re-auth phục hồi) thay vì `VerificationResult.Rejected` (từ chối vĩnh viễn), khớp Probe P03 / H01.
  4. `PlayPurchaseVerifierHttpTest.testSessionBoundToken_sessionChangedDuringAcquisition_returnsTransientError`: Căn chỉnh kiểm tra đột biến phiên (`startGen != afterGen`) ngay sau khi lấy token để ưu tiên trả về `VerificationResult.TransientError`.
- Chạy xác nhận 7/7 Round 3 probes (`VipSessionRound3ProbeTest`).
- Chạy xác nhận 7/7 Round 2 regression probes (`VipSessionReauditProbeTest`).
- Chạy xác nhận 3/3 Round 3 integration contracts (`VipSessionRound3IntegrationTest`).
- Chạy kiểm tra tĩnh (`lintDebug`).
- Biên dịch gói ứng dụng hoàn chỉnh (`assembleDebug`).
- Kiểm tra trạng thái thiết bị ngoại vi và đánh giá Device Gate.

---

## 2. Kết quả kiểm thử thực tế

### 2.1. Toàn bộ Unit Test Suite (:app:testDebugUnitTest)
- Lệnh thực thi:
  ```powershell
  $env:JAVA_HOME = 'C:\Users\nguye\.jdks\openjdk-21.0.1'; $env:GRADLE_USER_HOME = 'C:\Users\nguye\.gradle'
  .\gradlew.bat :app:testDebugUnitTest --offline --console=plain
  ```
- **Kết quả: BUILD SUCCESSFUL**
- **Tổng số test: 1,046**
- **Thất bại (Failures): 0**
- **Lỗi (Errors): 0**
- **Bỏ qua (Skipped): 0**
- Tỷ lệ đạt: **100% PASS**

### 2.2. Kiểm thử Probes Vòng 3 (`VipSessionRound3ProbeTest`)
- Lệnh thực thi:
  ```powershell
  .\gradlew.bat :app:testDebugUnitTest --offline -I docs/vip-session-round3-reaudit-20261004/audit.init.gradle -PsessionRound3Probes --tests 'com.tscanner.app.VipSessionRound3ProbeTest' --console=plain
  ```
- **Kết quả: 7/7 PASS (BUILD SUCCESSFUL)**
  - `P01_oldSuccessMustNotEraseNewPendingRequest`: **PASS** (Attempt ID không khớp bỏ qua an toàn, giữ nguyên pending continuation).
  - `P02_boundContinuationMustRequireOriginatingAttemptAtConsumption`: **PASS** (Continuation gắn attempt bắt buộc cung cấp attempt ID khi tiêu thụ).
  - `P03_missingCredentialMustBeAuthRequiredNotReceiptRejection`: **PASS** (Token null/thiếu trả về `AuthRequired`, không từ chối receipt hợp lệ).
  - `P04_expiredCredentialMustStopBeforeTransport`: **PASS** (Token hết hạn dừng ngay tại tầng client/auth recovery, không gửi request mạng).
  - `C01_matchingAttemptDispatchesOnce`: **PASS** (Attempt trùng khớp chỉ thực thi đúng 1 lần duy nhất).
  - `C02_wrongCancellationPreservesPending`: **PASS** (Hủy bỏ với ID không khớp không làm mất pending continuation).
  - `C03_backend401WithFreshTokenIsTyped`: **PASS** (Backend 401 trả về sự kiện có kiểu rõ ràng `AuthRequired`).

### 2.3. Kiểm thử hồi quy Vòng 2 (`VipSessionReauditProbeTest`)
- Lệnh thực thi:
  ```powershell
  .\gradlew.bat :app:testDebugUnitTest --offline -I docs/vip-session-reaudit-20261004/audit.init.gradle --tests 'com.tscanner.app.VipSessionReauditProbeTest.P01*' --tests 'com.tscanner.app.VipSessionReauditProbeTest.P02*' --tests 'com.tscanner.app.VipSessionReauditProbeTest.P04*' --tests 'com.tscanner.app.VipSessionReauditProbeTest.P05*' --tests 'com.tscanner.app.VipSessionReauditProbeTest.P06*' --tests 'com.tscanner.app.VipSessionReauditProbeTest.C01*' --tests 'com.tscanner.app.VipSessionReauditProbeTest.C02*' --console=plain
  ```
- **Kết quả: 7/7 PASS (BUILD SUCCESSFUL)**
  - `P01_guestUpgradeSurvivesSuccessfulLoginGenerationChange`: **PASS**
  - `P02_guestRestoreSurvivesSuccessfulLoginGenerationChange`: **PASS**
  - `P04_duplicateProductCallbackMustNotLaunchTwice`: **PASS**
  - `P05_oldProductCallbackMustNotFinishNewAction`: **PASS**
  - `P06_backend401MustRemainAnAuthFailureNotNetworkFailure`: **PASS**
  - `C01_sameOwnerReauthContinuesOnce`: **PASS**
  - `C02_boundReauthRejectsOtherOwner`: **PASS**

### 2.4. Kiểm thử Hợp đồng Tích hợp Vòng 3 (`VipSessionRound3IntegrationTest`)
- Lệnh thực thi:
  ```powershell
  .\gradlew.bat testDebugUnitTest --tests "com.tscanner.app.VipSessionRound3IntegrationTest" --offline --console=plain
  ```
- **Kết quả: 3/3 PASS (BUILD SUCCESSFUL)**
  - `receiptAuthRequiredRequestsSameOwnerRecoveryWithoutNewPurchase_contractSpecification`: **PASS** (H01 - Phục hồi receipt đúng chủ sở hữu, không mở mua trùng).
  - `fallbackLaunchFailureAllowsRetryOnSameHost_contractSpecification`: **PASS** (H03 - Lỗi khởi chạy fallback dọn sạch cờ bận, cho phép người dùng bấm thử lại ngay trên cùng host).
  - `navigationFromOwnerARejectedAfterLogoutAndOnReplay_contractSpecification`: **PASS** (H04 - Chặn navigation cũ sau khi đăng xuất, chặn replay Bundle, chặn session epoch mismatch).

---

## 3. Kiểm tra Tĩnh và Biên dịch

### 3.1. Android Lint (`:app:lintDebug`)
- Lệnh thực thi:
  ```powershell
  .\gradlew.bat :app:lintDebug --offline --console=plain
  ```
- **Kết quả: BUILD SUCCESSFUL (0 errors)**. Báo cáo HTML/SARIF xuất tại `app/build/reports/lint-results-debug.html`.

### 3.2. Biên dịch gói Debug APK (`:app:assembleDebug`)
- Lệnh thực thi:
  ```powershell
  .\gradlew.bat :app:assembleDebug --offline --console=plain
  ```
- **Kết quả: BUILD SUCCESSFUL**. File APK đầu ra tạo thành công tại `app/build/outputs/apk/debug/app-debug.apk`.

---

## 4. Đánh giá Thiết bị Ngoại vi (Device Gate Review)

- Lệnh kiểm tra:
  ```powershell
  & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices
  ```
- Kết quả đầu ra:
  ```text
  List of devices attached
  (empty)
  ```
- **Đánh giá Device Gate: NOT RUN / BLOCKED_EXTERNAL**.
  - Không có thiết bị Android vật lý hoặc máy ảo (emulator) nào được kết nối tại thời điểm chạy.
  - Theo đúng chỉ thị kế hoạch: Tất cả các khâu kiểm thử độc lập trên máy chủ (host-independent unit, integration, lint, build) đã hoàn tất 100%. Các bước kiểm thử tương tác thực tế với Google Play Console Billing sandbox cần được thực hiện khi có thiết bị kết nối.

---

## 5. Kết luận nghiệm thu Gói T05
- Gói T05 hoàn thành toàn bộ mục tiêu kiểm chứng, không có hồi quy, code pass 100% test và lint.
- Đủ điều kiện chuyển sang tổng hợp báo cáo chung `REPORT_FINAL.md`.
