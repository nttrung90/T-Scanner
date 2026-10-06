# Kế hoạch xử lý 5 cảnh báo Google Play — 05/10/2026

Trạng thái: chỉ điều tra và lập kế hoạch; chưa sửa production/config release. Thực hiện sau khi người dùng phê duyệt. Không reset/checkout đè thay đổi hiện có. Các đường dẫn dưới đây tính từ E:/DU AN AI/T-Scanner.

## 1. Kết quả đối chiếu hiện tại

| Cảnh báo | Bằng chứng trong checkout | Kết luận và ưu tiên |
|---|---|---|
| Edge-to-edge | Cả 8 Activity đã gọi enableEdgeToEdge; MainActivity.kt:42, CameraScanActivity.kt:99, CropRotateActivity.kt:38, PostScanEditorActivity.kt:61, OcrResultActivity.kt:88, DocumentManagementActivity.kt:47, PdfViewerActivity.kt:389, IdCardComposeActivity.kt:360 | Không thêm lệnh một cách máy móc. Ưu tiên cao: xử lý đầy đủ insets và kiểm thử UI. |
| API cửa sổ lỗi thời | app/src/main/res/values/themes.xml:12,14 còn statusBarColor/navigationBarColor. Không thấy lệnh setStatusBarColor/setNavigationBarColor/SHORT_EDGES trực tiếp trong app/src/main/java qua tìm kiếm hiện tại | Chưa xác định owner của các call site trong bản Play; có thể thuộc dependency. Truy nguồn trước khi đổi thư viện. |
| Khóa hướng | app/src/main/AndroidManifest.xml khóa portrait cho 6 Activity của app; merged release manifest hiện có thêm 2 delegate ML Kit | Ưu tiên cao: chuẩn bị layout/state trước khi gỡ khóa; tách SDK khỏi Activity tự quản lý. |
| Bitmap mạng | MoreFragment.kt:286 và AccountDetailDialog.kt:49 đã dùng Glide 4.16.0; decodeStream hiện tìm thấy trong OcrReaderViewModel.kt:960,978 và TesseractOcrHelper.kt:375,383 với options | Chưa tái hiện tải bitmap mạng thủ công như cảnh báo. Không thay pipeline OCR bằng Glide chỉ để xóa cảnh báo. |
| Resource shrinking | app/build.gradle: release có minifyEnabled true và proguard-android-optimize.txt, chưa khai báo shrinkResources; build.gradle dùng AGP 9.3.0 | Bổ sung resource shrinking có kiểm chứng cấu hình hiệu lực; R8 code shrinking đã bật. |

Snapshot nguồn: compileSdk/targetSdk 36, minSdk 26, versionCode 22, versionName 1.2.5. Merged manifest local cũng ghi 22/1.2.5; không đồng nghĩa đó là bản Play đang cảnh báo.

Các listener insets tại MainActivity.kt:55, CameraScanActivity.kt:285, CropRotateActivity.kt:46, PostScanEditorActivity.kt:91, OcrResultActivity.kt:97, DocumentManagementActivity.kt:56, PdfViewerActivity.kt:405, IdCardComposeActivity.kt:375 chủ yếu lấy top/bottom và giữ padding trái/phải. Đây là bằng chứng thiếu xử lý cạnh trong các listener, chưa phải lỗi UI đã tái hiện. Main/PostScan còn adjustPan; cần kiểm tra bàn phím thay vì tự động thay tất cả thành adjustResize.

Merged-manifest blame chỉ ra portrait của GmsDocumentScanningDelegateActivity đến từ play-services-mlkit-document-scanner:16.0.0; GmsBarcodeScanningDelegateActivity từ play-services-code-scanner:16.1.0. Activity/Material hiện khai báo 1.9.3/1.12.0; cần lấy resolved dependencies trước quyết định nâng.

Mapping local app/build/outputs/mapping/release/mapping.txt ánh xạ k8 thành AppCompatToggleButton, c5 thành AppAuthManager synthetic lambda, az0 thành MaterialCalendar$5. Các tên này chưa chứng minh tương ứng các phương thức trong cảnh báo. Tuyệt đối không dùng mapping của build khác để kết luận owner.

## 2. Điều kiện đầu vào G00 — đúng artifact, đúng baseline

Phạm vi: báo cáo/bằng chứng, không sửa mã. Ghi versionCode bản bị cảnh báo, track, ngày phân tích, SHA-256 AAB và mapping đi kèm; lưu bản sao artifact/mapping trước khi build ghi đè outputs. Đối chiếu mapping nằm trong AAB nếu có và Retrace/class-method mapping của az0.G, d70.b, f70.b, f.s, c5.invokeSuspend, k8.l, co0.c, jb1.b. Ghi app hay thư viện, phiên bản dependency, đường gọi; phương thức thiếu line number có thể cần xem DEX của đúng artifact.

Acceptance: bảng owner có bằng chứng hoặc đánh dấu UNKNOWN; không đoán. Thiếu bản Play chỉ chặn kết luận về call site/đóng cảnh báo, không chặn các cải tiến đã xác nhận trong checkout. Chụp baseline UI và đo RAM/kích thước bản release trước thay đổi.

## 3. Năm việc triển khai

### G01 — Hoàn thiện edge-to-edge và insets

Owner files: 8 Activity nêu trên và layout tương ứng; helper insets mới nếu thực sự giảm lặp. Không sửa manifest/build trong gói này.

- Giữ enableEdgeToEdge; thống nhất vùng nền được vẽ tràn viền và vùng nút/nội dung cần tránh system bars, display cutout, caption bar khi có.
- Xử lý cả bốn cạnh theo kích thước cửa sổ thực. Lưu padding/margin gốc, tránh cộng dồn sau nhiều lần dispatch, không áp cùng inset hai lần ở cha/con.
- Với màn nhập OCR, tên file, dialog: kiểm tra IME và khả năng cuộn tới ô/nút đang dùng; không cộng nav+IME hai lần. Chọn windowSoftInputMode dựa trên kết quả thử, thay đổi manifest chuyển cho gói G03.
- Giữ tương phản icon system bar, scrim 3-button khi cần; kiểm tra dialog/bottom sheet và nền camera riêng.

Regression: landscape với cutout bên trái/phải; nav 3 nút ở cạnh; mở/đóng IME và dispatch insets 10 lần. Acceptance: toàn bộ nút bấm/toolbar/trường nhập nằm trong vùng thao tác được, không che chữ hoặc tăng padding, nền vẫn tràn viền. Có ảnh trước/sau trên Android 14, 15, 16; API 26 smoke. Host test chỉ hữu ích cho phép tính inset; UI cần instrumentation/device. Dừng và báo nếu chưa có thiết bị, không tự đánh dấu đạt.

### G02 — Loại API lỗi thời theo owner

Owner files: themes.xml và các theme qualifier liên quan, app/build.gradle phần dependency; chỉ chỉnh Activity nếu G00 chứng minh có call của app và G01 đã bàn giao.

- Rà theme variants và compiled resources; bỏ các thuộc tính màu thanh hệ thống dư thừa khi enableEdgeToEdge đã quản lý, giữ nền bằng View của ứng dụng nếu cần.
- Truy chính xác call site từ G00. Với AndroidX/Material/ML Kit: xem changelog và chọn bản ổn định tương thích; nâng từng nhóm, kiểm tra resolved graph. Không tự nâng AGP/Kotlin/SDK toàn dự án.
- Không gỡ enableEdgeToEdge hoặc thêm opt-out để che cảnh báo. Lời gọi API cũ có nhánh tương thích Android cũ trong dependency không đồng nghĩa lỗi trên Android 15; ghi rõ điều kiện SDK và nguồn.

Regression: theme sáng/tối, khởi động, dialog, gesture/3-button API 26/34/35/36. Acceptance: app không còn tự dùng API không cần thiết; dependency call có bản sửa hoặc bằng chứng upstream/chưa giải quyết. Kiểm tra lại DEX đúng release; không hứa Play hết cảnh báo chỉ từ tìm kiếm source. Nếu chưa có fix upstream, ghi blocker và phương án, không patch bytecode/SDK nội bộ.

### G03 — Bỏ khóa hướng và hỗ trợ cửa sổ thay đổi

Owner files: AndroidManifest.xml; 6 Activity bị khóa và layout/state liên quan. Chia tuần tự G03a Main/DocumentManagement/OCR, G03b Camera/Crop/ID, G03c delegate ML Kit và rà Viewer/PostScan. Không làm đồng thời với G01 trên cùng file.

- Trước khi gỡ portrait, kiểm tra restore khi Activity recreate: tab/danh sách/selection, OCR văn bản-bảng-undo, camera session/capture đang chạy, ảnh hai mặt, crop/rotation chưa lưu. CropRotateActivity hiện giữ currentBitmap trong Activity và chưa tìm thấy onSaveInstanceState; đây là điểm cần thử trực tiếp.
- Lưu state nhẹ bằng ViewModel/SavedStateHandle hoặc draft thích hợp; không nhét Bitmap vào Bundle. Resize không được lặp import/capture/export hay làm mất bản nháp.
- Camera: cập nhật rotation/preview và ánh xạ touch/crop theo cửa sổ; xác minh EXIF/ảnh xuất. Layout ngang, split-screen, cửa sổ thấp phải tiếp cận được nút; tránh dựa vào kích thước màn hình vật lý.
- Gỡ portrait của 6 Activity sau khi từng nhóm đạt. Rà requestedOrientation, resizeableActivity, aspect ratio và merged manifest. Không thêm configChanges chỉ để né recreation; Viewer/PostScan đã có configChanges phải kiểm tra resize tại chỗ.
- Với 2 delegate ML Kit: ưu tiên bản SDK có hỗ trợ đã xác nhận. Nếu vẫn khóa, chỉ thử override đúng activity (tools:remove screenOrientation hoặc khai báo phù hợp) trong phạm vi gói này sau khi kiểm chứng lifecycle/result trên SDK thực; không ghi đè toàn bộ khai báo. Nếu không chứng minh an toàn, để mục này chưa hoàn tất và ghi dependency blocker, không tuyên bố đã xử lý cả 8.

Regression: xoay lúc OCR/chụp/lưu; xoay 10 lần; recreate/process death; gập/mở và split-screen. Acceptance: draft và nội dung chỉnh sửa nguyên vẹn, không thao tác trùng, preview/crop/output cùng hình học, không crash và không nút ngoài màn. Manifest release không còn hạn chế của app; delegate phải có kết quả riêng. Ưu tiên Android 16 tablet >=600dp vì app đã target 36; kiểm tra cả điện thoại và Android 15 trở xuống.

### G04 — Kiểm chứng và tối ưu ảnh mạng

Owner files: MoreFragment.kt, AccountDetailDialog.kt, test ảnh; loader khác chỉ khi G00 chứng minh. Không đổi OCR/render/export ảnh tài liệu trong gói này.

- Xác minh hai call site mạng bằng đúng mapping. Nếu bản mới đã thay bằng Glide, ghi bằng chứng và kiểm thử xác nhận, không thêm thư viện thứ hai.
- Dùng Glide theo lifecycle phù hợp và kích thước ImageView hữu hạn; tránh SIZE_ORIGINAL cho avatar. Rà hủy/clear khi đóng dialog, destroy view, đăng xuất/đổi tài khoản và nhánh URL rỗng để request cũ không ghi đè placeholder hoặc ảnh người khác.
- Giữ placeholder/error, caching phù hợp; không cache dữ liệu nhạy cảm/URL vào log. Các decodeStream local OCR đang có sampling cần đánh giá riêng nếu profiler cho thấy vấn đề.

Regression: ảnh avatar lớn, mạng chậm/lỗi, mở đóng dialog liên tục, A -> logout -> B khi request A đang chờ. Acceptance: ảnh đúng tài khoản, không callback lên view đã hủy, kích thước decode theo mục tiêu, không OOM; ghi peak memory và retained objects sau 20 vòng, so cùng thiết bị/bản baseline. Không kết luận tiết kiệm RAM bằng số ước đoán. Nếu không còn call thủ công trong artifact mới, kết quả gói có thể chỉ là xác nhận và bằng chứng.

### G05 — Bật resource shrinking và xác minh release

Owner files: app/build.gradle phần release; resource keep XML chỉ khi có bằng chứng. Không mở rộng sang thu hẹp toàn bộ proguard-rules.pro đang có trong cùng thay đổi.

- Dự án dùng legacy DSL dù AGP 9.3.0: thay đổi nhỏ dự kiến là shrinkResources true trong release, giữ minifyEnabled true/proguard rules hiện tại. Kiểm tra DSL thực tế/task hiệu lực trước build. Không đồng thời migrate sang optimization DSL mới vì có quy tắc keep khác.
- AGP >=9 không cần thêm android.r8.optimizedResourceShrinking=true chỉ để bật pipeline tối ưu. Xác nhận build task/report/AAB đã shrink, không dựa trên một dòng cấu hình.
- Rà getIdentifier tại TScannerApplication.kt:63 (billing_verifier_url): xác định resource có được inject ở release hay không và đường fallback. Nếu có tài nguyên truy cập động, giữ đúng tên bằng resource keep, không tools:keep tất cả. Proguard keep code không thay resource keep.
- Kiểm tra icon, 8 ngôn ngữ hiện hỗ trợ, layout/theme, FileProvider XML, WorkManager, ML Kit, Tesseract traineddata/native, login/Drive/VIP. Đo AAB và kích thước cài cùng cấu hình ABI; không cam kết một tỷ lệ giảm cố định.

Regression: chạy trên release đã minify+shrink, không chỉ debug. Acceptance: mapping đúng AAB, resources cần thiết còn hoạt động, smoke export PDF/OCR/camera/QR/login/Billing/Drive đạt và không Resources.NotFoundException. Giữ artifact baseline để rollback; không thay signing/credentials/endpoint.

## 4. Thứ tự, giao việc và điểm dừng

G00 -> G01 -> G02 -> G03a -> G03b -> G03c -> G04 -> G05 -> nghiệm thu. Một gói xong kiểm tra và bàn giao rồi mới sang gói phụ thuộc. Đây là kế hoạch tuần tự; chưa chạy agent triển khai. Nếu sau này chia agent, mỗi thời điểm chỉ một owner được sửa Activity/Manifest/build.gradle dùng chung.

Prompt dùng lại cho từng gói:

> Đọc PLAN_PLAY_5_WARNINGS_2026-10-05.md, thực hiện duy nhất [Gxx] đã được phê duyệt. Kiểm tra lại checkout và bằng chứng, bảo toàn thay đổi sẵn có. Chỉ sửa file thuộc gói; nếu cần file ngoài phạm vi hãy ghi phụ thuộc và dừng mở rộng. Kiểm thử hành vi cũ và mới bằng đường production, báo file thay đổi, lệnh/kết quả, bằng chứng thiết bị, hạn chế chưa kiểm tra. Viết REPORT_PLAY5_[Gxx].md, không commit/upload/publish tự động, không nhận phần kế tiếp khi tiêu chí bắt buộc chưa đạt.

## 5. Nghiệm thu tích hợp

Ma trận tối thiểu: API 26 smoke; Android 14/15/16 điện thoại gesture và 3-button, dọc/ngang, cutout; Android 16 tablet/foldable >=600dp và multi-window. Kiểm tra font 100%/200%, IME, theme, 8 locale, luồng quay lại từ Google scanner. Với runtime Google, ghi cả phiên bản Google Play services/model đã tải.

Sau triển khai: :app:testDebugUnitTest :app:lintDebug :app:assembleDebug; tiếp theo :app:lintRelease :app:bundleRelease và release APK phục vụ device smoke theo signing hiện có. Dùng output thư mục build, không ghi đè app/release artifact tham chiếu. Lưu SHA-256, versionCode, dependency graph, merged manifest, mapping, size/memory report và screenshot ma trận.

Chỉ sau phê duyệt phát hành riêng mới upload đúng AAB lên track thử nghiệm. Kiểm tra lại 5 mục Play Console theo đúng versionCode sau khi Google phân tích. Phân biệt: sửa source xong / device đạt / Play hết cảnh báo. Không gọi hoàn tất nếu còn SDK blocker hoặc chưa có kết quả Play.

## 6. Kiểm tra đã thực hiện khi lập kế hoạch

- Đã đọc cấu hình, manifest nguồn và merged release/blame hiện có, tìm API/insets/bitmap/Glide và mapping local. Outputs có thể thuộc build trước; chưa chứng minh khớp mã hiện tại hoàn toàn.
- Gradle baseline đã thử với JAVA_HOME=C:/Users/nguye/.jdks/openjdk-21.0.1 và GRADLE_USER_HOME=C:/Users/nguye/.gradle. Dừng ở gradle-9.7.1-bin.zip.lck: Access is denied, trước khi chạy test/lint/build. Log: audit_play5_baseline_20261005.log. Không có số test pass cho lượt này.
- adb devices không khởi động được daemon (cannot connect to daemon); chưa xác minh có thiết bị khả dụng, chưa chạy thử UI/memory.
- Chưa có artifact/mapping được xác nhận của cảnh báo Play; chưa giải mã được chính xác call site người dùng đưa. Không chỉnh mã production/configuration.

## 7. Tài liệu chính thức đã đối chiếu

- [Edge-to-edge Views](https://developer.android.com/develop/ui/views/layout/edge-to-edge): enableEdgeToEdge và quản lý insets.
- [Android 15 behavior changes](https://developer.android.com/about/versions/15/behavior-changes-15): thay đổi system bars/cutout với target 35+.
- [Android 16 behavior changes](https://developer.android.com/about/versions/16/behavior-changes-16): target 36 và bỏ hạn chế trên màn hình lớn theo điều kiện nền tảng.
- [Enable R8 optimization](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization): code/resource shrinking, legacy DSL còn hỗ trợ, DSL mới AGP 9.3 và yêu cầu keep rules.
- [Glide targets](https://bumptech.github.io/glide/doc/targets.html): kích thước target và giải phóng request.
