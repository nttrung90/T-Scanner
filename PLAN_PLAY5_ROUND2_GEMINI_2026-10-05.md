# Kế hoạch Gemini sửa PLAY5 vòng 2 — 05/10/2026

Đọc trước: `RECHECK_PLAY5_ROUND2_2026-10-05.md` và kế hoạch gốc `PLAN_PLAY_5_WARNINGS_2026-10-05.md`. Đây là gói bàn giao sửa lỗi; người kiểm tra chưa thay production. Không reset/stash/checkout đè thay đổi sẵn có, không upload/publish. Không tự gỡ khóa delegate ML Kit, đổi signing/endpoint, hay nâng toàn bộ dependency để giảm cảnh báo.

## Quy trình bắt buộc

Thực hiện tuần tự **R00 -> R01 -> R02 -> R03 -> R04 -> R05 -> R06**. Mỗi gói có một owner; các gói cùng file phải đợi bàn giao, không sửa chồng. Chỉ làm phần đã được người dùng giao/phê duyệt. Tạo REPORT_PLAY5_Rxx.md gồm file thay đổi, bug trước sửa, đường production được test, lệnh/kết quả và phần chưa kiểm chứng. Host pass không thay cho device acceptance. Nếu thiếu thiết bị vẫn có thể hoàn thành source/test host trong phạm vi được giao, nhưng phải để runtime gate OPEN.

## R00 — Chuẩn hóa bằng chứng và baseline

Scope: REPORT_PLAY5_G00.md, G03c.md, G04.md, G05.md, INTEGRATION_SUMMARY.md và báo cáo mới; không chỉnh app.

- Sửa số bytes/hash; không gọi 55,4% hoặc 99,8% là kết quả đo thực. Đối chiếu hash bằng công cụ, tách mapping hash khỏi AAB hash.
- Phân loại trạng thái SOURCE_DONE / HOST_VERIFIED / DEVICE_PENDING / PLAY_PENDING / SDK_BLOCKED theo bằng chứng, bỏ tuyên bố hoàn tất trọn vẹn và production-ready khi gate mở.
- Ghi local baseline là candidate khớp ký hiệu, cần xác nhận Play version/track nếu chưa có. Phân tích c5 theo method/nhánh DEX sau class merging, không chỉ header. Không sửa auth/bitmap pipeline theo suy đoán.
- Bảo toàn baseline và release hiện có trước build release mới; lưu metadata không chứa secret.

Acceptance: mỗi số đo có file/hash/lệnh; không suy luận performance từ phép tính lý thuyết; các gate chưa chạy giữ OPEN. Dừng mở rộng nếu thiếu artifact Play, vẫn bàn giao phần xác minh local.

Prompt: “Đọc PLAN_PLAY5_ROUND2_GEMINI_2026-10-05.md và thực hiện R00. Chỉ sửa báo cáo bằng bằng chứng kiểm tra lại; phân tích R8 merged methods cẩn thận. Bàn giao REPORT_PLAY5_R00.md.”

## R01 — Crop save có owner và commit một lần (F01)

Owner: CropRotateActivity.kt, state/IO coordinator mới dưới ui/editor nếu cần, regression tests crop. Không chỉnh SafeFileWriter toàn dự án nếu chưa chứng minh lỗi ở utility.

- Giữ trạng thái loading/editing/saving/committed và operation token qua Activity recreation; snapshot crop/rotation/source phải bất biến trong operation.
- Bitmap dùng bởi IO không được recycle từ Activity hoặc thao tác rotate. Khóa nút xung đột trong lúc save nhưng không coi disable UI là giải pháp duy nhất cho lifecycle.
- Tránh đọc file đã crop rồi áp lại edit cũ. Có staging/source revision/result persistence phù hợp; commit và trả kết quả đúng một lần, caller nhận được output hợp lệ sau rotation.
- Xử lý cancellation có chủ ý; không nuốt CancellationException thành lỗi chung. Chọn owner độc lập Activity cho IO sống qua rotation, hoặc cơ chế hủy an toàn trước khi giải phóng tài nguyên. Không khóa màn hình dọc để né lỗi.

Regression bắt buộc: giữ IO ở trước crop, trước commit, sau commit-trước-delivery; recreate ở mỗi mốc. Thử full crop (Bitmap.createBitmap có thể trả cùng instance), crop một phần, xoay ảnh 90°, rotate/cancel trong lúc save, save thất bại. Dùng latch/seam production, không sleep ngẫu nhiên.

Acceptance: không bitmap-recycled race, không double rotate/crop, không mất RESULT_OK, old file còn nguyên khi write thất bại, output chính xác về pixel/kích thước. Host test state machine phải gọi production coordinator; instrumentation cho Activity/Bitmap/caller. Thiếu device thì ghi chưa xác nhận runtime.

Prompt: “Đọc kế hoạch vòng 2, sửa duy nhất R01/F01. Thiết kế ownership của crop-save qua rotation, test các mốc IO thực, giữ nguyên luồng caller và dữ liệu cũ. Bàn giao REPORT_PLAY5_R01.md.”

## R02 — Restore crop khi decode chưa xong (F02)

Owner: CropRotateActivity.kt, CropOverlayView.kt và test crop; bắt đầu sau R01.

- Saved state lấy pendingNormalizedCropRect khi overlay chưa được dựng, không ghi full rect mặc định đè state chưa apply.
- Phân biệt chưa có bounds với người dùng chủ động chọn toàn ảnh. Lưu cả rotation và crop cùng phiên bản ảnh theo thiết kế R01.
- Rà layout thay đổi khi chưa recreate: cập nhật bounds theo kích thước view thực, giữ normalized crop; không reset selection tùy tiện.

Regression: crop [0.1,0.2,0.8,0.9] -> recreate giữ decode -> save state/recreate lần nữa -> release decode. Kiểm tra vùng và góc; thêm reset-to-full, ảnh lỗi, resize sau khi layout đã hoàn tất.

Acceptance: selection giữ nguyên qua ít nhất 3 lần recreate khi IO chậm và sau resize; test phải đi qua state production, không chỉ tự nhân/chia float trong test.

Prompt: “Đọc kế hoạch vòng 2 và bàn giao R01, sửa R02/F02. Kiểm thử pending crop qua decode chậm và nhiều lần recreate, không viết lại thuật toán riêng trong test. Bàn giao REPORT_PLAY5_R02.md.”

## R03 — Clear avatar khi fallback (F03)

Owner: MoreFragment.kt, NetworkImageLoadingPolicyTest.kt hoặc test UI mới; AccountDetailDialog chỉ sửa nếu test chứng minh vấn đề liên quan.

- Clear request trên đúng target trước setImageResource ở logged-out và photoUrl rỗng. Giữ cleanup onDestroyView; không thêm global clear cache.
- Kiểm tra A có URL -> logout -> B không URL, A -> B có URL, destroy view giữa request. Giữ mục tiêu ảnh theo mật độ/kích thước view phù hợp, không đánh đổi độ nét không cần thiết.

Regression: delayed image loader/HTTP response điều khiển được, render state production rồi cho request A hoàn tất. Acceptance: fallback/B vẫn hiện đúng; không view callback sau destroy. Thay test tự tính RAM/trim URL bằng test lifecycle/target hoặc ghi rõ chúng không phải acceptance evidence.

Prompt: “Đọc kế hoạch vòng 2, sửa R03/F03 trên MoreFragment và regression target. Không đổi auth/session/backend. Chứng minh completion muộn không ghi lại ảnh A. Bàn giao REPORT_PLAY5_R03.md.”

## R04 — Hoàn thiện insets OCR (F04)

Owner: OcrResultActivity.kt, activity_ocr_result.xml và test UI; fragment text/table chỉ khi cần tránh áp trùng và phải ghi rõ.

- Bảo vệ search bar, language banner, content ba tab bằng inset ngang. Chọn owner cha chung hoặc các sibling thích hợp, không áp hai lần.
- Giữ nền vẽ tràn viền, kiểm tra toolbar editor/touch target/table scroll trong phần nội dung. Test IME trên API cũ và mới, chỉ đổi manifest softInputMode khi có bằng chứng cần thiết.

Regression: inject left/right inset khác nhau và nav ở cạnh, mở đủ 3 tab/search; đo global bounds vùng bấm. Dispatch lặp 10 lần và đổi orientation; thử font 200%/IME.

Acceptance: tất cả nội dung tương tác nằm ngoài vùng cutout/nav, không co vùng nội dung hai lần, editor còn đủ khả năng cuộn. Bắt buộc có integration test inflation/listener hoặc device screenshots, test phép max không đủ.

Prompt: “Đọc kế hoạch vòng 2, sửa R04/F04 cho OCR với một owner insets rõ ràng. Chứng minh các sibling content/search/banner được bảo vệ và không cộng lặp. Bàn giao REPORT_PLAY5_R04.md.”

## R05 — Loại spacing cộng hai nguồn (F05)

Owner: listener insets ở 8 Activity và layout tương ứng; chỉ spacing, không đổi business logic. Chờ R01/R02/R04 bàn giao các file dùng chung.

- Lập bảng XML padding gốc, extra đang cộng, giá trị mong muốn mỗi màn. Chọn XML hoặc extra làm nguồn spacing, không giữ hằng số cũ khi đã cộng initial padding trừ khi có quyết định thiết kế rõ ràng.
- Rà cả IdCard/Viewer/PostScan/DocumentManagement, không chỉ ba ví dụ. Giữ max(nav,IME) và tính idempotent.

Regression: inflate layout thật với density biết trước, gọi production listener/helper; assert spacing khi inset=0 và khi có inset, rồi 10 dispatch. Landscape cửa sổ thấp/IME phải còn vùng thao tác. Không viết loop cộng số giả trong test.

Acceptance: bảng spacing được đáp ứng; không thừa 12/24dp do migration; không mất safe area. Nếu tăng padding là chủ ý, ghi trade-off được chấp thuận thay vì tự đổi thiết kế.

Prompt: “Đọc kế hoạch vòng 2 và các bàn giao trước, thực hiện R05/F05. Rà XML+extra trên 8 Activity, kiểm thử layout/helper thật và chỉ sửa spacing cần thiết. Bàn giao REPORT_PLAY5_R05.md.”

## R06 — Nghiệm thu release và các gate còn mở

Owner: tests/báo cáo/artifact thư mục build; không tự chỉnh production ngoài gói đã bàn giao.

1. Chạy focused regression R01-R05, sau đó :app:testDebugUnitTest :app:lintDebug :app:assembleDebug. Ghi failures/errors/skips và phân biệt warning mới/cũ.
2. Chạy :app:lintRelease :app:bundleRelease sau khi bảo toàn artifact. Kiểm tra shrink task/resources report, mapping/AAB SHA-256, merged manifest và keep dynamic resource; không coi keep.xml là bằng chứng resource injection tồn tại.
3. Nếu cần đo riêng hiệu quả shrink, so hai build cùng source/dependencies/config/ABI chỉ khác shrinking trong môi trường được giao; không dùng bản 21 vs 22 để quy tác dụng riêng cho R8.
4. Device: API26 smoke; Android14/15/16 dọc/ngang gesture/3-button/cutout; Android16 tablet/foldable >=600dp và split-screen. Kiểm tra crop lúc lưu, camera EXIF/preview, OCR/text/table undo, ID draft, Viewer/PostScan resize tại chỗ, IME/font200%, 8 locale.
5. Release smoke login/Drive/VIP, scanner/QR/OCR/PDF; đo avatar RAM qua 20 vòng trên cùng thiết bị. Ghi cả Google Play services/model version.
6. Giữ 2 ML Kit portrait là SDK gate chưa đóng nếu chưa có bản SDK phù hợp và device evidence. API cũ trong dependency cần đánh giá branch SDK/DEX cụ thể; không tuyên bố hết Play warnings từ source grep.
7. Chỉ upload khi được người dùng giao riêng; kiểm tra lại đúng versionCode/track sau Play analysis. Thiếu device/Play access: báo PARTIAL, không ghi production-ready.

Acceptance cuối: F01-F05 có regression production đạt; release và device có bằng chứng; các SDK/Play gate hoặc đã đạt hoặc được ghi rõ chưa giải quyết. Không tuyên bố hoàn tất 5 cảnh báo nếu chỉ host pass.

Prompt: “Đọc kế hoạch vòng 2 và REPORT_PLAY5_R00-R05, thực hiện R06. Kiểm chứng độc lập, phân biệt host/device/Play, không upload. Lập REPORT_PLAY5_ROUND2_FINAL.md với mục đạt, lỗi còn lại và gate chưa chạy.”
