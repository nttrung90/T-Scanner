# Rà soát sau triển khai Files / Free–VIP

Ngày: 24/09/2026. Đối chiếu `PLAN_FILES_VIP_TWO_AGENTS_2026-09-24.md` và hai báo cáo Agent A/B với mã hiện tại.

## Kết luận

Chưa phát hiện lỗi chức năng nghiêm trọng trong phạm vi thay đổi. Bốn yêu cầu chính đã được triển khai đúng qua kiểm tra mã và tài nguyên. Có một thiếu sót nhỏ về vùng chạm, cùng phần nghiệm thu trên thiết bị còn thiếu. Không sửa mã nguồn trong lượt rà soát này.

## Điểm cần chỉnh

### F01 — P3: Vùng chạm FREE/VIP mới chỉ cao 40dp

- Bằng chứng: `app/src/main/res/layout/fragment_home.xml:61` đặt `layout_height="40dp"` cho FrameLayout `btn_vip_home`. `minWidth="48dp"` chỉ mở rộng chiều ngang; không có TouchDelegate mở rộng chiều dọc trong mã hiện tại.
- Ảnh hưởng: vùng bấm dọc nhỏ hơn mục tiêu 48dp của bước B1, khó thao tác hơn với người cần hỗ trợ tiếp cận. Không phải lỗi phân loại tài khoản hoặc crash.
- Đề xuất: tăng container lên tối thiểu 48dp chiều cao, giữ icon/nhãn căn giữa. Header cha đang wrap_content nên có thể chứa container lớn hơn. Chỉ cần sửa fragment_home.xml.
- Nghiệm thu: vùng chạm ít nhất 48×48dp ở cả Free/VIP; thanh tìm kiếm vẫn căn giữa và TalkBack chỉ đọc một mô tả cho nút.
- Đây là thiếu sót xác nhận qua cấu hình XML, chưa đo vùng chạm trên thiết bị.

## Đối chiếu yêu cầu

| Hạng mục | Kết quả đọc mã/tài nguyên |
|---|---|
| Bỏ Quản lý tài liệu khỏi Mở rộng | Đã bỏ block, listener, import; còn đúng một separator giữa tài khoản và ngôn ngữ |
| Đặt sau Tạo thư mục trong Tập tin | Đúng thứ tự 4 card; ID không trùng; listener gọi DocumentManagementActivity.start |
| Giữ màn hình quản lý cũ | Activity đã khai báo trong manifest; điểm mở mới dùng API cũ |
| FREE cho khách/Free/hết hạn | Render dựa trên isVipActive; hai View loại trừ nhau; XML mặc định FREE |
| Icon cho VIP đang hoạt động | Đúng; không loại bỏ quyền của PRO/PRO MAX hiện hữu |
| Cập nhật khi tài khoản đổi | Observer currentUser gọi renderVipStatus cùng refreshRecentDocs |
| Cập nhật khi resume/hết hạn | Có gọi onResume; job hẹn sau hạn 100ms, hủy khi render lại hoặc onDestroyView; gắn viewLifecycleOwner |
| Bấm FREE/VIP | Giữ listener mở VipUpgradeDialog |
| Ẩn PRO/PRO MAX và thông tin | Tiêu đề roadmap và cả hai container đặt GONE; giữ ID nên binding không bị mất |
| Mô tả xóa dấu | vip_perk_4 cập nhật đúng, xuất hiện một lần trong layout; đủ 8 locale, mỗi key có đúng một khai báo |

Không có baseline diff được lưu trong báo cáo hai agent để xác minh độc lập lời khẳng định “chỉ sửa file được cấp”. Repository có nhiều thay đổi có sẵn từ trước; không quy toàn bộ diff hiện tại cho lần triển khai này. Luồng kích hoạt/Drive của dialog và policy watermark không thấy thay đổi liên quan so với mã đã đọc khi lập kế hoạch.

## Kiểm tra đã thực hiện

1. Parse XML của fragment_files, fragment_more, fragment_home và dialog_vip_upgrade: hợp lệ, không có ID trùng trong từng layout.
2. Kiểm tra 4 key vip_perk_4/vip_badge_free/vip_home_desc_free/vip_home_desc_active trong 8 bộ strings hiện hữu: đủ, không trùng.
3. Gradle offline với `GRADLE_USER_HOME=C:\Users\nguye\.gradle`:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline --console=plain
.\gradlew.bat :app:testDebugUnitTest --rerun --offline --console=plain
```

- Lệnh tổng hợp: BUILD SUCCESSFUL; Gradle xác nhận hầu hết task UP-TO-DATE.
- Lệnh thứ hai thực thi lại riêng unit test: **478 tests / 66 suites, 0 failures, 0 errors, 0 skipped**.
- Lint hiện tại: **0 errors, 745 warnings** toàn dự án. Không coi toàn bộ warning là phát sinh bởi thay đổi này.
- Log: `audit_files_vip_build.log`, `audit_files_vip_tests.log`.
- Ban đầu wrapper bị chặn quyền ghi lock ở cache; đã chạy thành công bằng cache người dùng với quyền thực thi phù hợp. Đây là vấn đề môi trường, không phải lỗi app.

## Giới hạn và phần nghiệm thu còn thiếu

ADB chạy được nhưng danh sách thiết bị rỗng. Chưa xác nhận bằng runtime/screenshot:

- Bốn card ở chiều rộng 320/360dp, font 1.3–1.5, đặc biệt nhãn tiếng Đức/Bồ Đào Nha; wrap_content tránh chiều cao cố định nhưng chưa chứng minh bố cục đẹp/đồng đều. Đây là rủi ro cần kiểm tra, chưa kết luận bị cắt chữ.
- Mở quản lý từ Tập tin rồi Back; tạo thư mục, nhập ảnh/file và bảo toàn trạng thái thư mục.
- Nâng cấp ngay tại Home, đổi tài khoản, đăng xuất, hết hạn khi Home đang mở hoặc app ở nền, xoay màn hình/tái tạo view.
- TalkBack, kích thước hiển thị chữ FREE, cuộn hộp thoại đến nút đóng, export Free/VIP thực tế.

Các unit test hiện có không thay thế những bước này: VipManagerTest kiểm tra công thức tính hạn trong helper của test; MainActivityNavigationTest chủ yếu kiểm tra ID/tag điều hướng; chúng không đo layout hoặc trực tiếp chạy vòng đời HomeFragment/renderVipStatus. VipWatermarkPolicyTest bảo vệ policy bỏ dấu, không chứng minh đầu ra trên thiết bị.

Đề nghị xử lý F01 trước, sau đó thực hiện ma trận thiết bị trong kế hoạch gốc. Chưa có căn cứ yêu cầu sửa lại phần di chuyển mục, ẩn gói hoặc bản dịch.
