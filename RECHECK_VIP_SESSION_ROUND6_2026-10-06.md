# Kiểm tra bản sửa V00–V03 — 06/10/2026

## Kết luận

**Chưa khép toàn bộ kế hoạch vòng 5.** 25/25 regression cũ đã PASS, gồm ba lỗi của vòng 5. Reservation hiện ngăn hai consumer cùng claim và xử lý explicit refusal tốt hơn. Tuy nhiên còn hai điểm của chính hợp đồng đã yêu cầu: manager vẫn tính lượt trước khi consumer nhận, và chưa nối handshake qua tất cả đường mở VIP.

Không sửa code ứng dụng/config; chỉ thêm audit tests và tài liệu trong workspace. Không mua thật hoặc đổi dữ liệu tài khoản. Đây là audit checkout hiện tại, không suy từ báo cáo Gemini.

## S01 — [P1] Listener bỏ qua event trước consumer vẫn làm mất lượt

**Code:** `BillingManager.kt:1050–1054` vẫn dùng listener tồn tại để thêm key vào `attemptedRecoveryKeys`. Bản sửa thêm `event.releaseAttempt()` trong consumer, nhưng `VipUpgradeDialog.kt:135–142` chỉ gọi handler khi Activity hợp lệ và dialog đang hiện; nhánh điều kiện false không gọi consumer cũng không release. Vì vậy consumer không thể hoàn lượt cho event nó chưa nhận. Tình huống Activity finishing/destroyed trong khoảng listener còn gắn, hoặc adapter bỏ qua trước consume, vẫn thuộc yêu cầu inactive/no-host của V01.

**Tái hiện JVM:** `VipSessionRound6ProbeTest.P01_listenerDropsBeforeConsumerMustNotSpendRecovery` gọi manager production, dùng listener fault injection bỏ qua lần đầu (mô phỏng đúng vị trí guard trước consumer). Lần đầu consumerCalls=0, acceptedStarts=0. Cho listener hoạt động rồi emit cùng operation: consumerCalls=1 nhưng acceptedStarts vẫn **0**, kỳ vọng **1**. Test FAIL bằng assertion, không phải lỗi compile.

**Giới hạn:** probe không khởi tạo Android Dialog/Activity thật. Nhánh UI được xác nhận bằng source; thời điểm lifecycle trên thiết bị chưa chạy. Không kết luận mọi retry thủ công đều hỏng: operation mới có key mới. Lỗi đã chứng minh là cùng operation bị mất lượt dù chưa accepted recovery.

**Sửa cần thiết:** manager không đánh dấu đã dùng chỉ khi dispatch. Một nguồn state authoritative cho reserve/accepted/terminal; việc không có consumer không được cần một callback release để sửa lại trạng thái đã ghi sai. Không chỉ thêm release ở một nhánh Dialog rồi giữ nguyên contract cũ.

## S02 — [P2] Các entry point còn dùng fallback xác nhận sớm và làm mất context

**Xác nhận bằng source, chưa chạy UI runtime:**

| Đường mở VIP | Điểm thiếu |
|---|---|
| Home `HomeFragment.kt:313–343` | Chỉ onRequestSignInForAction; tạo navigation operation ID mới, không truyền recovery context/reservation hoặc kết quả nhận/từ chối |
| AccountDetail `AccountDetailDialog.kt:108–128` | Factory chỉ chuyển callback action cũ; không chuyển callback recovery |
| CreatePdf `CreatePdfDialog.kt:65–90` | Dialog VIP lồng nhau chỉ chuyển action/currentName, không callback recovery |

`VipUpgradeDialog.kt:204–209` fallback gọi `decision.confirmStarted()` **trước** callback Unit; kể cả callback không mở được provider hoặc không tồn tại thì reservation vẫn STARTED. Các host trực tiếp More/PdfViewer/IdCard đã được nối callback mới, nhưng các đường trên vẫn đi qua fallback. Riêng Home chưa thể biết More đã nhận yêu cầu vì navigation bất đồng bộ.

Hệ quả xác nhận từ wiring: không thể trả refused về đúng reservation, và origin context không được chuyển xuyên suốt đến restore qua những entry point này. Không khẳng định đã tái hiện double-charge hay vòng lặp Google SDK.

## Vì sao suite xanh vẫn chưa đủ

- `VipSessionRound3IntegrationTest.kt:120–124` đòi event thứ hai isRetry=true sau khi chỉ thu event vào biến, chưa có accepted provider. Kỳ vọng đó mâu thuẫn với accepted-only budget. Giữ mục tiêu chống retry lặp, nhưng bổ sung bước production consumer/provider accepted trước assert lần hai. Không giữ implementation sai để test này xanh.
- `VipPurchaseRecoveryIntegrationTest.kt:151–152` thêm cùng một lambda hai lần; manager `addAuthRequiredListener` dedupe listener, nên contract3 không thực sự kiểm tra hai consumer khác nhau. Probe vòng 5 dùng hai lambda khác nhau vẫn PASS, vì vậy đây là thiếu coverage chứ không kết luận atomic claim đang hỏng.
- Test contract4 tự nối continuation/restore trong test; `restoredSuccess=true` tại dòng 220 còn bỏ qua giá trị `success` callback. Test có assert VIP sau đó, nhưng chưa chứng minh các adapter Home/AccountDetail/CreatePdf hoạt động. Sửa callback assertion và dùng production adapter được UI gọi thực; tránh copy logic host vào test.

## Kết quả kiểm chứng

| Gate | Kết quả hiện tại |
|---|---|
| Full JVM suite | **1.090 tests, 0 failures/errors/skipped**, test task chạy lại |
| 25 regression vòng 2–5 được chọn | **25 PASS**; vòng 5 dùng adapter Gemini có reset reservation và explicit release/confirm |
| Probe vòng 6 | **1 FAIL / 1 PASS**; P01 mất lượt, C01 explicit refusal→accepted và không lặp đạt |
| Tổng probe | **27 tests: 26 PASS / 1 FAIL**, 0 errors |
| Lint/build | BUILD SUCCESSFUL 44s; lint analysis/report và assembleDebug UP-TO-DATE; XML hiện tại 0 errors/768 warnings toàn dự án |
| Device/Play | `adb devices -l` rỗng: **BLOCKED_EXTERNAL** |

Evidence: `docs/vip-session-round6-reaudit-20261006/`: host.log, host-results.json, probes.log, TEST-*.xml, VipSessionRound6ProbeTest.kt, run-probes.ps1, audit.init.gradle, lint-results-debug.xml, adb-devices.txt, source-evidence.txt, source-hashes.json, status-before.txt. Không ghi đè evidence các vòng trước.

Full command: `gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline -I docs/vip-session-round3-reaudit-20261004/audit.init.gradle --console=plain` (không bật sessionRound3Probes).

Probe command: `powershell -NoProfile -File docs/vip-session-round6-reaudit-20261006/run-probes.ps1`. Exit 1 là assertion failure P01. P03 unbound API vòng 2 tiếp tục nằm ngoài bộ regression theo phạm vi đã chấp nhận.

## Bàn giao

Thực hiện `PLAN_FIX_VIP_SESSION_ROUND6_GEMINI_2026-10-06.md` W00–W03. Giữ những sửa đã đạt; chỉ khép S01/S02 và coverage tương ứng. Cần chạy Google/Play/lifecycle thật trước khi nghiệm thu lỗi người dùng hoàn toàn.
