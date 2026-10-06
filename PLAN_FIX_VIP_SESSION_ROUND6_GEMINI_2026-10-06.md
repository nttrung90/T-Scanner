# Gemini — khép admission và các entry point VIP, vòng 6

Workspace `E:\DU AN AI\T-Scanner`, ngày 06/10/2026. Đọc báo cáo vòng 6 và evidence tương ứng trước sửa. Không mở rộng sang backend/OAuth/signing hoặc những lỗi chưa tái hiện.

## Quy tắc

Thực hiện tuần tự W00–W03; ghi report rồi tự chuyển khi acceptance đạt, không hỏi lại từng gói. Giữ working tree; không reset/clean/stash, đổi package/version/R8/dependencies/backend/signing, mua thật, commit/push/phát hành. Không ghi đè evidence cũ. Chỉ sửa các file thuộc admission, recovery callback/navigation/continuation, tests/docs cần thiết.

## W00 — Baseline và sửa hợp đồng test bị lệch

Tests/docs trước, production thay ở W01/W02. Lưu status/hash. Chạy 27 probes vòng 6: baseline 26 PASS/1 FAIL; giữ P01/C01 vòng 6 nguyên tình huống/assertion. Chạy 25 regression cũ, full suite baseline 1.090.

Lập bảng tất cả call sites `VipUpgradeDialog`, gồm trực tiếp More/PdfViewer/IdCard và Home/AccountDetail/CreatePdf. Nêu đúng adapter gọi provider, nơi nhận accepted/refused/terminal, context đến restore, lifecycle cancellation.

**Giải quyết mâu thuẫn test, không che lỗi:** `VipSessionRound3IntegrationTest` đang assert second isRetry sau chỉ dispatch. Thêm bước production consumer → fake provider **accepted** trước event thứ hai; giữ assertion không reauth lần hai sau accepted. Thêm assertion dispatch mà không consumer thì chưa tiêu lượt. Lưu diff/lý do migration; không xóa test, đổi thành assert true tùy tiện hoặc sửa evidence cũ. Có thể giữ isRetry như thông tin delivery nếu đổi tên/ý nghĩa rõ, nhưng không dùng nó làm quyền auth khi chưa có accepted start.

## W01 — Một nguồn quản lý recovery, dispatch không tiêu lượt (S01)

Scope BillingManager, VipPurchaseAuthConsumer/coordinator nhỏ và listener Dialog cùng tests. Không bỏ atomic claim đã đạt.

- Loại bỏ việc quyết định đã dùng lượt chỉ vì `authRequiredListeners.isNotEmpty()` hoặc đã invoke listener. Dispatch/queue không phải accepted.
- Unclaimed event không làm thay đổi accepted budget. Listener bỏ qua/throw/biến mất trước consume, consumer Defer, không listener/background đều không cần hoàn lại một lượt đã bị tiêu sớm.
- Hợp nhất ledger authoritative hoặc định nghĩa rõ một owner; tránh manager set + consumer map mâu thuẫn. Consumer hợp lệ reserve độc quyền, provider nhận mới commit Started. Provider refused release **đúng reservation token**, không release state của người khác.
- Vẫn giới hạn accepted recovery tối đa một lần/operation; callback trùng/consumer thứ hai không gọi provider. Accepted→second401 phải Stop; thao tác thủ công mới có budget mới.
- Terminal/cancel/stale cleanup phải token-bound; reservation không treo khi host biến mất. Cleanup không làm operation đã accepted tự được reauth lại. Không làm suy yếu owner/gen/epoch guards để test xanh.

**Acceptance:** P01 vòng 6 xanh, C01 xanh; tests inactive trước consumer, queue không có consumer, busy, hai consumer khác nhau, repeated event trong Reserved/Started, accepted second401, stale callback. Assert acceptedStarts/providerCalls chứ không chỉ cờ event. Dialog listener phải gọi production adapter được test; không tự viết lại if/else của UI trong test rồi gọi là UI integration.

## W02 — Khép mọi adapter recovery, bỏ fallback confirm trước callback (S02)

Depends W01. Scope VipUpgradeDialog, HomeFragment/MainActivity/MoreFragment, AccountDetailDialog, CreatePdfDialog, host PdfViewer/IdCard và continuation chỉ phần liên quan.

- Định nghĩa typed recovery request giữ immutable operation context, owner, receipt identity/reservation; accepted/refused/terminal có attempt ownership. Giữ guest UPGRADE và RESTORE thông thường riêng để không phá hành vi cũ.
- Home→navigation→More phải mang cùng recovery request. Enqueue navigation chỉ Reserved; More gọi provider và nhận kết quả mới accepted. Navigation bị discard, host inactive/destroyed hoặc pending/busy phải resolve/refuse đúng reservation. Không tạo origin operation mới cho automatic continuation để bỏ context cũ. Không nhét closure vào Bundle; dùng envelope/registry có lifecycle/epoch guard hoặc giải pháp tương đương rõ ràng.
- AccountDetail factory và CreatePdf dialog lồng nhau phải truyền callback/envelope recovery đến host thật. CreatePdf giữ tên file và trạng thái watermark qua login như trước.
- VipUpgradeDialog không được confirmStarted trước callback Unit hay khi callback null. Missing recovery host phải Defer/refuse an toàn, không âm thầm chuyển sang mua mới. Sau migration rà lại tất cả constructor/factory call sites.
- Success cùng owner restore đúng một lần với đúng context, 0 extra launchBillingFlow, VIP/ack chỉ sau verify hợp lệ. Callback stale không tác động operation mới.

**Acceptance bắt buộc theo từng entry point:** trực tiếp More/PdfViewer/IdCard; Home→More; AccountDetail→VIP; CreatePdf→VIP. Mỗi đường test provider busy/accepted, operation context tới restore, stale/discard và không extra purchase. Test production adapters được UI gọi; external provider/router/restore có thể fake. Không manual gọi restore thay success callback production.

Nếu JVM không khởi tạo UI được, tách adapter production đủ nhỏ và cung cấp source-binding map + test adapter. Instrumentation/UI thật chưa chạy phải ghi NOT RUN, không gọi host test là device end-to-end.

## W03 — Verification và bằng chứng bàn giao

Depends W02. Sửa coverage contract3 bằng **hai lambda/consumer khác nhau**, xác nhận thực sự đăng ký hai consumer trước emit. Contract4 assert giá trị `success` thực, context và counts; không đặt biến true bất kể kết quả. Giữ 25 regression cũ với migration fixture minh bạch ở W00; giữ P01/C01 vòng 6, thêm tests theo từng adapter.

Chạy 27 probes chọn lọc, tests mới/permanent, full `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline`. Lưu XML/log trước lần chạy sau, báo số thực, executed/UP-TO-DATE. Không ép khớp baseline. Copy runner/init/probes sang thư mục fix và đổi output/source paths cần thiết, không ghi đè evidence vòng 6.

Device gate: Free A hết hạn; Activity kết thúc trước event; Home chuyển More nhưng More busy/discard; AccountDetail và CreatePdf gặp 401; cancel→retry thủ công; receipt recovery không mở mua mới. Dùng license tester được phép, không mua thật/clear-data. Thiếu device ghi BLOCKED_EXTERNAL.

Mỗi gói lưu `docs/vip-session-round6-fix/REPORT_Wxx.md` + `PROGRESS.md`; cuối `REPORT_FINAL.md`: files/call-sites, red→green, migration tests, commands/log/XML, provider/restore/extra-launch counts, source/host/device tách riêng. Dừng ở review, không phát hành.
