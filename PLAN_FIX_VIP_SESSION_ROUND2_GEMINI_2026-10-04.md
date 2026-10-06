# Gemini (Antigravity) — sửa VIP session vòng 2, S00–S07

Ngày 04/10/2026. Workspace `E:\DU AN AI\T-Scanner`.

## Phải đọc trước

1. `RECHECK_VIP_SESSION_ROUND2_2026-10-04.md` — G01–G06, kết quả và giới hạn chứng cứ.
2. `PLAN_FIX_VIP_SESSION_GEMINI_ANTIGRAVITY_2026-10-04.md` — giữ các ràng buộc an toàn E00–E06.
3. `docs/vip-session-reaudit-20261004/` — source probe, XML, host summary/log.

Mục tiêu: guest và người có tài khoản hết hạn đều tiếp tục đúng Mua/Khôi phục; mọi host ràng buộc đúng owner; backend auth-required dẫn tới reauth/reconciliation có giới hạn; không bắt logout, không tạo lại giao dịch đã có receipt.

Khi được giao triển khai, tự làm tuần tự S00→S07. Mỗi gói chạy focused tests, ghi checkpoint rồi mới sang gói phụ thuộc, không hỏi lại từng bước. Không chạy các agent đồng thời sửa file chung. Nếu thiếu device/Console thì hoàn tất phần host độc lập, ghi NOT RUN/BLOCKED_EXTERNAL và tổng hợp một lần.

Giữ mọi thay đổi có sẵn, không reset/clean/stash/ghi đè. Không thay package/OAuth/signing/R8/version/dependency/backend, không mua thật, commit/push/upload/release. Chỉ thay test dependencies khi chứng minh cần thiết và tách đề xuất riêng; ưu tiên test seam bằng hạ tầng đang có. Không sửa report cũ để xóa lịch sử; viết report mới đối chiếu kết quả thực.

## S00 — Khóa reproduction và sửa chất lượng kiểm thử (G05)

- **Scope:** tests, docs; không production trừ seam nhỏ được mô tả và giao gói phụ trách.
- Lưu baseline status/diff; chạy lại 8 probes. Phân biệt 6 FAIL với 6 lỗi độc lập: P01/P02 cùng G01, P04/P05 cùng G04.
- Thiết kế test đi qua wiring thật của AccountDetailDialog, VipUpgradeDialog, MoreFragment, PdfViewerActivity, IdCardComposeActivity. Nếu chưa có UI test runner, viết instrumentation hoặc tách seam production ở gói sở hữu để handler UI bắt buộc gọi nó; fake external provider/Billing, không sao chép biểu thức/callback setup trong test.
- **Regression:** tháo callback production ở hai nút tài khoản hoặc OR action-only phải làm test tương ứng đỏ; guest→login success phải dùng actual auth commit để generation tăng; RESTORE chạy cả modern và fallback.
- **Acceptance:** evidence đỏ có ý nghĩa, test source không tự thay logic bằng phiên bản mong muốn. Không tuyên bố test UI PASS khi chỉ chạy handler thuần.
- **Checkpoint:** `docs/vip-session-round2-fix/REPORT_S00.md`, `PROGRESS.md`; thống nhất seam ownership trước S01.

## S01 — Continuation nhận đúng chuyển phiên của attempt (G01)

- **Depends:** S00.
- **Files:** `VipLoginContinuationHandler.kt`, `MoreFragment.kt`, tests; `AppAuthManager.kt`/`GoogleLoginAttempt.kt` chỉ nếu cần cung cấp commit identity chính xác.
- Bind action với owner ban đầu/guest, originating request ID, epoch và generation. Cho phép guest null→A do chính attempt đó commit; same-owner reauth không đổi owner; mọi chuyển phiên ngoài attempt, logout, process cũ phải bị loại.
- Không sửa bằng cách bỏ so sánh generation hoặc truyền -1 cho production. Đồng nhất consumption ở Credential Manager và Intent fallback. Giữ product context nếu thực sự được truyền, không lưu rồi bỏ.
- Hủy/lỗi/không khởi chạy vì busy: không ghi đè continuation của operation khác; kết thúc đúng request. Rotation phục hồi chỉ attempt hợp lệ, process restart không replay pending cũ.
- **Regression:** P01/P02 xanh; C01/C02 giữ xanh; duplicate success, stale attempt, logout, A→B→A, rotation, process death, guest token thiếu/hết hạn, launch refused vì login khác đang chạy.
- **Acceptance:** đúng action một lần sau guest login và same-owner reauth, không mở thanh toán tự động, không mất protections.
- **Checkpoint:** ghi contract origin/commit/consume để S02–S03 áp dụng thống nhất.

## S02 — Áp dụng owner/action contract cho PDF và ID-card (G02)

- **Depends:** S01.
- **Files:** `PdfViewerActivity.kt`, `IdCardComposeActivity.kt`, `CreatePdfDialog.kt` nếu cần truyền action/draft, tests host. Không sửa backend hoặc nới auth core.
- Mọi VIP reauth khi có profile A truyền expected owner A trước provider call; guest dùng login bình thường. Modern/fallback đều cùng attempt, guard trước commit/claim/migrate.
- Cả hai host truyền callback theo action vào VipUpgradeDialog; RESTORE không request continuation mặc định UPGRADE. Thực thi đúng action qua contract S01.
- Giữ draft PDF/name/watermark intentions khi cùng owner; sai owner bị từ chối trước state mutation; không gọi save/export tài liệu của A dưới B. Cancel/host destroyed không mất draft và không cấp VIP.
- **Regression:** test callback được host tạo thực, assert attempt.expectedOwnerId=A; provider trả B phải giữ A; provider trả A tiếp tục đúng UPGRADE/RESTORE; guest vẫn dùng được; rotation/back/draft preservation ở cả hai host.
- **Acceptance:** không còn host VIP dùng unbound sign-in với current profile; không restore→upgrade. P03 cũ là probe unbound API: thay bằng host-bound regression, giữ case unbound làm control nếu cần; không vô hiệu hóa đổi tài khoản có chủ đích trên toàn ứng dụng.
- **Checkpoint:** bảng mọi call site VIP và tests cho từng host.

## S03 — Giữ lý do auth-required qua điều hướng/restore (G03, nhánh restore)

- **Depends:** S02.
- **Files:** `VipUpgradeDialog.kt`, `HomeFragment.kt`, `MainActivity.kt`, `MoreFragment.kt`, continuation context và các host liên quan tối thiểu; tests.
- Truyền auth-required reason/force-reauth context có owner và operation identity từ dialog qua navigation. Backend 401 vẫn yêu cầu reauth dù local exp còn hạn; không chỉ kiểm tra expired trong FragmentResultListener.
- Phân biệt fresh navigation “mở VIP” với “backend đã yêu cầu xác thực lại”. Không dùng global boolean không gắn operation.
- Sau reauth, restore tối đa một lần cho operation; nếu auth-required tiếp tục thì kết thúc và hiện retry thủ công. Không recursive redirect, không nuốt auth-required thành toast chung, không retry receipt bằng mua mới.
- **Regression:** Home→More RESTORE backend401/token-fresh phải mở provider đúng một lần; AccountDetail/PDF/ID-card cũng tương đương; second401 dừng; cancel/offline/account switch/late callback; valid session bình thường không mở provider không cần thiết.
- **Acceptance:** đúng reason, owner, action và giới hạn retry qua navigation thật/seam production; không lặp sign-in.
- **Checkpoint:** bàn giao typed UI auth recovery contract cho S04.

## S04 — Auth recovery sau receipt, không coi 401 là mạng (G03, nhánh verify)

- **Depends:** S03.
- **Files:** `utils/billing/PurchaseVerifier.kt`, `PlayPurchaseVerifier.kt`, `BillingManager.kt`, `VipUpgradeDialog.kt` phần event và consumer của sealed result; tests. Client only; không đổi backend deployment/contract tùy tiện.
- Thêm/propagate typed auth-required outcome cho lỗi credential, giữ khác network/transient, business rejection và forbidden. HTTP401 đi auth recovery; HTTP403 dựa contract, không mặc định tất cả cần refresh.
- Khi purchase đã có receipt: lưu/giữ recovery context đúng owner; sau reauth verify/reconcile lại receipt, không gọi launchBillingFlow lần nữa; không grant/ack vượt policy hiện có. Giữ process death recovery qua Play query/reconciliation.
- Token thiếu/hết hạn trước verify không biến thành receipt bị revoke hoặc ownership rejection. Background sync không tự bật UI login; trả deferred auth state, foreground user action mới mở provider.
- **Regression:** P06 không còn vào network branch; transport401/403/429/5xx khác nhau; receipt trước auth error được recover với cùng purchase token, zero extra launch; cancel giữ receipt; second401 dừng; account switch không apply; restart/silent sync không tự mở UI.
- **Acceptance:** typed end-to-end event tới host, bounded recovery, receipt không mất/nhân đôi, backend verification và account binding giữ nguyên.
- **Checkpoint:** audit mọi exhaustive when để không bỏ sót nhánh mới; báo test receipt lifecycle thực chạy.

## S05 — Khóa terminal theo từng thao tác mua (G04)

- **Depends:** S04.
- **Files:** `VipPurchaseActionCoordinator.kt`, launcher adapter nếu cần, focused tests; không thay Play purchase ownership để bù race UI.
- Mỗi click được nhận có operation ID và terminal guard riêng từ lúc bắt đầu đến connect/query/launch/auth/error. Callback chỉ được tác động khi vẫn sở hữu operation đó; terminal cũ không reset busy operation mới.
- Dùng cùng guard cho tất cả đường kết thúc, kể cả true return + onError/onAuthRequired bất thường; lifecycle/session invalidation phải đóng đúng operation. Không để một AtomicBoolean global làm identity.
- **Regression:** P04/P05 xanh; callback connect/query lặp, error→late success, new action khi old kết thúc, auth-required→late success, destroy/reset/logout, duplicate click và terminal đúng một lần.
- **Acceptance:** tối đa một launcher invocation/terminal cho action; callback cũ không điều khiển thao tác mới. Báo đây là fault-injection coverage, không tuyên bố đã tái hiện tính tiền hai lần.
- **Checkpoint:** giữ các test checkout hiện có không hồi quy.

## S06 — Chuỗi reauth đúng locale (G06)

- **Depends:** S05.
- **Files:** resources strings của các locale đang hỗ trợ và tests tài nguyên, không thêm ngôn ngữ ngoài phạm vi.
- Default values dùng ngôn ngữ mặc định của app (hiện tiếng Anh); values-vi giữ tiếng Việt; bổ sung bản dịch tương ứng cho các locale đang hỗ trợ. Bỏ việc dùng ignore MissingTranslation để che key mới còn thiếu.
- Giữ placeholder/escaping; thông báo guest, expired, account mismatch không gây hiểu nhầm. Không mở rộng sửa toàn bộ localization debt.
- **Regression/acceptance:** key đủ locale, XML compile, format đúng; device/render locales nào chưa chạy phải ghi NOT RUN.
- **Checkpoint:** danh sách resources sửa và kiểm tra thực chạy.

## S07 — Kiểm chứng và bàn giao cuối

- **Depends:** S06. **Files:** test/report/evidence; sửa production chỉ cho regression liên quan S01–S06 rồi chạy lại đúng checks.
- Chạy focused regressions + full `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline`. Đọc XML count thật; baseline vòng audit là 1.023/0 failures, lint 753 warnings, không phải số mục tiêu để ép khớp.
- G05: chứng minh test fail khi wiring production tương ứng sai, không sửa expectation để xanh. Không xóa negative test. Probe P03 phải được giải thích riêng theo S02.
- Device gate: A Free hết hạn thực, hai nút tài khoản và Home/PDF/ID-card, guest login→Mua/Restore, wrong account, backend auth failure theo môi trường test có kiểm soát, cancel/offline/rotation/restart. Không sửa đồng hồ máy/token production để giả chứng cứ; không mua thật.
- Nếu không có thiết bị/license tester/Play test track, ghi NOT RUN và bảng còn thiếu; host PASS không đồng nghĩa public release fixed.
- **Acceptance:** G01–G06 có verdict/evidence; báo riêng confirmed defect, robustness test và runtime gates. `REPORT_FINAL.md` nêu files, red→green, commands, counts, limitations và bước release còn lại.
- **Điểm dừng:** giao kết quả review, không tự commit/push/phát hành.

## Mẫu báo cáo và prompt

Mỗi gói lưu `docs/vip-session-round2-fix/REPORT_Sxx.md`: phạm vi, evidence trước/sau, files đã sửa (tách phần có sẵn), command/test và số fail/error/skip, acceptance, NOT RUN, contract bàn giao. Cập nhật `PROGRESS.md`; lỗi code chưa qua thì không chuyển gói phụ thuộc.

```text
Đọc E:\DU AN AI\T-Scanner\PLAN_FIX_VIP_SESSION_ROUND2_GEMINI_2026-10-04.md và RECHECK_VIP_SESSION_ROUND2_2026-10-04.md. Thực hiện tuần tự S00→S07, giữ toàn bộ thay đổi hiện có. Mỗi gói viết regression đi qua production, chạy focused tests, lưu REPORT_Sxx.md/PROGRESS.md rồi tự chuyển khi acceptance đạt; không hỏi lại từng gói.

Ưu tiên guest continuation qua đúng login attempt; ràng buộc owner và giữ UPGRADE/RESTORE ở PDF/ID-card; force reauth khi backend yêu cầu dù local token còn hạn; receipt đã có chỉ verify/reconcile lại, không mua mới. Không bỏ auth/session guards, không logout ẩn, không cấp VIP chỉ nhờ login. Sửa callback operation ownership và locale theo scope. Không sao chép UI logic vào test để làm xanh.

Thiếu device/Play access ghi NOT RUN/BLOCKED_EXTERNAL, hoàn tất host work độc lập. Không sửa OAuth/signing/R8/version/dependencies/backend, không mua thật, commit/push hoặc release. Kết thúc bằng báo cáo evidence và gates còn thiếu.
```

Prompt một gói: `Đọc PLAN_FIX_VIP_SESSION_ROUND2_GEMINI_2026-10-04.md cùng PROGRESS.md và báo cáo gói trước. Chỉ làm Sxx đúng scope/regression/acceptance, ghi bàn giao rồi dừng ở checkpoint.`
