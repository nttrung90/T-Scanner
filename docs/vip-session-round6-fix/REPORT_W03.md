# REPORT_W03 — Tổng Kiểm Chứng, Regressions, Lint, Assemble & Bàn Giao

**Ngày thực hiện:** 06/10/2026  
**Gói công việc:** W03 — Verification, Regression, Lint, Build & Handover  
**Workspace:** `E:\DU AN AI\T-Scanner`  
**Trạng thái gói:** **PASS**

---

## 1. Mục tiêu Gói W03

1. Xác minh đầy đủ 27 probes (2 probes mới Round 6 + 25 probe regression từ các vòng trước): chuyển từ 26 PASS / 1 FAIL sang **27 PASS / 0 FAIL (100% GREEN)**.
2. Kiểm tra bộ kiểm thử đơn vị toàn dự án (`:app:testDebugUnitTest`): bảo đảm không gây bất kỳ hồi quy nào trên toàn bộ 1.090+ unit tests.
3. Kiểm tra mã tĩnh (`:app:lintDebug`): không phát sinh lỗi lint mới.
4. Kiểm tra biên dịch ứng dụng (`:app:assembleDebug`): bảo đảm đóng gói APK debug thành công.
5. Kiểm tra device gate (`adb devices -l`): ghi nhận chính xác `BLOCKED_EXTERNAL` do môi trường host không gắn thiết bị thật/giả lập.
6. Lưu trữ bằng chứng XML/log độc lập trong `docs/vip-session-round6-fix/`.

---

## 2. Kết quả Thực Thi Từng Cổng Kiểm Chứng

### 2.1. Bộ 27 Probes Chọn Lọc (`run-probes.ps1`)

Lệnh thực thi:
```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File docs/vip-session-round6-fix/run-probes.ps1
```

Kết quả:
- **`GRADLE_EXIT=0`**
- **27/27 probes PASS (0 failures, 0 errors, 0 skipped)**
- Cụ thể:
  - `VipSessionRound6ProbeTest`: **2/2 PASS**
    - `P01_listenerDropsBeforeConsumerMustNotSpendRecovery`: **PASS** (Red $\to$ Green, trước đó FAIL với `expected:<1> but was:<0>`)
    - `C01_explicitRefusalThenAcceptanceWorksAndDoesNotRepeat`: **PASS**
  - `VipSessionRound5ProbeTest`: **4/4 PASS**
  - `VipSessionRound4ProbeTest`: **8/8 PASS**
  - `VipSessionRound3ProbeTest`: **6/6 PASS**
  - `VipSessionReauditProbeTest`: **7/7 PASS** (P01, P02, P04, P05, P06, C01, C02)

XML logs tại `docs/vip-session-round6-fix/`:
- `TEST-com.tscanner.app.VipSessionRound6ProbeTest.xml`: `tests="2" failures="0" errors="0"`
- `TEST-com.tscanner.app.VipSessionRound5ProbeTest.xml`: `tests="4" failures="0" errors="0"`
- `TEST-com.tscanner.app.VipSessionRound4ProbeTest.xml`: `tests="8" failures="0" errors="0"`
- `TEST-com.tscanner.app.VipSessionRound3ProbeTest.xml`: `tests="6" failures="0" errors="0"`
- `TEST-com.tscanner.app.VipSessionReauditProbeTest.xml`: `tests="7" failures="0" errors="0"`

### 2.2. Kiểm thử Tích hợp và Hợp đồng Mới (`app/src/test/`)

1. **`VipEntryPointsRecoveryIntegrationTest`** (Mới tạo ở W02):
   - Số test: **6 tests**
   - Kết quả: **6 PASS / 0 FAIL**
   - Bao phủ 6 entry points: Direct More, Direct PdfViewer, Direct IdCard, Home $\to$ More Navigation Envelope, AccountDetail Dialog Factory, CreatePdf Dialog (bảo toàn file name và watermark).
   - XML: `docs/vip-session-round6-fix/TEST-com.tscanner.app.VipEntryPointsRecoveryIntegrationTest.xml`
2. **`VipPurchaseRecoveryIntegrationTest`** (Đã di chuyển contract3 & contract4):
   - Số test: **7 tests**
   - Kết quả: **7 PASS / 0 FAIL**
   - XML: `docs/vip-session-round6-fix/TEST-com.tscanner.app.VipPurchaseRecoveryIntegrationTest.xml`
3. **`VipSessionRound3IntegrationTest`** (Đã di chuyển fixture accepted provider):
   - Số test: **3 tests**
   - Kết quả: **3 PASS / 0 FAIL**
   - XML: `docs/vip-session-round6-fix/TEST-com.tscanner.app.VipSessionRound3IntegrationTest.xml`
4. **`VipPurchaseAuthConsumerTest`** (Thêm 2 unit tests authoritative ledger):
   - Số test: **10 tests**
   - Kết quả: **10 PASS / 0 FAIL**
   - XML: `docs/vip-session-round6-fix/TEST-com.tscanner.app.VipPurchaseAuthConsumerTest.xml`

### 2.3. Full Unit Test Toàn Dự Án (`:app:testDebugUnitTest`)

Lệnh thực thi:
```powershell
.\gradlew.bat :app:testDebugUnitTest --offline --console=plain
```

Kết quả:
- **BUILD SUCCESSFUL in 39s**
- **Tổng số test cases:** **1.098 tests** (Tăng từ 1.090 baseline do bổ sung 8 tests mới của Round 6)
- **Failures:** **0**
- **Skipped:** **0**
- **Tỷ lệ đạt:** **100.0%**
- Actionable tasks: 2 executed, 26 up-to-date.

### 2.4. Kiểm tra Lint (`:app:lintDebug`)

Lệnh thực thi:
```powershell
.\gradlew.bat :app:lintDebug --offline --console=plain
```

Kết quả:
- **BUILD SUCCESSFUL in 2m 46s**
- Actionable tasks: 31 actionable tasks (8 executed, 23 up-to-date)
- Báo cáo HTML/SARIF: `app/build/reports/lint-results-debug.html`

### 2.5. Kiểm tra Đóng gói (`:app:assembleDebug`)

Lệnh thực thi:
```powershell
.\gradlew.bat :app:assembleDebug --offline --console=plain
```

Kết quả:
- **BUILD SUCCESSFUL in 37s**
- Actionable tasks: 40 actionable tasks (3 executed, 37 up-to-date)
- APK sinh thành công tại: `app/build/outputs/apk/debug/app-debug.apk`

### 2.6. Device Gate (`adb devices -l`)

Lệnh:
```powershell
adb devices -l
```
Kết quả: `adb: The term 'adb' is not recognized`  
Trạng thái: **BLOCKED_EXTERNAL** (Không có Android SDK platform-tools/thiết bị vật lý/giả lập được kết nối trong container/runner). Toàn bộ các cổng host độc lập đã hoàn tất 100%.

---

## 3. Tổng Hợp Trạng Thái Nghiệm Thu

| Cổng kiểm tra | Chỉ tiêu | Thực tế | Trạng thái |
|---|---|---|:---:|
| 27 Probes | 27 PASS | 27 PASS / 0 FAIL | **PASS** |
| Unit Tests Toàn Bộ | $\ge 1.090$ PASS, 0 FAIL | 1.098 PASS / 0 FAIL | **PASS** |
| Lint | 0 fatal errors | BUILD SUCCESSFUL | **PASS** |
| Assemble Debug | APK buildable | BUILD SUCCESSFUL | **PASS** |
| Device Verification | Kiểm thử console/thiết bị | Không có thiết bị | **BLOCKED_EXTERNAL** |

Gói W03 hoàn tất xuất sắc và đủ điều kiện bàn giao kỹ thuật.
