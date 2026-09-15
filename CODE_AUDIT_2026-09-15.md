# Rà soát T-Scanner — 15/09/2026

**Trạng thái: chỉ kiểm tra và lập kế hoạch. Chưa sửa mã nguồn. Mọi hạng mục sửa dưới đây chờ chủ dự án phê duyệt.**

## Phạm vi và bằng chứng

- Đã rà soát module chạy chính `:app`: 55 file Kotlin, 12.367 dòng; cấu hình Gradle, manifest, tài nguyên và các luồng camera, nhập file, PDF, OCR, thẻ ID, QR, tài khoản, Drive, quản lý tài liệu.
- `settings.gradle` chỉ đưa `:app` vào T-Scanner. `android/`, `ios/`, `tutorials/` chứa các dự án mẫu ML Kit độc lập (337 file Java/Kotlin/Swift/Objective-C): đã kiểm kê cấu trúc và quét dấu hiệu rủi ro, **chưa review thủ công toàn bộ hoặc build/test từng dự án mẫu**. Không coi các mẫu này là đã được chứng nhận an toàn. Không cần sửa/xóa chúng để xử lý các lỗi T-Scanner bên dưới.
- Thư mục hiện tại không có Git repository; không có baseline Git để đối chiếu thay đổi trước đó.
- Chạy `gradlew.bat :app:testDebugUnitTest :app:lintDebug --offline`. Gradle được phép ghi cache ngoài sandbox; các file sinh ra là đầu ra build/lint, không phải bản sửa code.
- Compile Kotlin/Java debug thành công. Unit test trả `NO-SOURCE`; `app/src` chỉ có `main`, chưa có bộ test của ứng dụng.
- Lint thất bại: **116 errors, 699 warnings**. 116 errors gồm 102 MissingTranslation, 5 StringFormatInvalid, 6 UseAppTint, 3 MissingPermission. Số thông báo không tương đương số lỗi chức năng độc lập.
- [Báo cáo lint HTML](<E:/DU AN AI/T-Scanner/app/build/reports/lint-results-debug.html>) và [báo cáo lint dạng text](<E:/DU AN AI/T-Scanner/app/build/intermediates/lint_intermediate_text_report/debug/lintReportDebug/lint-results-debug.txt>).
- Chưa chạy trên điện thoại/emulator, chưa gọi API Drive bằng tài khoản thật, chưa giao dịch thanh toán, chưa xác nhận bản release/khả năng chạy native trên thiết bị 16 KB. Các kịch bản dưới đây là suy luận từ luồng code, trừ kết quả lint và các phép kiểm tra logic được ghi rõ.

## A. Lỗi ưu tiên cao — P1

### A01. Lưu/tải tài liệu trùng tên ghi đè PDF cũ

**Vị trí:** `PdfViewerActivity.kt:529`, `CloudBackupManager.kt:167`, `IdCardComposeActivity.kt:528`, `PdfConverterHelper.kt:30`.

Tên file vật lý được tạo từ tiêu đề hoặc thời gian chỉ đến phút. Lưu hai bản quét cùng tên, lưu hai thẻ ID trong một phút hoặc tải hai file Drive cùng tên sẽ dùng cùng đường dẫn. Code xóa/ghi đè file đó nhưng tạo hai `DocumentItem` có ID khác nhau. Xóa một bản ghi còn có thể xóa PDF đang được bản ghi kia dùng.

**Kế hoạch:** định danh file nội bộ bằng document ID; tách tên hiển thị khỏi đường dẫn; tạo tên xuất duy nhất hoặc yêu cầu người dùng chọn ghi đè rõ ràng. Kiểm tra và chuyển đổi các tham chiếu trùng đang tồn tại trước khi cho phép xóa.

**Nghiệm thu:** lưu/tải hai tài liệu cùng tên, nội dung khác nhau; mở lại từng tài liệu đúng nội dung; xóa một tài liệu không ảnh hưởng tài liệu còn lại.

### A02. Tạo lại PDF xóa bản gốc trước khi biết có thể tạo bản mới

**Vị trí:** `PdfConverterHelper.kt:30`, `PdfViewerActivity.kt:73`.

`createPdfFromImages` xóa output ngay đầu hàm, bỏ qua ảnh không đọc được và vẫn trả thành công nếu còn ít nhất một trang. Luồng crop gọi hàm này trực tiếp lên PDF đang lưu và bỏ qua kết quả Boolean. Khi thiếu ảnh hoặc ghi lỗi, có thể mất PDF gốc, mất một số trang, nhưng vẫn hiện thông báo cập nhật thành công.

**Kế hoạch:** xác thực toàn bộ đầu vào; ghi PDF vào file tạm; kiểm tra đủ trang và đóng tài nguyên; chỉ thay bản gốc khi hoàn tất. Trả lỗi có nguyên nhân, không thông báo thành công khi thất bại.

**Nghiệm thu:** ảnh trang 2 bị mất, bộ nhớ đầy, lỗi ghi giữa chừng: PDF cũ giữ nguyên, không báo lưu thành công.

### A03. Lưu bản quét xong xóa ảnh mà viewer vẫn sử dụng

**Vị trí:** `PdfViewerActivity.kt:581`, `:298`, `:536`.

Sau lưu, `deleteTempSession` xóa ảnh trong `renderedPagePaths`; danh sách và adapter vẫn giữ đường dẫn cũ. OCR chỉ render PDF nếu danh sách rỗng, nên không phục hồi được trường hợp danh sách có phần tử nhưng file đã mất. Crop và tạo PDF tiếp cũng nhận ảnh đã xóa.

**Kế hoạch:** sau lưu, chuyển viewer sang nguồn PDF đã lưu; tạo lại preview trong thư mục riêng rồi cập nhật adapter trước khi dọn session. Xác thực file tồn tại, không chỉ kiểm tra độ dài danh sách.

**Nghiệm thu:** quét → lưu → cuộn trang → OCR → crop → xuất tiếp, ngay trong cùng màn hình, đều hoạt động.

### A04. Chụp liên tiếp có thể ghi hai ảnh vào cùng một trang

**Vị trí:** `CameraScanActivity.kt:241`, `:251`, `:254`, `:266`, `:417`.

`photoIndex` lấy từ số trang đã xử lý xong. Shutter được mở lại trước khi auto-crop kết thúc và trước khi thêm đường dẫn. Bấm tiếp lúc đó nhận cùng index, cùng file `page_N.jpg`. Nút hoàn tất cũng không đợi các ảnh còn đang xử lý.

**Bằng chứng logic:** lúc danh sách có 0 trang, cả lần chụp thứ nhất và lần thứ hai trước khi lần một xử lý xong đều nhận index 1.

**Kế hoạch:** cấp ID/chỉ số ngay khi nhận lệnh chụp; quản lý số tác vụ đang chờ; thu kết quả theo thứ tự chụp; hoàn tất chỉ sau khi các tác vụ đã kết thúc; giữ ảnh gốc nếu xử lý thất bại.

**Nghiệm thu:** chụp nhanh 10 trang có số thứ tự, nhấn hoàn tất khi đang cắt; không mất, trùng hoặc đảo trang.

### A05. Worker đánh dấu sao lưu thành công giả với email chứa `demo`

**Vị trí:** `GoogleDriveBackupWorker.kt:56`.

Chỉ cần email hoặc ID chứa chuỗi `demo`, worker bỏ qua upload, tạo `drive_mock_*` và đánh dấu SYNCED. Điều kiện này áp dụng cả tài khoản thật; bản release không loại bỏ nhánh demo. Dữ liệu chưa sao lưu nhưng giao diện nói đã sao lưu.

**Kế hoạch:** cô lập mock trong debug/test, dùng loại tài khoản rõ ràng; release chỉ đánh dấu SYNCED sau phản hồi upload thật. Rà lại bản ghi có `drive_mock_*` để đánh dấu chưa sao lưu và cho phép sao lưu thật.

**Nghiệm thu:** tài khoản thật có `demo` trong email vẫn upload; bản mock không hiển thị là bản sao Drive có thể phục hồi.

### A06. Công việc sao lưu không gắn với chủ tài khoản

**Vị trí:** `CloudBackupManager.kt:46`, `GoogleDriveBackupWorker.kt:38`, `AppAuthManager.kt:252`.

Work input chỉ có document ID, đường dẫn và tiêu đề. Worker lấy người dùng hiện tại khi chạy. Đăng xuất không hủy công việc; danh mục không có owner. Vì vậy job xếp hàng dưới tài khoản A có thể upload vào Drive tài khoản B sau khi đổi tài khoản và B có VIP. Đây là nguy cơ đưa tài liệu đến sai tài khoản cloud.

**Kế hoạch:** gắn owner ID vào bản ghi/job, kiểm tra owner trước upload và trước cập nhật kết quả; hủy/dừng job khi đổi tài khoản; phân định tài liệu trên thiết bị với tài liệu thuộc cloud của từng tài khoản. Không tự xóa tài liệu local khi đăng xuất.

**Nghiệm thu:** xếp hàng offline bằng A, đổi sang B rồi bật mạng; tài liệu A không tự upload vào B.

### A07. Quyền VIP được kích hoạt ngay, chưa có xác minh giao dịch

**Vị trí:** `VipUpgradeDialog.kt:37`, `AppAuthManager.kt:283`, `app/build.gradle`.

Nút có giá 20.000 đ/năm gọi trực tiếp `setUserVipTier`, lưu quyền vào preferences. Không có bước thanh toán hoặc xác minh purchase trong luồng hiện tại. Đây là thiếu sót nếu bản này được dùng để bán VIP; có thể là chức năng mô phỏng trong giai đoạn phát triển.

**Kế hoạch:** trước phát hành thương mại, tích hợp luồng mua và phục hồi quyền có xác minh; chỉ mở VIP sau khi giao dịch hợp lệ. Nếu chủ dự án chủ đích cho dùng thử miễn phí, đổi nội dung và tách rõ khỏi giao dịch thật. Việc chọn phương thức thanh toán cần được thống nhất trong phê duyệt.

**Nghiệm thu:** giao dịch hủy/thất bại không cấp VIP; giao dịch thành công cấp đúng tài khoản; đăng nhập trên thiết bị khác phục hồi đúng quyền.

### A08. Chỉnh sửa thẻ ID làm PDF A4 biến thành hai ảnh rời

**Vị trí:** `IdCardComposeActivity.kt:552`, `:560`, `PdfViewerActivity.kt:199`, `:73`.

PDF đã ghép một trang A4 nhưng `pagePaths` truyền sang viewer là ảnh mặt trước/mặt sau. Viewer ưu tiên các ảnh này, hiển thị như hai trang. Crop một mặt sẽ tạo lại PDF từ hai ảnh nguồn, làm mất bố cục A4 và kích thước thẻ.

**Kế hoạch:** viewer render từ PDF A4 thực tế; lưu ảnh nguồn thẻ trong trường riêng nếu cần quay lại trình ghép. Chỉnh sửa thẻ phải tái ghép theo cấu hình A4 đã lưu.

**Nghiệm thu:** ghép hai mặt → xem → crop/chỉnh → chia sẻ vẫn đúng một trang A4, đúng bố cục và kích thước.

### A09. Lưu JSON danh mục không có cơ chế thay thế an toàn

**Vị trí:** `DocumentRepo.kt:158`, `:38`.

`writeText` ghi đè trực tiếp file duy nhất. Tiến trình bị ngắt hoặc bộ nhớ đầy khi đang ghi có thể để lại JSON hỏng. Lần mở tiếp theo đọc lỗi, chỉ in stack trace và dùng danh mục rỗng/đọc dở; lần lưu kế tiếp có thể ghi đè phần dữ liệu còn lại.

**Kế hoạch:** dùng AtomicFile hoặc transaction database, lưu bản phục hồi, không công bố thành công nếu persist lỗi. Parse vào cấu trúc tạm và chỉ công bố sau khi đọc hợp lệ.

**Nghiệm thu:** ngắt ghi/làm đầy dung lượng, khởi động lại; danh mục trước đó vẫn có thể phục hồi, không tự ghi đè dữ liệu hỏng bằng danh mục rỗng.

## B. Lỗi ưu tiên tiếp theo — P2

### B01. Tài liệu chỉ có trên cloud bị xóa khỏi danh mục khi mở lại app

**Vị trí:** `DocumentRepo.kt:81`, `CloudBackupManager.kt:113`.

Catalog tạo bản ghi chỉ có driveFileId và không có file local, nhưng `loadData` chỉ giữ bản ghi có PDF/ảnh/thumbnail trên đĩa. Mở lại offline làm các mục này biến mất; online chỉ có thể bổ sung lại sau sync.

**Sửa và kiểm tra:** giữ bản ghi cloud hợp lệ, phân biệt chưa tải với orphan; đồng bộ → đóng app → mở offline phải giữ danh mục và trạng thái chưa tải.

### B02. PDF đã chỉnh sửa vẫn mang trạng thái đã sao lưu bản mới

**Vị trí:** `PdfViewerActivity.kt:73`, `DocumentRepo.kt:330`, `:348`, `GoogleDriveService.kt:uploadPdfFile`.

Crop thay nội dung file nhưng không cập nhật metadata repo, không đánh dấu cần sync và không enqueue upload. Worker chỉ tạo file mới, chưa cập nhật Drive ID cũ. `isSynced` tiếp tục làm bộ lọc bỏ qua tài liệu đã thay đổi.

**Sửa và kiểm tra:** thêm revision/hash, cập nhật size/thumbnail sau ghi thành công, đánh dấu dirty và upload cập nhật đúng driveFileId; không đánh dấu bản mới SYNCED khi worker hoàn tất bản cũ. So sánh nội dung local/Drive sau crop.

### B03. Icon trạng thái sync không cập nhật theo dữ liệu

**Vị trí:** `DocumentRepo.kt:303`, `DocumentAdapter.kt:175`.

Repo sửa trực tiếp object đã được ListAdapter giữ. List mới là shallow copy; old/new cùng tham chiếu object đã sửa, DiffUtil thấy bằng nhau nên không bind lại.

**Sửa và kiểm tra:** dùng `copy` để tạo DocumentItem mới và giữ snapshot bất biến. Quan sát LOCAL_ONLY → SYNCING → SYNCED/FAILED mà không rời màn hình.

### B04. Drive chỉ đọc trang kết quả đầu tiên

**Vị trí:** `GoogleDriveService.kt:200`.

Request không lấy `nextPageToken`, không lặp `pageToken`, nên catalog thiếu tài liệu khi kết quả được phân trang. Google mô tả trường và cơ chế này tại [Drive files.list](https://developers.google.com/workspace/drive/api/reference/rest/v3/files/list).

**Sửa và kiểm tra:** lấy mọi trang, lọc PDF, phân biệt lỗi truy vấn với danh mục rỗng; kiểm thử response nhiều trang và lỗi trang giữa.

### B05. Trạng thái VIP mất hoặc chuyển nhầm khi đăng nhập

**Vị trí:** `AppAuthManager.kt:130`, `:220`, `:271`, `:290`.

Đăng xuất xóa profile duy nhất; đăng nhập lại không có nguồn phục hồi VIP. Nhánh đăng nhập cổ điển kế thừa VIP từ profile hiện tại mà không đối chiếu account ID; chọn tài khoản khác ở luồng cấp quyền Drive có thể chuyển quyền sai. Gia hạn đặt hạn mới bằng hiện tại + 365 ngày, làm mất số ngày còn lại.

**Sửa và kiểm tra:** quản lý quyền theo account ổn định, phục hồi từ nguồn xác minh; gia hạn từ max(now, expiry). Test đăng xuất/đăng nhập lại cùng tài khoản, đổi tài khoản khi cấp quyền và gia hạn trước hạn.

### B06. Ghép PDF có trang rộng/hẹp khác nhau làm cắt mất nội dung ảnh dài

**Vị trí:** `PdfConverterHelper.kt:194`, `:213`.

Chiều cao canvas tính bằng tổng chiều cao gốc, nhưng khi vẽ lại scale từng trang đến cùng chiều rộng. Ví dụ 600×800 và 300×800: canvas cao 1.600, tổng ảnh sau scale cao 2.400; phần cuối bị cắt. Phép tính này đã được đối chiếu độc lập, chưa render trên Android.

**Sửa và kiểm tra:** tính kích thước sau scale trước khi cấp canvas, giới hạn ngân sách pixel; render từng trang rồi giải phóng để tránh giữ tất cả bitmap. Test PDF trộn trang ngang/dọc và nhiều kích cỡ.

### B07. Crop ảnh rất hẹp có thể gây IllegalArgumentException

**Vị trí:** `CropOverlayView.kt:35`, `:236`.

Kích thước tối thiểu cố định 60dp có thể lớn hơn chiều ảnh hiển thị. `coerceIn` lúc kéo cạnh sẽ có cận dưới lớn hơn cận trên. Ví dụ density=3, ảnh rộng 100px, cạnh phải 95px: khoảng clamp là [0, -85].

**Sửa và kiểm tra:** giới hạn minCrop theo kích thước hiển thị thực tế; bảo đảm mọi khoảng clamp hợp lệ. Test ảnh hóa đơn dài, panorama hẹp và màn hình nhỏ.

### B08. Luồng nhập/xuất báo thành công dù không ghi được file

**Vị trí:** `FileUtils.kt:203`, `:227`, `:251`, `FilesFragment.kt:367`, `ToolsFragment.kt:handleImportImages`, `OcrResultActivity.kt:performExportWord/performExportExcel`.

Các helper dùng `openOutputStream()?.use` rồi trả true/URI dù stream null; một số luồng bỏ qua kết quả tạo PDF hoặc lưu Downloads. Người dùng có thể thấy thành công với file rỗng/không tồn tại. Một batch lưu một phần cũng được thông báo như toàn bộ thành công.

**Sửa và kiểm tra:** yêu cầu stream khác null, quản lý file tạm/MediaStore pending, cleanup khi thất bại; truyền kết quả đến UI; báo đúng số file thành công. Test provider trả null, lỗi quyền, đầy bộ nhớ và batch thất bại một phần.

### B09. Mục “Chuyển sang Word” không hoạt động với PDF quét đã lưu

**Vị trí:** `HomeFragment.kt:CONVERT_WORD`, `FilesFragment.kt:319`.

Luồng chỉ nhận `pagePaths.firstOrNull`. Bản quét lưu mới cố ý có pagePaths rỗng nên bấm menu không làm gì. Tài liệu có nhiều ảnh chỉ chuyển trang đầu.

**Sửa và kiểm tra:** dùng pipeline chung render PDF/OCR toàn bộ trang, hỗ trợ tải bản cloud khi cần; luôn có phản hồi tiến trình/lỗi. Test PDF ba trang và PDF chưa tải local.

### B10. OCR Nhật/Hàn chọn model và bộ ký tự không phù hợp

**Vị trí:** `AppLanguageManager.kt:39`, `TextRecognitionHelper.kt:172`, `:215`, `PaddleOcrEngine.kt:44`.

AUTO đưa ja/ko vào model `ch_PP-OCRv4_rec.onnx`; từ điển kèm theo chỉ có 5 mục chứa Kana và 2 mục chứa Hangul, không thể biểu diễn văn bản Nhật/Hàn phổ thông đầy đủ. Fallback là Tesseract eng rồi ML Kit Latin. Chọn ML Kit thủ công cũng luôn dùng Latin, kể cả Hindi/ja/ko. Dependencies recognizer chuyên biệt đã có nhưng không được gọi.

**Sửa và kiểm tra:** route đúng script/ngôn ngữ, dùng recognizer ML Kit tương ứng hoặc model có từ điển đúng; tách ngôn ngữ tài liệu khỏi ngôn ngữ giao diện khi cần. Kiểm tra bộ ảnh mẫu vi/en/zh/ja/ko/hi và fallback. [Tài liệu ML Kit](https://developers.google.com/ml-kit/vision/text-recognition/v2) xác nhận hỗ trợ các script riêng.

### B11. Callback OCR có thể truy cập Fragment đã bị tháo

**Vị trí:** `TextRecognitionHelper.kt:27`, `ToolsFragment.kt:convertToWordFromUri/convertToExcelFromUri`.

OCR chạy trong scope toàn cục. Callback Tools gọi `viewLifecycleOwner`/`requireContext` mà không kiểm tra view còn tồn tại. Chuyển tab trong lúc tác vụ chưa xong có thể gây IllegalStateException. Callback tải Drive tại Home/Files cũng truy cập requireContext sau tác vụ ngoài vòng đời.

**Sửa và kiểm tra:** đưa scope về ViewModel/view lifecycle, hủy hoặc lưu kết quả khi view đóng; không giữ Activity trong tác vụ dài; đóng recognizer khi hoàn tất. Test bắt đầu OCR/download rồi chuyển tab/back ngay.

### B12. Kết quả OCR dài không sống qua việc tạo lại Activity

**Vị trí:** `OcrResultActivity.kt:69`, `:247`.

Văn bản >50.000 ký tự chỉ giữ trong static cache, được xóa ngay lần đọc đầu; intent để rỗng, không có saved state. Activity tái tạo hoặc tiến trình chết sẽ mất kết quả.

**Sửa và kiểm tra:** lưu nội dung vào file/repository theo result ID, khôi phục theo ID; test recreate và process death với nội dung dài.

### B13. Xóa file quản lý có thể loại bỏ bản ghi dù xóa vật lý thất bại

**Vị trí:** `DocumentRepo.kt:461`, `:466`.

Sau `file.delete()` trả false, code vẫn có thể xóa thumbnail/bản ghi và trả thành công qua `repoModified`. Nhánh xóa trang tính `newPages` nhưng không gán lại cho document.

**Sửa và kiểm tra:** chỉ commit metadata theo kết quả thực tế, cập nhật pagePaths/pageCount và xử lý file được chia sẻ. Test xóa thất bại và xóa một trang trong tài liệu nhiều trang.

### B14. Lint đang chặn kiểm tra chất lượng

**Vị trí:** báo cáo lint liên kết ở đầu tài liệu.

Thiếu quyền VIBRATE khiến phản hồi rung không hoạt động (exception bị nuốt); 6 chỗ dùng android:tint thay app:tint; 102 MissingTranslation và 5 StringFormatInvalid. Có thêm cảnh báo chuỗi viết trực tiếp, accessibility, locale alias, CredentialManager và API cũ. Cảnh báo CredentialManagerMisuse là kết quả lint, chưa được coi là bằng chứng đăng nhập luôn thất bại.

**Sửa và kiểm tra:** sửa quyền/tint/format; thống nhất resource key và locale alias; chuyển chuỗi hiển thị quan trọng ra resource. Không tạo baseline để che lỗi. Chạy lint lại và kiểm tra giao diện các ngôn ngữ đại diện.

## C. Hạng mục cần xác minh thêm / hạn chế sản phẩm

- Nhiều thao tác copy file/render thumbnail/ghi toàn bộ JSON vẫn chạy trên main thread: cần đo StrictMode và độ trễ với file lớn; không chỉ dựa vào tên `suspend`.
- `PdfViewerActivity.onDestroy` xóa session chưa lưu mà không phân biệt tái tạo Activity; trạng thái sau lưu chưa cập nhật trong intent/saved state. Cần thử recreate và process death để bảo đảm không mất phiên quét.
- `createNewPdf` giữ ảnh/thumbnail từ cache trong repo; `onDestroy` dọn toàn bộ preview dùng chung. Cần quản lý cache theo viewer/document, tránh tham chiếu đã hết hạn.
- PDF-to-long-image giữ mọi bitmap trước khi ghép; camera/crop decode ảnh gốc không có giới hạn. Cần stress test RAM và giới hạn pixel, đặc biệt trên máy bộ nhớ thấp.
- `moveDocumentToFolder` chưa có nơi gọi trong UI. Thư mục có thể nhận ảnh nhập ở tab Files nhưng chưa có thao tác chuyển tài liệu đã lưu vào thư mục.
- “PPT” hiện xuất HTML, Word xuất HTML mang đuôi .doc; cần thống nhất sản phẩm muốn HTML tương thích hay file Office thật, rồi test trên ứng dụng đích. Không tự ý đổi định dạng trong đợt sửa dữ liệu.
- Nhập `text/*` nhưng mở tài liệu đó bằng MIME `image/*`; cần lưu MIME/loại file thật.
- Xuất Downloads trên Android 26–28 đi qua external storage nhưng không thấy luồng xin quyền ghi runtime. Cần xác minh trên API 26/28 và chọn SAF hoặc xử lý quyền phù hợp.
- Flexible in-app update chưa có `completeUpdate`/listener; cần thử với bản Play test trước khi kết luận trải nghiệm cập nhật hoàn chỉnh.
- Không có unit/instrumentation test của app; không có `.github/workflows`; `app/proguard-rules.pro` được tham chiếu nhưng file chưa tồn tại (release hiện tắt minify). Đây là khoảng trống kiểm chứng/build cần xử lý, không khẳng định debug build hỏng.
- Bộ mẫu ML Kit độc lập và iOS chưa được kiểm chứng đầy đủ; iOS cần môi trường macOS/Xcode để build/test. Giữ hạng mục này riêng nếu các mẫu cũng là sản phẩm cần phát hành.

## Kế hoạch triển khai sau phê duyệt

| Đợt | Phạm vi | Điều kiện hoàn tất |
|---|---|---|
| 1. Bảo toàn dữ liệu | A01–A04, A08–A09, B01, B13; quyền sở hữu file/cache và khôi phục session | Không ghi đè nhầm, không mất trang/bản gốc khi lỗi; test hồi quy qua lưu/crop/xóa/restart |
| 2. Sao lưu và tài khoản | A05–A07, B02–B05 | Không upload sai tài khoản; chỉ báo sync thật; cập nhật đúng revision; phục hồi quyền VIP đúng chủ |
| 3. OCR và xuất file | B06–B12 | Crop không crash với ảnh hẹp; ảnh dài đủ nội dung; OCR đúng script; lưu/xuất báo đúng kết quả; callback an toàn |
| 4. Chất lượng phát hành | B14 và các hạng mục C đã thống nhất | Lint không còn error; có test hồi quy; build debug/release và thử trên thiết bị đại diện |

### Nguyên tắc triển khai

1. Tạo bản sao dữ liệu thử và baseline mã nguồn trước sửa; thư mục hiện tại chưa có Git.
2. Viết test tái hiện cho lỗi dữ liệu/concurrency trước khi thay luồng; không thêm test chỉ để kiểm tra câu lệnh hiển nhiên.
3. Sửa theo từng nhóm nhỏ, bảo đảm đọc được dữ liệu JSON cũ; chuyển đổi không xóa file hay metadata không thể phục hồi.
4. Dùng fixture/mocks cho Drive và lỗi ghi file; chỉ thử tài khoản cloud thật khi đã có môi trường kiểm thử phù hợp.
5. Chạy test có nguồn thật, lint, build debug/release; thử API 26/28, API 35/36, cấu hình bộ nhớ thấp và thiết bị native phù hợp.
6. Báo cáo diff, kiểm tra đã chạy, các hạn chế còn lại. Không phát hành hoặc thay dữ liệu cloud thật chỉ dựa trên phê duyệt sửa code.

**Điểm chờ:** chủ dự án phê duyệt toàn bộ kế hoạch hoặc chọn đợt/ID muốn sửa trước. Báo cáo này không phải phê duyệt triển khai.
