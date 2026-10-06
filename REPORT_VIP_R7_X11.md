# Báo cáo VIP R7 — Gói X11: Kiểm chứng host độc lập và acceptance toàn bộ

## 1. Trạng thái và Mục tiêu
- **Mục tiêu:** Chạy kiểm chứng độc lập toàn bộ ma trận: 27 probes vòng 7, 12 probes gốc vòng 6, 16 probes gốc vòng 5, toàn bộ suites Android và backend, lintDebug và assembleDebug.
- **Trạng thái:** **DONE** (toàn bộ tests đều PASS, 0 lint errors, build APK debug thành công).

## 2. Kết quả kiểm thử thực tế (Actual Matrix)

| Suite / Target | Lệnh thực thi | Kết quả PASS / Tổng | Ghi chú |
|---|---|---|---|
| **R7 Android Probes** | `./gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipRound7RegressionTest` | **18 / 18** (100%) | A01–A08, C01–C06, D01–D04 |
| **R7 Backend Probes** | `node --experimental-strip-types --test backend/billing-verifier/test/round7-regression.test.ts` | **9 / 9** (100%) | B701–B706, B709–B711 |
| **R6 Android Probes** | `./gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipRound6RegressionTest` | **12 / 12** (100%) | Baseline vòng 6 bảo toàn |
| **R6 Backend Probes** | `node --experimental-strip-types --test backend/billing-verifier/test/round6-regression.test.ts` | **3 / 3** (100%) | Baseline vòng 6 bảo toàn |
| **R5 Android Probes** | `./gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipRound5RegressionTest` | **9 / 9** (100%) | Baseline vòng 5 bảo toàn |
| **R5 Backend Probes** | `node --experimental-strip-types --test backend/billing-verifier/test/round5-regression.test.ts` | **7 / 7** (100%) | Baseline vòng 5 bảo toàn |
| **Full Backend Suite** | `node --experimental-strip-types --test backend/billing-verifier/test/*.test.ts` | **108 / 108** (100%) | 0 fail, 0 skipped, 0 cancelled |
| **Full Android Suite** | `./gradlew.bat :app:testDebugUnitTest --offline --console=plain` | **964 / 964** (100%) | 115 test suites, 0 failures, 0 errors |
| **Android Lint** | `./gradlew.bat :app:lintDebug --offline --console=plain` | **0 errors**, 757 warnings | Giữ nguyên baseline không phát sinh lỗi |
| **Android Build** | `./gradlew.bat :app:assembleDebug --offline --console=plain` | **BUILD SUCCESSFUL** | Tạo thành công debug APK |

## 3. Bản đồ F01–F09 và kết quả kiểm chứng host

- **F01 (Linked bind CAS loop & absent sentinel):** B701, B711 PASS. Đã ngăn chặn race conditions và ghi đè trạng thái đã có giữa 2 kết nối SQLite.
- **F02 (Different-SKU linked resolution outcome):** B702, B703, B709, B710 PASS. Phân giải đúng SKU yearly <-> monthly từ V2 lineItems.
- **F03 (RTDN canceled pending authority query):** B704 PASS. Truy vấn Play authority và refresh linked token.
- **F04 (Owner/generation capture & cancellation guard):** A06, A07 PASS. Discard commit/callback khi manager destroyed hoặc session thay đổi.
- **F05 (Independent backend refresh on Play query error):** A01 PASS. Play query lỗi vẫn refresh quyền server-authoritative cho owner đã đăng nhập.
- **F06 (Token itemized results & partial messaging):** B705, B706, D01, D02, A02, A03, A04, A05 PASS. Báo đúng failedCount, partial message và không fake full success.
- **F07 (Real credential & preflight expiry validation):** C01, C02, C03, C06, C05 PASS. idToken mặc định null, fail-closed khi parse JWT.
- **F08 (Restore HTTPS transport gate & auth recovery):** C04, C05 PASS. Enforce HTTPS/isConfigured trước khi gửi HTTP transport, map 401 sang AuthRequired.
- **F09 (Strict source, owner & state-based expiry):** D01, D02, D03, D04 PASS. Chấp nhận canceled-pending tombstone hợp lệ, từ chối payload thiếu source/owner.

## 4. Bước kế tiếp
- Tự động chuyển sang gói **X12**: Tổng hợp external gates, xuất `REPORT_VIP_R7_FINAL.md` và `docs/billing/ROUND7_ACCEPTANCE.md`.
