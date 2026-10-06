# Rà soát đăng nhập, VIP và Drive — 25/09/2026

## Kết luận và giới hạn

Đã đọc luồng tài khoản ở More, các điểm mở VIP ở Home/Viewer/ID card/Create PDF, AppAuthManager, entitlement, policy watermark, CloudBackupManager, GoogleDriveBackupWorker, GoogleDriveService và phần sở hữu tài liệu. Không sửa mã nguồn. Checkout có nhiều thay đổi từ trước; các phát hiện dưới đây áp dụng cho mã hiện tại, không quy về một lần sửa cụ thể.

Có lỗi chắc chắn trong điều phối đăng nhập và đường dự phòng. Chưa xác nhận nguyên nhân Google từ chối đăng nhập trên thiết bị người dùng: không có thiết bị trong `adb devices`, chưa có status code/logcat và chưa kiểm tra được Google Cloud/Play Console. Không kết luận SHA-1 sai chỉ từ triệu chứng.

## Phát hiện theo ưu tiên

### L01 — P1: Bỏ qua kết quả đăng nhập lỗi

`MoreFragment.kt:44–58`: callback chỉ gọi `handleGoogleSignInResult` khi `RESULT_OK`. Kết quả không thành công có dữ liệu lỗi bị bỏ qua hoàn toàn, không hiện lỗi, không kích hoạt dự phòng. Cần đọc kết quả trả về và phân biệt người dùng hủy với lỗi xác thực/cấu hình. `AppAuthManager` đã có xử lý ApiException nhưng nhánh này không được gọi trong trường hợp bị lọc.

### L02 — P1: Credential Manager tạo request không hợp lệ

`AppAuthManager.kt:291–308`: cùng một request chứa `GetSignInWithGoogleOption` và `GetGoogleIdOption`. Tài liệu Google quy định explicit-button flow chỉ chứa một GetSignInWithGoogleOption, không kèm option khác. Lỗi bị catch rồi quay lại classic sign-in. Đây là lỗi nhánh dự phòng; đường chính hiện ở `MoreFragment.kt:245` gọi classic trước, vì vậy không được coi L02 là nguyên nhân duy nhất của mọi lần đăng nhập thất bại.

Nguồn: https://codelabs.developers.google.com/sign-in-with-google-android

### L03 — P2: Đăng nhập đang gắn với xin quyền Drive

`AppAuthManager.kt:210–217`: yêu cầu ID token và scope drive.file ngay lúc đăng nhập, dù đã có API cấp quyền Drive riêng. Lỗi consent/cấu hình quyền Drive có thể làm ảnh hưởng bước xác thực danh tính. Cần tách đăng nhập khỏi cấp quyền Drive; từ chối Drive vẫn phải dùng tài khoản và các tính năng không cần Drive được.

Google khuyến nghị xin scope bổ sung tại thao tác cần API: https://developer.android.com/identity/legacy/gsi/additional-scopes

### L04 — P2: Nút yêu cầu đăng nhập trong VIP không mở đăng nhập

`VipUpgradeDialog.kt:32–35,70–78`: khi chưa có tài khoản, nút mang nghĩa “đăng nhập để kích hoạt” nhưng chỉ Toast rồi dismiss. Home mở dialog này trực tiếp (`HomeFragment.kt:274`). Người dùng đi vào VIP từ Home không được dẫn tới màn chọn tài khoản. Cần callback yêu cầu đăng nhập và tiếp tục luồng sau thành công.

### L05 — P2: Cấp quyền Drive từ Home thiếu callback kết quả

`HomeFragment.kt:274` và `MoreFragment.kt:127` mở VipUpgradeDialog không truyền onRequestDrivePermission. Dialog dùng `context.startActivity(intent)` (`VipUpgradeDialog.kt:58–59`), nên không qua launcher để xác nhận tài khoản/quyền và chạy đồng bộ sau cấp quyền. SDK có thể vẫn lưu quyền, nhưng ứng dụng không thực hiện bước hậu cấp quyền trong đường này. Cần dùng cùng cơ chế nhận kết quả ở mọi điểm mở.

### L06 — P1: Tài khoản demo chiếm sở hữu tài liệu khách

`MoreFragment.kt:273–286` đề nghị demo khi lỗi đăng nhập; `AppAuthManager.kt:444–456` gọi claimGuestDocuments bằng ID demo. `DocumentRepo.kt:559` chỉ nhận tài liệu ownerId null. Sau đó đăng nhập Google thật không chuyển tài liệu đã thuộc demo sang tài khoản thật; chúng bị loại khỏi tập tài liệu đồng bộ của tài khoản thật và có thể khỏi danh sách lọc tài khoản. Đây là đường lỗi suy ra trực tiếp từ mã, chưa thao tác tái hiện trên thiết bị. Không nên dùng demo như cách khắc phục xác thực trong bản phát hành; không tự động chuyển sở hữu dữ liệu sang demo.

### L07 — Giới hạn sản phẩm: VIP hiện là quyền thử nghiệm lưu tại máy

`VipUpgradeDialog.kt:41–43` cấp 365 ngày trực tiếp; `AppAuthManager.kt:414–430,loadVipForUser,saveVipForUser` đọc/ghi entitlement trong SharedPreferences. Không tìm thấy BillingClient/billingclient/purchaseToken trong app/src/main và app/build.gradle. Có thể bấm gia hạn nhiều lần, không có chứng từ mua hay khôi phục quyền từ server. Đăng nhập cùng Google trên máy mới không tự mang VIP sang; sao lưu Drive không đồng nghĩa sao lưu entitlement. Nếu đây là chủ đích thử nghiệm thì không coi là lỗi thanh toán, nhưng chưa phải luồng bán/khôi phục VIP hoàn chỉnh.

### L08 — P2: Lỗi đồng bộ sau đăng nhập không đến giao diện

`AppAuthManager.kt:175–183` gọi syncCatalogFromDrive với callback rỗng. Wrapper trong CloudBackupManager chuyển Failure/AuthRequired thành số lượng 0; kết quả này cũng bị bỏ. Đăng nhập thành công không chứng minh Drive đã đồng bộ. Cần hiển thị riêng trạng thái tài khoản, VIP, quyền Drive và kết quả sync; dùng typed result hiện có.

### L09 — P1: Snapshot sao lưu vẫn có đường mất tính bất biến

`CloudBackupManager.kt:81–103` xóa mọi snapshot quá 24 giờ khi tạo snapshot mới, không kiểm tra còn work chờ/retry không. Worker chọn snapshotPath và fail khi file mất. Mất mạng dài rồi tạo tài liệu mới có thể làm mất snapshot của work còn chờ. Khi copy snapshot thất bại, enqueue vẫn dùng file sống; worker có kiểm tra revision trước upload nhưng không bảo vệ byte của file sống khỏi thay đổi sau đó. Đây là rủi ro dữ liệu từ mã, chưa fault-inject trong lượt này. Cần quản lý snapshot theo vòng đời work và dừng enqueue khi không tạo được snapshot.

## Các bảo vệ hiện có

- Canonical ID ưu tiên sub; quyền Drive đối chiếu email với tài khoản hiện tại.
- Entitlement lưu tách theo user ID; có kiểm tra hết hạn.
- Worker kiểm tra owner/revision và CAS khi cập nhật trạng thái; đã giữ snapshot khi Result.retry thay vì xóa vô điều kiện.
- Transport phân biệt transient/auth/quota/404; không tái tạo file cho mọi lỗi update.
- Policy watermark kiểm tra VIP đang hoạt động tại các callsite xuất đã rà soát. Chưa xác minh nội dung file xuất thật trên thiết bị.

## Kiểm thử thực hiện

Chạy offline `:app:testDebugUnitTest` với tên đầy đủ của 8 lớp: AppAuthCanonicalIdentityTest (7), AppAuthDriveAuthorizationTest (4), CloudSessionGenerationGuardTest (3), DocumentRepoAccountIsolationTest (8), GoogleDriveDownloadValidationTest (4), GoogleDriveTransportErrorTest (5), VipManagerTest (3), VipWatermarkPolicyTest (4).

Kết quả mới: BUILD SUCCESSFUL, 38 tests, 0 failures, 0 errors. XML: app/build/test-results/testDebugUnitTest. Không chạy lại toàn bộ lint/release build vì không sửa production.

Lần chạy đầu bị sandbox chặn cache lock; chạy có quyền bằng cache C:\Users\nguye\.gradle đã qua. Lần dùng wildcard bị Windows mở rộng thành tên file; lần cuối dùng tên lớp đầy đủ và thành công.

Các test không chạy Google UI/OAuth thật. VipManagerTest dùng helper tính hạn của test; AppAuthDriveAuthorizationTest không kiểm tra callback Activity.RESULT_CANCELED và không tạo request Credential Manager. Do đó 38 test xanh không phủ L01/L02/L04/L05. Không tạo test sao chép mã chỉ để tuyên bố đã tái hiện.

## Thứ tự khắc phục đề xuất — chưa triển khai

1. Sửa xử lý kết quả và request đăng nhập, tách Drive, nối CTA VIP tới đăng nhập. Thêm regression vào điều phối production cho success/cancel/error và request builder.
2. Dùng chung launcher cấp quyền Drive, trả lỗi typed về UI; ngăn demo nhận tài liệu thật.
3. Đối chiếu OAuth bằng package com.tscanner.app và chứng thư của đúng bản cài: debug, APK ký release hoặc Play App Signing. Kiểm tra Web client cùng project và cấu hình consent/test users nếu áp dụng. Chỉ làm sau khi có mã lỗi; chưa sửa client ID theo phỏng đoán.
4. Chốt VIP là trial hay thương mại, rồi mới thiết kế billing/restore; sửa vòng đời snapshot độc lập.
5. Nghiệm thu thiết bị: login thành công/hủy/lỗi; từ chối Drive; cấp quyền lại từ Home/More; khách có tài liệu; đổi tài khoản; đăng xuất; VIP hết hạn; khôi phục máy mới; mất mạng và retry; PDF xuất Free/VIP.
