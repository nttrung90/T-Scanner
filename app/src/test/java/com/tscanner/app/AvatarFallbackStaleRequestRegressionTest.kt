package com.tscanner.app

import android.widget.ImageView
import com.tscanner.app.utils.AvatarViewBinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AvatarFallbackStaleRequestRegressionTest {

    private class MockImageView : ImageView(null)

    private class FakeGlideEngine : AvatarViewBinder.AvatarLoaderEngine {
        val callOrder = mutableListOf<String>()
        var activeUrl: String? = null
        var isRequestInFlight = AtomicBoolean(false)
        var isRequestCancelled = AtomicBoolean(false)

        override fun clear(imageView: ImageView) {
            callOrder.add("CLEAR")
            isRequestCancelled.set(true)
            isRequestInFlight.set(false)
            activeUrl = null
        }

        override fun loadCircleAvatar(
            imageView: ImageView,
            url: String,
            sizePx: Int,
            placeholderRes: Int,
            errorRes: Int
        ) {
            callOrder.add("LOAD:$url")
            activeUrl = url
            isRequestCancelled.set(false)
            isRequestInFlight.set(true)
        }

        /**
         * Giả lập phản hồi mạng trễ trả về kết quả
         */
        fun simulateDelayedNetworkResponse(onResourceReady: () -> Unit): Boolean {
            if (!isRequestCancelled.get() && isRequestInFlight.get()) {
                onResourceReady()
                isRequestInFlight.set(false)
                return true
            }
            return false // Bị drop do đã clear
        }
    }

    @Test
    fun testStaleAvatarResponse_cannotOverwriteDefaultIcon_afterLogout() {
        val engine = FakeGlideEngine()
        val mockView = MockImageView()
        val currentDisplayedRes = AtomicInteger(0)
        val imageResourceSetter: (ImageView, Int) -> Unit = { _, resId ->
            engine.callOrder.add("SET_RES:$resId")
            currentDisplayedRes.set(resId)
        }

        val urlA = "https://example.com/avatar_user_a.jpg"
        val googleIconRes = 1001 // R.drawable.ic_google stub
        val defaultAccountRes = 1002 // R.drawable.ic_account_circle stub

        // 1. Bắt đầu tải ảnh đại diện User A (phản hồi mạng chậm)
        AvatarViewBinder.bindAvatar(
            imageView = mockView,
            photoUrl = urlA,
            fallbackRes = defaultAccountRes,
            engine = engine,
            imageResourceSetter = imageResourceSetter
        )
        assertTrue("Request cho URL A phải đang in-flight", engine.isRequestInFlight.get())
        assertFalse("Request URL A chưa bị hủy", engine.isRequestCancelled.get())

        // 2. Người dùng Đăng xuất (Logout) trước khi ảnh A tải xong
        AvatarViewBinder.bindAvatar(
            imageView = mockView,
            photoUrl = null,
            fallbackRes = googleIconRes,
            engine = engine,
            imageResourceSetter = imageResourceSetter
        )

        // Kiểm tra thứ tự: CLEAR bắt buộc phải chạy TRƯỚC SET_RES
        assertEquals(
            listOf("LOAD:$urlA", "CLEAR", "SET_RES:$googleIconRes"),
            engine.callOrder
        )
        assertEquals("Phải hiển thị icon Google đăng nhập", googleIconRes, currentDisplayedRes.get())
        assertTrue("Request A phải bị hủy sau khi clear", engine.isRequestCancelled.get())

        // 3. Giả lập phản hồi trễ của request A trả về sau khi đã logout
        var responseApplied = false
        val executed = engine.simulateDelayedNetworkResponse {
            // Giả định nếu Glide cũ chưa bị clear thì nó sẽ gán drawable của ảnh A
            responseApplied = true
            currentDisplayedRes.set(9999) // Mã ảnh A
        }

        // Khẳng định: Do đã clear, phản hồi muộn bị loại bỏ, KHÔNG được phép ghi đè lên icon Google
        assertFalse("Phản hồi muộn không được phép thực thi", executed)
        assertFalse("Drawable ảnh A không được phép ghi đè", responseApplied)
        assertEquals("Icon Google mặc định phải được bảo toàn nguyên vẹn", googleIconRes, currentDisplayedRes.get())
    }

    @Test
    fun testStaleAvatarResponse_cannotOverwriteDefaultIcon_whenSwitchingToUserWithoutPhoto() {
        val engine = FakeGlideEngine()
        val mockView = MockImageView()
        val currentDisplayedRes = AtomicInteger(0)
        val imageResourceSetter: (ImageView, Int) -> Unit = { _, resId ->
            engine.callOrder.add("SET_RES:$resId")
            currentDisplayedRes.set(resId)
        }

        val urlA = "https://example.com/avatar_user_a.jpg"
        val defaultAccountRes = 2001 // R.drawable.ic_account_circle stub

        // 1. Tải ảnh user A
        AvatarViewBinder.bindAvatar(
            imageView = mockView,
            photoUrl = urlA,
            fallbackRes = defaultAccountRes,
            engine = engine,
            imageResourceSetter = imageResourceSetter
        )

        // 2. Chuyển sang user B có photoUrl = "" (rỗng)
        AvatarViewBinder.bindAvatar(
            imageView = mockView,
            photoUrl = "",
            fallbackRes = defaultAccountRes,
            engine = engine,
            imageResourceSetter = imageResourceSetter
        )

        // Bắt buộc gọi CLEAR trước khi gán icon account circle
        assertEquals(
            listOf("LOAD:$urlA", "CLEAR", "SET_RES:$defaultAccountRes"),
            engine.callOrder
        )
        assertTrue(engine.isRequestCancelled.get())

        // 3. Giả lập phản hồi muộn từ URL A
        val executed = engine.simulateDelayedNetworkResponse {
            currentDisplayedRes.set(9999)
        }
        assertFalse(executed)
        assertEquals(defaultAccountRes, currentDisplayedRes.get())
    }
}
