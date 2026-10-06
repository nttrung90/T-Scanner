# Kiểm tra lại sau sửa R01–R04

Phạm vi: các bản sửa sau REVIEW_8_LANGUAGES_2026-09-18.md và luồng 8 ngôn ngữ liên quan. Không sửa code sản phẩm. Phát hiện runtime được suy ra từ luồng code, chưa tái hiện trên thiết bị.

## Những phần đã sửa

- R01: `IdCardComposeActivity` lưu/khôi phục `IdCardSessionDraft` chứa đường dẫn ảnh và config; nhánh restore không còn đọc đè từ Intent. Chưa chứng minh toàn bộ lifecycle thực tế: test hiện dùng `toMap/fromValues`, chưa kiểm tra Bundle và Activity recreate.
- R02: lỗi OCR thông thường đã đi qua resource, không đưa raw exception ra UI trong mapper; lỗi khởi tạo Tesseract/Paddle được tách khỏi trạng thái tải model. Còn trường hợp ML Kit bên dưới.
- R03: các callsite hiện tại truyền kết quả có cấu trúc; màn hình kết quả format lại theo locale hiện hành. Còn thiếu metadata fallback cho kết quả nhiều trang.
- R04: hộp Giới thiệu lấy versionName/versionCode từ PackageManager và format bằng resource.

## C01 — P2: ML Kit thiếu dung lượng hoặc lỗi mạng vẫn bị báo đang tải model

**Bằng chứng:** `app/src/main/java/com/tscanner/app/utils/TextRecognitionHelper.kt:109–114` coi NETWORK_ISSUE và NOT_ENOUGH_SPACE là model unavailable. Nhánh callback dòng 563 và catch dòng 574 gán cứng `OcrModelUnavailableType.DOWNLOADING`. Mapper dòng 82 vì thế hiển thị “model đang tải, thử lại sau”.

**Tác động:** người dùng thiếu dung lượng chỉ được yêu cầu chờ/thử lại, không biết cần giải phóng dung lượng; lỗi mạng cũng bị trình bày như một tiến trình tải đang hoạt động. Không kết luận rằng mọi lỗi unavailable đều có tác vụ tải thật.

**Tái hiện kiểm thử:** inject MlKitException với NOT_ENOUGH_SPACE và NETWORK_ISSUE vào cả callback failure và nhánh lỗi khởi tạo. Chọn engine ML Kit hoặc làm fallback thất bại để lỗi đến UI.

**Sửa:** phân loại theo errorCode trước, có trạng thái riêng cho thiếu dung lượng/kết nối hoặc dùng thông báo model chưa sẵn sàng trung tính; chỉ dùng DOWNLOADING khi có bằng chứng phù hợp. Đồng bộ hai đường xử lý lỗi và dịch thông báo trong tám bộ resource.

**Nghiệm thu:** kiểm thử chuỗi phân loại engine → OcrResult → thông báo cho từng mã lỗi; không chỉ assert `isModelUnavailableException == true` như test hiện có. Lỗi hết dung lượng không trả thông báo đang tải.

## C02 — P2: Kết quả OCR nhiều trang mất dấu hiệu đã dùng engine dự phòng

**Bằng chứng:** `app/src/main/java/com/tscanner/app/utils/OcrModels.kt:383` (`MultiPageOcrResult.Success`) chỉ giữ engineIds/documentLanguage, không giữ fallbackUsed. Aggregator bỏ cờ này khi gom các trang. `app/src/main/java/com/tscanner/app/ui/ocr/OcrResultActivity.kt:352–358` gọi start overload mà không truyền fallbackUsed, nên nhận mặc định false ở dòng 313. Khi có engineIds, UI ưu tiên format lại metadata từ IDs và bỏ qua enginesUsed cũ chứa nhãn dự phòng.

**Tác động/tái hiện:** một trang trả Success(engineId="mlkit_latin", documentLanguage="vi", fallbackUsed=true) sau khi engine chính thất bại → gom trang từ Files/Home/PDF Viewer → màn hình chỉ còn tên engine và ngôn ngữ, mất nhãn dự phòng. Kể cả danh sách chỉ một trang nhưng đi qua aggregator cũng gặp. Không khẳng định mất văn bản OCR.

**Sửa:** giữ metadata fallback theo engine/trang hoặc một mô hình tổng hợp có ngữ nghĩa rõ ràng; truyền dữ liệu sang Activity và dịch lại khi render. Không gắn cờ toàn bộ engine nếu thực tế chỉ một engine là dự phòng.

**Nghiệm thu:** test một trang fallback, nhiều trang pha trộn primary/fallback, cùng engine xuất hiện ở hai vai trò; assert metadata sau aggregate và payload chuyển màn hình. Đổi locale giữ đúng trạng thái dự phòng và dịch đúng nhãn. Test hiện tại chỉ kiểm tra pagesWithText/enginesUsed nên chưa bắt mất cờ.

## Thứ tự xử lý

C01 trước, C02 sau; mỗi gói một lượt cho model nhỏ. Chưa phát hiện P1 mới trong phạm vi các bản sửa được kiểm tra. Không coi nhận định này là bảo đảm toàn ứng dụng hết lỗi.

## Giới hạn

Kiểm chứng trong lượt này: validator PASS (628 strings, 7 plurals, bảy bộ dịch + nguồn Anh); 12/12 unittest Python PASS. Gradle `:app:testDebugUnitTest --rerun :app:assembleDebug :app:lintDebug --offline --console=plain` BUILD SUCCESSFUL; 137 JVM tests, 0 failures/errors. Lint 0 errors, 681 warnings, trong đó vẫn có 21 MissingQuantity của es/fr/pt cần rà ở đợt ngữ pháp. Build assemble sử dụng các đầu ra up-to-date; unit test được yêu cầu chạy lại. Không build/ghi đè release AAB.

`adb devices` không có thiết bị kết nối. Cần kiểm thử thực tế đổi locale/recreate, process death, nâng cấp và cài từ AAB offline. Không đánh dấu hỗ trợ tám ngôn ngữ đã nghiệm thu hoàn toàn chỉ từ kiểm thử JVM/XML.
