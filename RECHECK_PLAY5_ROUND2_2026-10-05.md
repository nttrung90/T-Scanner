# Kiểm tra độc lập PLAY5 — vòng 2, 05/10/2026

## Kết luận

Chưa đủ điều kiện kết luận hết lỗi/đưa production. Có **5 vấn đề trong logic hiện tại**, cùng khoảng trống kiểm thử và sai lệch báo cáo nghiệm thu. Đây là kết luận từ đọc đường production, layout và artifact; chưa tái hiện bằng thao tác thiết bị vì ADB không có thiết bị. Không sửa production trong lượt kiểm tra này.

Các phần đã xác nhận được: 6 khóa portrait của app đã gỡ; 8 Activity vẫn gọi enableEdgeToEdge; helper lấy systemBars + displayCutout, dùng max cho IME; theme đã bỏ màu system bar; release khai báo minifyEnabled và shrinkResources true; avatar dùng Glide và có cleanup khi destroy view/onStop. Những cải tiến này không chứng minh mọi màn hình đã đạt runtime acceptance.

## F01 — P1: Crop save không có ownership qua recreation

Bằng chứng: `app/src/main/java/com/tscanner/app/ui/editor/CropRotateActivity.kt:188-234` lấy bitmap hiện tại, chạy crop/compress/SafeFileWriter trên Dispatchers.IO trong lifecycleScope; `:257-260` recycle bitmap khi Activity destroy. `:40-46` lưu góc/crop; `:133-145` đọc lại chính imagePath và áp lại góc. Save ghi đè imagePath. Chỉ nút Save bị disable; nút rotate/cancel vẫn hoạt động.

Hai lịch thực thi nguy hiểm:

1. IO đang dùng bitmap -> xoay thiết bị/thoát màn -> onDestroy recycle bitmap. Cancellation coroutine không bảo đảm đoạn blocking Bitmap/compress/write đã dừng; thao tác có thể lỗi vì tài nguyên bị giải phóng. Bấm rotate khi save đang chạy cũng recycle bitmap cũ.
2. Crop/rotate đã commit xuống file -> Activity cũ bị hủy trước khi trả RESULT_OK -> Activity mới đọc file đã biến đổi, áp lại currentRotationAngle và khôi phục crop cũ trên ảnh đã crop. Có thể xoay/cắt lần hai và mất tín hiệu hoàn tất đối với caller. Atomic write chỉ bảo vệ file commit, không giải quyết ownership/UI result.

Cần giữ snapshot bất biến có owner sống qua rotation, quản lý trạng thái commit/result, không recycle khi IO còn dùng, hoặc ghi staging và chỉ commit một lần theo transaction. Không giải quyết bằng khóa orientation trở lại. Chưa có thiết bị để xác nhận tần suất thực tế.

## F02 — P2: Crop mất vùng cắt khi recreate lần nữa trước khi decode xong

Bằng chứng: `CropRotateActivity.kt:60-63` khôi phục pendingNormalizedCropRect; chỉ sử dụng/xóa nó trong `:166-173`. Nhưng onSaveInstanceState `:43` luôn lấy cropOverlayView.getNormalizedCropRect, không ưu tiên pending state. `CropOverlayView.kt:108-110` trả full rect khi imageBounds chưa được đặt.

Chuỗi lỗi: chọn crop riêng -> rotate -> Activity mới còn decode -> rotate lần nữa -> save full rect từ overlay chưa khởi tạo -> lần restore sau mất crop ban đầu. Góc có lưu nhưng vùng cắt bị thay. Đây là đường logic xác định; cần test Activity với decode bị giữ bằng latch, không dùng sleep ngẫu nhiên.

## F03 — P2: Request avatar cũ vẫn có thể ghi đè icon sau logout

Bằng chứng: `ui/more/MoreFragment.kt:275-277` và `:295-296` gọi setImageResource cho logged-out/URL rỗng, không clear request Glide cũ. Clear chỉ ở onDestroyView `:627-629`; updateAccountUi có thể chạy khi Fragment view còn sống.

Tái hiện cần kiểm tra: tải ảnh A bằng response chậm -> logout hoặc B không có photoUrl -> icon mặc định -> response A hoàn tất -> Glide cũ vẫn sở hữu ImageView và ghi ảnh A. Nhánh B có URL mới dùng into cùng view có cơ chế thay request; nhánh setImageResource không có cơ chế đó. Sửa bằng clear trước fallback trong cả hai nhánh, kiểm thử completion muộn.

## F04 — P2: Insets ngang OCR chưa bảo vệ nội dung tương tác

Bằng chứng: `ui/ocr/OcrResultActivity.kt:98-130` chỉ xử lý toolbar, bottom actions và tabLayoutOcr. `res/layout/activity_ocr_result.xml:91,129,202-298` chứa search bar, language banner và FrameLayout nội dung ba tab là các sibling; padding của tab không áp xuống chúng. Fragment text/table không có listener insets bổ sung qua tìm kiếm source.

Khi cutout/nav bar ở cạnh: nội dung, toolbar Undo/Redo trong editor, vùng nhập bảng/tìm kiếm vẫn có thể đi vào vùng bị che, dù toolbar Activity nhìn đúng. Cần đặt một owner insets cho các vùng này, giữ nền tràn viền và tránh áp lặp ở cha/con. Cần screenshot/layout bounds landscape với cutout trái và phải; chưa chạy UI.

## F05 — P2: Migration helper cộng thêm khoảng đệm đã có trong XML

Bằng chứng: helper `utils/EdgeToEdgeInsetsHelper.kt` tính `initial.bottom + bottomInset + extraBottom`; CropRotateActivity `:70,81-86` thêm 12dp trong khi `res/layout/activity_crop_rotate.xml:95` đã có paddingVertical 12dp. OcrResultActivity `:101,113-119` thêm 12dp trong khi layout_ocr_bottom_actions XML `:306` đã padding 12dp. Camera `:287-306` cộng XML top 12dp + extra 8dp và bottom 24dp + extra 16dp.

Kết quả số học: Crop/OCR bottom thành 24dp + inset; Camera top 20dp + inset, bottom 40dp + inset. Đây không phải cộng dồn qua nhiều dispatch, mà là thay đổi spacing do giữ đồng thời padding gốc và hằng số cũ. So với implementation trước migration, workspace bị thu hẹp thêm; đặc biệt đáng chú ý với landscape/IME. Cần chọn một nguồn spacing, xác minh thiết kế và so bounds, không chỉ test max(nav,IME). Nếu tăng spacing là chủ ý được duyệt thì ghi nhận trade-off; hiện báo cáo không nêu thay đổi đó.

## V01 — Test mới chưa đủ chứng minh các luồng production

- `NetworkImageLoadingPolicyTest.kt`: shouldLoadWithGlide và calculateMemoryFootprintBytes đều là hàm private trong test; không gọi MoreFragment/Glide. Phép so ảnh 4032x3024 với 128x128 không chứng minh RAM thực tế giảm 99,8%; trước sửa đã dùng Glide theo kích thước view.
- `CropRotateStateAndMathTest.kt`: tự viết normalizeRotation và phép round-trip float, không gọi Activity/overlay/lifecycle/IO. Không phát hiện F01/F02.
- `EdgeToEdgeInsetsHelperTest.kt`: 3 test max gọi production; 2 test dispatch chỉ tự cộng số trong loop, không gọi applyTopBarInsets/applyBottomBarInsets hay inflate XML. Không phát hiện F04/F05.
- Cần bổ sung regression trên production seam hoặc instrumentation; không đếm test số học này thành bằng chứng lifecycle/UI đạt.

## V02 — Báo cáo artifact, mapping và mức hoàn thành bị khẳng định quá mức

Đọc/hash trực tiếp trong lượt kiểm tra:

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| baseline_artifacts_20261005/app-release.aab | 17,420,226 | 5B8FC710A1E8FB4DF22EDC22502DDF7DFC2E17D2901A0F8216B51F0846F6CF3F |
| app/build/outputs/bundle/release/app-release.aab | 16,576,487 | 9A6CF12172EBC9863275A72F6266C62BF435FD479BF186BA9DE43554C6519C51 |

Chênh lệch hai file là 843,739 byte (~4,84%), **không phải 55,4%**. Hai build khác nguồn/version nên ngay cả 4,84% cũng không được quy toàn bộ cho resource shrinking. REPORT_PLAY5_G05.md:54 lấy baseline 37,222,084 không khớp baseline được giữ; :57 dùng tiền tố hash `313EE3` của mapping làm hash AAB. REPORT_PLAY5_INTEGRATION_SUMMARY.md lặp lại số liệu sai.

Mapping baseline khớp nhiều tên nhưng R8 đã merge/inlining: trong block c5 (header là AppAuthManager coroutine), dòng 457619-457632 còn có TesseractOcrHelper.calculateInSampleSize/access$calculateInSampleSize -> invokeSuspend. Vì vậy kết luận c5.invokeSuspend chỉ là Auth flow dựa vào tên header là không đầy đủ. Cần phân tích đúng nhánh DEX/line range trước kết luận cảnh báo bitmap là false positive. Local AAB phù hợp ký hiệu là bằng chứng tốt, chưa thay thế xác nhận version/track cảnh báo trên Play.

Merged manifest release vẫn còn 2 portrait ML Kit. Giữ nguyên khi chưa có device evidence là lựa chọn thận trọng, không tự coi là defect cần override. Nhưng phải ghi dependency gate còn mở, không gọi cả cảnh báo đã hoàn tất. Cũng không được suy từ build thành công rằng layout không vỡ, bộ nhớ đã giảm hay đủ điều kiện production.

## Kiểm chứng host và giới hạn

- Đã chạy lại `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline --console=plain` với JDK 21.0.1/Gradle cache hiện có; log `audit_play5_recheck_20261005.log`. **BUILD SUCCESSFUL in 3m 14s**, 55 tasks (11 executed, 44 up-to-date). XML test results: **1062 tests, 0 failures, 0 errors, 0 skipped**. Lint debug: **0 errors, 740 warnings**. Các số này không chứng minh F01-F05 đã được test hay không tồn tại.
- ADB chạy được sau khi cấp quyền truy cập môi trường, danh sách thiết bị trống. Không có device/instrumentation/rotation/IME/memory/Play Console result trong lượt này.
- Chỉ kiểm tra AAB release có sẵn, chưa rebuild release hay cài release. Không sửa config, artifact tham chiếu, credential hoặc production source.
- Tài liệu chính thức dùng đối chiếu: [Glide targets](https://bumptech.github.io/glide/doc/targets.html), [Android edge-to-edge](https://developer.android.com/develop/ui/views/layout/edge-to-edge). Các kết luận cụ thể F01-F05 dựa trên checkout.

Kế hoạch tiếp theo: `PLAN_PLAY5_ROUND2_GEMINI_2026-10-05.md`.
