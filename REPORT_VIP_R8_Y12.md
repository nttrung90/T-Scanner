# Báo Cáo VIP Vòng 8 — Gói Y12: Kiểm Chứng Host Xuyên Tầng & Ma Trận Đầy Đủ

**Mã gói:** Y12  
**Mục tiêu:** Kiểm chứng toàn diện ma trận kiểm thử host xuyên tầng (Android JVM, Backend Node.js, Lint, Assemble) trên toàn bộ các lỗi G01–G10 đã xử lý qua các gói Y01–Y11.  
**Thời gian thực hiện:** 01/10/2026 16:25 – 16:40  
**Môi trường:** `E:\DU AN AI\T-Scanner` | Windows pwsh | Gradle offline | Node v22.12.0  

---

## 1. Tóm tắt Ma trận Kiểm chứng Host

Tất cả các bài kiểm tra chạy hoàn toàn tự động, cô lập và tái lập được 100%:

| Nhóm kiểm thử | Lệnh thực thi | Kết quả thực tế | Tỷ lệ đạt | Ghi chú |
|---|---|---|---|---|
| **R8 Backend Probes (Harness gốc)** | `node --experimental-strip-types --test docs/vip-round8-20261001/backend-round8-probes.test.ts` | **13 / 13 PASS** | 100% | B801–B813 xanh hoàn toàn |
| **R8 Backend Regression Suite** | `node --experimental-strip-types --test backend/billing-verifier/test/round8-regression.test.ts` | **20 / 20 PASS** | 100% | B801–B813 + các biến thể idempotent/stale/unknown |
| **Backend Full Test Suite** | `node --experimental-strip-types --test backend/billing-verifier/test/*.test.ts` | **128 / 128 PASS** | 100% | Toàn bộ 15 test files backend |
| **R7 Backend Probes (Gốc)** | `node --experimental-strip-types --test docs/vip-round7-20261001/backend-agent-probes.test.ts` | **9 / 9 PASS** | 100% | B701–B711 giữ vững 100% |
| **R8 Android Auth Probes (Gốc)** | `./gradlew.bat -I docs/vip-round8-20261001/audit.init.gradle :app:testDebugUnitTest --tests com.tscanner.app.RootRound8AuthAuditTest --offline --console=plain` | **8 / 8 PASS** | 100% | C801–C808 xanh hoàn toàn |
| **R8 Android Agent Probes (Gốc)** | `./gradlew.bat -I docs/vip-round8-20261001/audit.init.gradle :app:testDebugUnitTest --tests com.tscanner.app.AndroidRound8AuditTest --offline --console=plain` | **12 / 12 PASS** | 100% | A801–A812 xanh hoàn toàn |
| **R8 Android Integrated Regression** | `./gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipRound8RegressionTest --offline --console=plain` | **17 / 17 PASS** | 100% | A801–A812, C801–C803, C806, C808 |
| **R7 Android Probes (Gốc)** | `./gradlew.bat -I docs/vip-round7-20261001/audit.init.gradle :app:testDebugUnitTest --tests com.tscanner.app.AndroidAgentAuditTest --tests com.tscanner.app.RootVipAuthAuditTest --tests com.tscanner.app.RootVipParserAuditTest --offline --console=plain` | **18 / 18 PASS** | 100% | 8 + 6 + 4 tests bảo toàn |
| **Android Full Unit Test Suite** | `./gradlew.bat :app:testDebugUnitTest --offline --console=plain` | **983 / 983 PASS** | 100% | Vượt baseline 964 tests (+19 tests mới) |
| **Android Lint & Assemble** | `./gradlew.bat :app:lintDebug :app:assembleDebug --offline --console=plain` | **BUILD SUCCESSFUL** | 100% | 0 lint errors, APK build thành công |

---

## 2. Chi tiết Từng Nhóm Lỗi G01–G10

- **G01 (Linked Work & Durable Retry — B801, B802, B809):**  
  Xử lý hoàn tất linked receipt resolution trước khi cập nhật watermark và persist main token. Idempotent và sống sót qua restart / multi-worker connections.
- **G02 (Unknown Linked RTDN & Google Schema — B803, B804, B810):**  
  Hỗ trợ schema Google không mang `subscriptionId`, trích xuất `lineItemProductId`, map quyền hạn authoritative, xếp hàng outbox ack.
- **G03 (Bind/Outbox Result Checking — B805):**  
  Bắt và xử lý `casConflict`, `conflictOwner`, `staleIgnored` và lỗi ghi DB trong quá trình xử lý RTDN của unknown token.
- **G04 (Single Final Outcome & Fresh Counts — B806, A804, A805, A812):**  
  Backend map duy nhất một kết quả cuối cùng cho mỗi purchase token. Android gom nhóm theo token identity set, ưu tiên quyền remote server, và chỉ đếm số lượng fresh entitlements vừa phục hồi.
- **G05 (Typed Pending/Unresolved State — A801, A802, A803, A806):**  
  Android phân định rõ ràng giữa `RestoreResult.NoActivePurchases` và `ReconciliationResult.PendingApproval`, không đánh đồng pending thành lỗi hoặc thành công giả tạo.
- **G06 (BillingManager Callbacks Suppression — A807, A808, A811):**  
  Sau khi `destroy()` hoặc coroutine scope bị cancel, triệt tiêu toàn bộ callback muộn từ Play SDK hoặc Android Main Looper Runnable.
- **G07 (Purchase Operation Owner Readiness — C804, C805, C807):**  
  Xác thực session generation, owner ID và token expiry xuyên suốt các mốc callback bất đồng bộ trước khi gọi `launchBillingFlow`.
- **G08 (HTTPS & Auth Guard tại Actual Transport — C801, C802, C803, C806, C808):**  
  Fail-closed chặn mọi kết nối không bảo mật hoặc credential hết hạn/malformed ngay trước lớp network transport của cả verify và restore.
- **G09 (UI Auth Recovery & Continuation):**  
  Hỗ trợ điều hướng đăng nhập cho cả nâng cấp và khôi phục VIP, lưu trữ `VipContinuationAction` và tự động tiếp tục thao tác sau khi đăng nhập thành công.
- **G10 (Voided Full Refund Lifetime — B812, B813):**  
  Google RTDN `voidedPurchaseNotification` thu hồi ngay lập tức (`REVOKED`) quyền lợi trọn đời trong SQLite store và watermark ordering.

---

## 3. Ranh giới Host vs External (Y13)

- **Host Checks (ĐÃ HOÀN TẤT & ĐẠT 100%):**  
  Toàn bộ contract, schema, logic, lifecycle, CAS, atomic transactions, HTTP routing, mock Play billing API và JVM unit tests.
- **External Gates (Chuyển sang gói Y13):**  
  Các kịch bản đòi hỏi môi trường thực tế từ bên thứ ba (Google Cloud PubSub topic thực tế, Google Play Licensing API thực tế, tài khoản test track và thiết bị Android vật lý).

Tự động chuyển tiếp sang **Y13 (External gates và bàn giao cuối một lần)**.
