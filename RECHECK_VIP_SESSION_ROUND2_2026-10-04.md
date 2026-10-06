# Kiểm tra độc lập sau E00–E06 — phiên đăng nhập/VIP, vòng 2

Ngày: 04/10/2026. Workspace: `E:\DU AN AI\T-Scanner`.

## Kết luận

**Chưa hoàn tất kế hoạch.** Đường Chi tiết tài khoản đã nối callback, action-only callback đã được nhận diện và reauth có expected owner đã chặn chọn tài khoản khác. Tuy nhiên còn lỗi continuation khách, các host PDF/ID-card chưa áp dụng đầy đủ, auth recovery khi backend từ chối credential chưa khép kín. Coordinator còn thiếu bảo vệ trước callback lặp/cũ; kiểm thử đã viết chưa kiểm chứng wiring UI thực. Chuỗi mới chưa được dịch đầy đủ.

Đây là re-audit source/host, không phải xác nhận bản public Play. Không sửa production code, cấu hình, các tests hiện có hoặc report cũ. Chỉ thêm tài liệu và probe độc lập dưới `docs/vip-session-reaudit-20261004/`.

## Kết quả thực chạy

| Kiểm tra | Kết quả | Evidence |
|---|---|---|
| Bộ test hiện tại | 1.023 tests, 0 failure/error/skip | `host-test-results.json`, `host.log` |
| Lint debug | 0 errors, 753 warnings | `app/build/reports/lint-results-debug.xml`; warnings không mặc định do bản sửa này |
| Assemble debug | PASS | `host.log`, BUILD SUCCESSFUL 1m27s |
| Probe độc lập, lần cuối | 8 tests: **6 FAIL / 2 PASS** | `probe-results.xml`, `probes.log`, `VipSessionReauditProbeTest.kt` |
| ADB | Daemon khởi động được, danh sách thiết bị trống | `adb devices` thực chạy; device/Google/Play acceptance NOT RUN |

Host suite được chạy trước probe; kết quả XML mặc định của Gradle sau đó thuộc probe run. Bản tóm tắt suite đã lưu riêng, không cộng probe vào 1.023. Các lệnh PowerShell ghi log rồi in tail có exit code của lệnh cuối; kết luận test dựa vào Gradle log và XML, không dùng exit code của wrapper shell.

## G01 — P2: Login khách thành công làm mất UPGRADE/RESTORE

- `MoreFragment.kt:378–388` lưu generation trước login; hai success paths tại `:96` và `:356` truyền generation **sau** commit.
- `AppAuthManager.kt:793–794`: guest null → A gọi `notifyUserSessionChanged`, tăng generation hợp lệ.
- `VipLoginContinuationHandler.kt:82`: khác generation thì reset và bỏ action; không phân biệt chuyển phiên do chính login thành công với phiên bị thay ngoài luồng.
- **PROBE FAIL P01/P02:** dùng production commit thật và production continuation handler; expected UPGRADE/RESTORE, actual null. Không giả lập tăng generation bằng tay.
- Tác động: khách bấm mua/khôi phục rồi login thành công nhưng không được tiếp tục, phải thao tác lại. Cả Credential Manager và fallback trong More cùng mắc wiring này.
- Yêu cầu sửa: chấp nhận đúng chuyển phiên của originating attempt; không đơn giản bỏ guard generation hay truyền -1 để vượt kiểm tra.

## G02 — P1/P2: PDF và ID-card bỏ qua owner binding và mất action Khôi phục

- `PdfViewerActivity.kt:134` và `IdCardComposeActivity.kt:133` vẫn gọi `signInWithGoogle` không truyền expectedOwnerId, dù có thể được gọi lúc A còn profile nhưng token hết hạn.
- `PdfViewerActivity.kt:182–185` và `IdCardComposeActivity.kt:181–183` request continuation mặc định UPGRADE, không truyền generation. Các VipUpgradeDialog tại `PdfViewerActivity.kt:208`/`IdCardComposeActivity.kt:200` chỉ truyền callback thường, thiếu callback theo action.
- `VipUpgradeDialog.kt:218–225`: restore cần auth rơi về callback thường khi không có action callback. Hai host luôn mở nâng cấp sau login, thay vì khôi phục.
- **PROBE FAIL P03:** production unbound attempt cho phép commit B đè A. **CONTROL C02 PASS:** cùng dữ liệu qua bound reauth bị chặn, chứng minh bảo vệ chỉ có hiệu lực khi caller truyền đúng context. Probe kiểm tra auth boundary; omission ở host là CODE VERIFIED, chưa bấm Activity thật trên máy.
- Tác động P1: chọn nhầm B trong xác thực lại có thể đổi tài khoản ứng dụng giữa flow tài liệu. Chưa khẳng định receipt/tài liệu đã bị chuyển chủ; đó là rủi ro cần test thêm, không phải evidence hiện có.
- Tác động P2: RESTORE trong các host này bị thay thành UPGRADE. CODE VERIFIED.

## G03 — P1/P2: Backend auth-required chưa dẫn tới recovery đúng

### Sau Google Play có receipt (P1)

- `PlayPurchaseVerifier.kt:413–420` gom HTTP 401/403 thành `VerificationResult.TransientError`.
- `BillingManager.kt:978–981` biến kết quả này thành thông báo “Lỗi kết nối khi xác thực”, không phát event auth-required để host reauth.
- **PROBE FAIL P06:** credential có exp tương lai, transport giả trả 401; actual `TransientError(cause=null, message=Authentication required)`. Không gọi mạng thật.
- Người dùng có thể đã có receipt nhưng không hoàn tất xác thực qua flow này. Silent sync/restore sau đó có thể recover; chưa chứng minh mất vĩnh viễn quyền mua. Phải giữ receipt và retry verification/reconciliation, không mở giao dịch mua mới.

### Restore bắt đầu từ Trang chủ, token cục bộ còn hạn (P2)

- Dialog phát RESTORE auth-required → Home chuyển tới More qua `navigateToMoreForVipSignIn`.
- `MoreFragment.kt:73–78` chỉ mở sign-in nếu profile null hoặc local token expired; token còn hạn nhưng backend 401 thì gọi thẳng `executeVipContinuation`.
- `MoreFragment.kt:398–410` restore lại mà không truyền `onAuthRequired`, do đó có thể dùng lại cùng credential bị backend từ chối rồi chỉ toast lỗi. Không có force-reauth reason/context đi qua navigation.
- CODE VERIFIED; chưa chạy end-to-end navigation trên thiết bị.
- Yêu cầu sửa: phân biệt auth failure với network và forbidden/owner conflict; retry có giới hạn, giữ expected owner và receipt, không coi mọi 403 là hết hạn.

## G04 — P2: Coordinator chưa khóa callback theo từng thao tác

- `VipPurchaseActionCoordinator.kt:201,218,256` callback connect/query kiểm tra session/token nhưng không kiểm tra action ID hoặc terminal state của action.
- `:308` tạo terminalHandled bên trong từng `executeLaunch`; callback query bị gọi hai lần sẽ tạo hai terminal guards độc lập.
- **PROBE FAIL P04:** phát product callback hai lần → launcher được gọi 2 thay vì 1.
- **PROBE FAIL P05:** query của thao tác 1 trả false, thao tác 2 bắt đầu; callback true trễ của thao tác 1 vẫn launch và xóa busy của thao tác 2.
- Đây là **fault injection ở seam launcher**, xác nhận vi phạm invariant once-per-action trong coordinator, không chứng minh Google SDK thực tế đã gửi callback trùng hoặc đã tính tiền hai lần. Giữ mức P2 phòng vệ/race, không diễn giải thành giao dịch trùng trên Play đã xảy ra.
- Yêu cầu sửa: operation identity/terminal ownership xuyên toàn thao tác, cleanup cũ không ảnh hưởng thao tác mới; kiểm tra host/session lại ở callback.

## G05 — P2 kiểm chứng: tests F02/F04/F05 chưa kiểm tra wiring thật

- `VipSessionExpiryRegressionTest.kt:222–240`: tự tạo callback trong test và tự tính hasSignInCallback rồi gọi coordinator, không tạo/click AccountDetailDialog.
- `:255–272`: sao chép biểu thức OR trong test, không đi qua VipUpgradeDialog.
- `:295` test handler trực tiếp, không đi qua MoreFragment và không có production login thay generation. Vì vậy bỏ sót G01.
- Các test có thể xanh khi production wiring tương ứng bị tháo. Báo cáo PASS của E03 chưa đáp ứng yêu cầu click thật/seam production trong kế hoạch.
- `docs/vip-session-20261004/PROGRESS.md` cũng chưa có. Đây là thiếu evidence bàn giao, không phải nguyên nhân app lỗi.

## G06 — P2 UI: chuỗi reauth mới hiện tiếng Việt ở locale khác

- `app/src/main/res/values/strings.xml:635` chứa tiếng Việt cho `vip_session_expired_reauth_prompt`, kèm ignore MissingTranslation.
- Search key trong resources chỉ có values và values-vi. Locale khác fallback về tiếng Việt. Không đáp ứng E03 về các ngôn ngữ đang hỗ trợ.
- CODE VERIFIED; chưa render từng locale trên thiết bị.

## Những điểm đã sửa đúng / không mở lại vô cớ

- AccountDetailDialog hai nút gọi cùng helper truyền đầy đủ callback.
- VipUpgradeDialog nhận callback action-only bằng OR.
- More Credential Manager success dùng handler theo action; lỗi cũ “luôn UPGRADE” đã sửa tại đây, nhưng generation mới gây G01.
- Bound same-owner reauth và bound foreign-owner rejection qua production commit: **C01/C02 PASS**.
- Không phát hiện việc gỡ token expiry guard để vượt Billing. Không cần thay OAuth/signing/R8 cho các lỗi đã xác định.
- TargetProductId có trường lưu nhưng More chưa consume product; hiện dialog mua hardcode yearly, nên ghi là chưa hoàn thiện contract mở rộng, không coi là lỗi chọn sai nhiều gói đã tái hiện.

## Chạy lại probe

```powershell
$env:JAVA_HOME='C:\Users\nguye\.jdks\openjdk-21.0.1'
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
.\gradlew.bat :app:testDebugUnitTest --offline -I docs/vip-session-reaudit-20261004/audit.init.gradle --tests 'com.tscanner.app.VipSessionReauditProbeTest' --console=plain
```

Init script chỉ thêm probe vào test source set khi được chỉ định; không sửa build.gradle và không làm bộ test mặc định luôn đỏ. Khi khắc phục host G02, cần bổ sung test host thực; probe P03 minh họa hậu quả của API **unbound** không nhất thiết phải đổi API sign-in thông thường để làm nó xanh. Thay probe đó bằng test caller đã bound hoặc giữ nó như control có nhãn rõ ràng; không ép cấm account switching hợp lệ toàn app.

Kế hoạch sửa tiếp: `PLAN_FIX_VIP_SESSION_ROUND2_GEMINI_2026-10-04.md`.
