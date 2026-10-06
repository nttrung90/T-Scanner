# Báo cáo kết quả Gói R03: Hủy Request Avatar Cũ Khi Fallback (F03)

- **Ngày thực hiện**: 05/10/2026
- **Trạng thái**: Hoàn tất phần mã nguồn & kiểm thử logic máy chủ (`SOURCE_DONE`, `HOST_VERIFIED`)
- **Phạm vi xử lý**: Bug F03 (P2) trong `MoreFragment`, `AccountDetailDialog`, và tạo helper `AvatarViewBinder`.

---

## 1. Bản chất lỗi F03 đã giải quyết

### Lỗ hổng trước khi sửa:
- Khi người dùng đăng nhập tài khoản có URL ảnh đại diện A, Glide khởi tạo tác vụ tải ảnh bất đồng bộ từ mạng xuống ImageView.
- Nếu người dùng đăng xuất (`user == null`) hoặc chuyển sang tài khoản B không có `photoUrl` (`photoUrl.isNullOrEmpty()`):
  - Mã nguồn cũ gọi trực tiếp `binding.ivAccountIcon.setImageResource(R.drawable.ic_google)` hoặc `setImageResource(R.drawable.ic_account_circle)` mà **không hủy request Glide cũ** đang gắn với ImageView.
  - Theo cơ chế Target của Glide, nếu request A hoàn thành trễ (delayed network response) sau khi người dùng đã logout, Glide vẫn giữ target trên View và gọi `onResourceReady`, ghi đè ảnh A lên icon mặc định!
  - Lệnh `Glide.clear()` trước đó chỉ được đặt ở `onDestroyView()`, trong khi `updateAccountUi()` có thể được gọi nhiều lần trong suốt vòng đời hiển thị của Fragment.
  - Tương tự trong `AccountDetailDialog`, khi `user.photoUrl.isNullOrEmpty()` cũng chỉ gọi `setImageResource` mà không hủy request cũ.

---

## 2. Giải pháp kỹ thuật

1. **Tạo bộ điều phối tải Avatar an toàn: `AvatarViewBinder` (`app/src/main/java/com/tscanner/app/utils/AvatarViewBinder.kt`)**:
   - Định nghĩa interface `AvatarLoaderEngine` với hai thao tác: `clear(imageView)` và `loadCircleAvatar(...)`.
   - Cung cấp `GlideAvatarLoader` mặc định sử dụng `Glide.with(view)`.
   - Trong phương thức nghiệp vụ cốt lõi `bindAvatar`:
     ```kotlin
     if (!photoUrl.isNullOrEmpty()) {
         engine.loadCircleAvatar(
             imageView = imageView,
             url = photoUrl,
             sizePx = sizePx,
             placeholderRes = fallbackRes,
             errorRes = fallbackRes
         )
     } else {
         // Bắt buộc: Hủy request in-flight trước khi đặt drawable mặc định
         engine.clear(imageView)
         imageResourceSetter(imageView, fallbackRes)
     }
     ```
   - Cung cấp `clearAvatar(imageView)` để dọn dẹp triệt để trong `onDestroyView()` hoặc `onStop()`.

2. **Áp dụng vào `MoreFragment.kt`**:
   - Nhánh `user == null` (chưa đăng nhập / đăng xuất): Gọi `AvatarViewBinder.bindAvatar` với `photoUrl = null`, `fallbackRes = R.drawable.ic_google`. Đảm bảo `clear()` chạy trước khi gán icon Google.
   - Nhánh `user != null`: Gọi `AvatarViewBinder.bindAvatar` với `photoUrl = user.photoUrl`, `fallbackRes = R.drawable.ic_account_circle`. Đảm bảo nếu `photoUrl` rỗng thì `clear()` chạy trước khi gán icon avatar mặc định.
   - Trong `onDestroyView`: Gọi `AvatarViewBinder.clearAvatar(binding.ivAccountIcon)`.

3. **Áp dụng vào `AccountDetailDialog.kt`**:
   - Trong `setupViews`: Thay thế nhánh logic tải avatar bằng `AvatarViewBinder.bindAvatar(binding.ivDialogAvatar, user.photoUrl, R.drawable.ic_account_circle, 160)`.
   - Trong `onStop()`: Gọi `AvatarViewBinder.clearAvatar(binding.ivDialogAvatar)`.

---

## 3. Bằng chứng kiểm thử & Xác minh

### Unit & Regression Test:
- Tạo test suite production `AvatarFallbackStaleRequestRegressionTest.kt`:
  1. `testStaleAvatarResponse_cannotOverwriteDefaultIcon_afterLogout`: Bắt đầu tải ảnh A (mạng chậm) -> Người dùng Logout -> Gọi `bindAvatar` -> Kiểm chứng `CLEAR` chạy trước `SET_RES` -> Giả lập phản hồi mạng trễ của A -> Khẳng định phản hồi bị hủy, không ghi đè lên icon Google.
  2. `testStaleAvatarResponse_cannotOverwriteDefaultIcon_whenSwitchingToUserWithoutPhoto`: Tải ảnh A -> Chuyển sang User không có ảnh (`photoUrl = ""`) -> Kiểm chứng `CLEAR` chạy trước và phản hồi trễ của A bị drop hoàn toàn.

### Kết quả chạy lệnh:
- `:app:testDebugUnitTest`: **1073 tests completed, 0 failures, 0 errors, 0 skipped** (tăng thêm 2 test cases).
- `:app:assembleDebug`: **BUILD SUCCESSFUL**.

---

## 4. Phân loại Gate nghiệm thu

| Gate | Trạng thái | Ghi chú |
|---|---|---|
| Mã nguồn & Logic | **SOURCE_DONE** | Đã hoàn thiện `AvatarViewBinder`, `MoreFragment`, `AccountDetailDialog`. |
| Kiểm thử máy chủ (Host) | **HOST_VERIFIED** | 1073 unit tests pass, compilation sạch. |
| Kiểm thử thiết bị thực | **DEVICE_PENDING** | Cần kiểm tra kịch bản đăng nhập tài khoản có avatar, chuyển mạng chậm (network throttle), đăng xuất nhanh trên thiết bị thật. |
| Play Console Warning | **PLAY_PENDING** | Thuộc nhóm cảnh báo Bitmap mạng / Memory footprint. |
