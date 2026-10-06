# Báo Cáo Gói W00 — Baseline & Sửa Hợp Đồng Test Bị Lệch

Ngày thực hiện: 06/10/2026. Workspace: `E:\DU AN AI\T-Scanner`.

---

## 1. Mục Tiêu & Phạm Vi Gói W00

- Khóa trạng thái baseline trước khi can thiệp mã nguồn production:
  1. Ghi lại mã băm SHA256 và trạng thái Git của các tệp thuộc phạm vi sửa đổi.
  2. Tái hiện nguyên bản kết quả chạy 27 probes (26 PASS, 1 FAIL; P01 FAIL, C01 PASS, 25 regressions cũ PASS).
  3. Lập ma trận kết nối (call-sites wiring map) của toàn bộ các vị trí khởi tạo và gọi `VipUpgradeDialog` trên toàn bộ dự án.
  4. Đồng bộ hóa và di chuyển (migrate) fixture kiểm thử trong `VipSessionRound3IntegrationTest.kt` để yêu cầu phải có bước production consumer $\to$ provider accepted trước khi assert chặn reauth lần hai, bảo đảm tính nhất quán với triết lý "accepted-only budget".

---

## 2. Khóa Trạng Thái Baseline

- Mã băm SHA256 đã lưu tại `docs/vip-session-round6-fix/baseline_hashes.txt`:
  - `BillingManager.kt`: `F35C3FC56C3584A47DD0A8F497B79DD23D886156A34F80D39AC42DC6E7BEE36B`
  - `VipPurchaseAuthConsumer.kt`: `B5AC34D3622BEF9DD3796A3769242184AAE40BC937E7E9F44901FEF5BCAE1D2B`
  - `VipUpgradeDialog.kt`: `1BE20E9753CBAA57B5A7D83848337E65D7280485D7EB276BB6437B23C6DB537D`
  - `HomeFragment.kt`: `E700EFA1BF40EE6C8A6F4B8530C0A3CE6E40156FC2215A5C9993808EA6F2FACF`
  - `AccountDetailDialog.kt`: `AACD96094128D695972DAF994FEEB14640EFD564CC651F8E9B547DD4B7E98DD5`
  - `CreatePdfDialog.kt`: `B6A3D2147B6ACA11B3121E1985A879EFA2C4185299B16946DB7BFDFD4D371127`
  - `MoreFragment.kt`: `9208255071B607337D8D3276DB12D1E42C75F6B1F9C89A9854FBD497CFBCC8CC`
  - `PdfViewerActivity.kt`: `526706CEB8023605DCBEDAE4BA938D0A6246D8B11CD59DDED977432C79CCC722`
  - `IdCardComposeActivity.kt`: `5A29CCDE263E0B18949BD47763DC6599D5EACC925F2DBB47214228C829FEE463`
  - `MainActivity.kt`: `2590C0BA1856E60C0BC7CA572A77998D4F62555BFF4533C2C0D0D2B68EC77AC3`
- Trạng thái git working tree ban đầu được sao lưu tại `docs/vip-session-round6-fix/baseline_git_status.txt`.

---

## 3. Kết Quả Chạy Baseline 27 Probes

Lệnh thực thi: `powershell -NoProfile -File docs/vip-session-round6-fix/run-probes.ps1`  
Mã thoát: `GRADLE_EXIT=1` — **BUILD FAILED** (26 PASS, 1 FAIL).

```
VipSessionRound6ProbeTest > P01_listenerDropsBeforeConsumerMustNotSpendRecovery FAILED
    java.lang.AssertionError at VipSessionRound6ProbeTest.kt:66
    expected:<1> but was:<0>
27 tests completed, 1 failed
```

- **P01 (`VipSessionRound6ProbeTest`)**: **FAIL** (đúng vị trí tái hiện lỗi S01 tại dòng 66 khi listener bỏ qua event lần 1 nhưng manager đã tiêu sớm lượt).
- **C01 (`VipSessionRound6ProbeTest`)**: **PASS** (refusal $\to$ release $\to$ accepted start).
- **25 Probes hồi quy cũ**: **PASS 100%**:
  - `VipSessionRound5ProbeTest`: 4/4 PASS
  - `VipSessionRound4ProbeTest`: 7/7 PASS
  - `VipSessionRound3ProbeTest`: 7/7 PASS
  - `VipSessionReauditProbeTest`: 7/7 PASS

---

## 4. Ma Trận Call Sites Wiring Của `VipUpgradeDialog`

| Call Site / Entry Point | File & Dòng | Trạng thái hiện tại | Vấn đề / Yêu cầu W01–W02 |
|---|---|---|---|
| **Trực tiếp More** | `MoreFragment.kt:527` | Đã nối `onRequestSignInForRecovery` | Duy trì, forward context tới restore |
| **Trực tiếp PdfViewer** | `PdfViewerActivity.kt:286` | Đã nối `onRequestSignInForRecovery` | Duy trì, forward context tới restore |
| **Trực tiếp IdCard** | `IdCardComposeActivity.kt:275` | Đã nối `onRequestSignInForRecovery` | Duy trì, forward context tới restore |
| **Home $\to$ More** | `HomeFragment.kt:313` | Chỉ có `onRequestSignInForAction` | Thiếu `onRequestSignInForRecovery`; tạo operation ID mới; không mang context/reservation qua navigation |
| **AccountDetail $\to$ VIP** | `AccountDetailDialog.kt:110` | Factory chỉ nhận action callback | Thiếu `onRequestSignInForRecovery` trong factory và instance |
| **CreatePdf $\to$ VIP** | `CreatePdfDialog.kt:65` | Chỉ có action callback | Thiếu `onRequestSignInForRecovery`; không chuyển tiếp callback host |
| **Fallback Dialog** | `VipUpgradeDialog.kt:204–209` | Tự gọi `confirmStarted()` trước callback Unit/null | Vi phạm nguyên tắc; phải chỉ commit khi host nhận thực sự |

---

## 5. Di Chuyển Hợp Đồng Kiểm Thử (`VipSessionRound3IntegrationTest.kt`)

- **Vấn đề trước sửa:**  
  Trong `VipSessionRound3IntegrationTest.kt`, sau khi gọi `billingManager.processPurchase(testPurchase)` lần đầu, event chỉ được thu vào biến `typedEventReceived`. Không có consumer nào đánh giá và không có provider nào cam kết tiếp nhận (`confirmStarted()`). Việc test kỳ vọng ngay sau đó lượt thứ hai là `isRetry = true` đã vô tình ép `BillingManager` phải tiêu ngân sách ngay lúc dispatch/queue.
- **Giải pháp di chuyển (Migration):**  
  Thêm bước thực tế qua production seam:
  ```kotlin
  val firstDecision = VipPurchaseAuthConsumer.evaluate(
      event = typedEventReceived!!,
      currentOwnerId = AppAuthManager.getCurrentUser()?.id,
      currentGeneration = AppAuthManager.getSessionGeneration(),
      currentEpoch = AppAuthManager.getProcessEpoch(),
      isUiActive = true
  )
  assertTrue(firstDecision is PurchaseAuthDecision.RequestReauth)
  (firstDecision as PurchaseAuthDecision.RequestReauth).confirmStarted()
  ```
  Sau khi provider cam kết (`confirmStarted()`), lần phát sinh thứ hai trên cùng receipt mới bị chặn (`secondDecision is PurchaseAuthDecision.Stop`) và `secondTypedEvent.isRetry == true`.
- **Kết quả:**  
  Kiểm thử `VipSessionRound3IntegrationTest` đạt **PASS** 100% (3/3 tests) mà không hạ thấp tiêu chuẩn chống lặp của hệ thống.

---

## 6. Kết Luận Gói W00

- Đã xác lập đầy đủ bằng chứng baseline, tái hiện lỗi đỏ P01 có ý nghĩa.
- Đã lập ma trận wiring và chuẩn bị sẵn sàng cho gói **W01** (Loại bỏ tiêu budget lúc dispatch, hợp nhất authoritative ledger).
