package com.tscanner.app.utils

import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.annotation.VisibleForTesting
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.tscanner.app.R

/**
 * Điều phối hiển thị Avatar người dùng và hủy các request tải ảnh cũ
 * trước khi áp dụng icon mặc định (tránh race condition khi mạng chậm).
 */
object AvatarViewBinder {

    interface AvatarLoaderEngine {
        fun clear(imageView: ImageView)
        fun loadCircleAvatar(
            imageView: ImageView,
            url: String,
            sizePx: Int,
            @DrawableRes placeholderRes: Int,
            @DrawableRes errorRes: Int
        )
    }

    class GlideAvatarLoader(
        private val requestManagerProvider: (ImageView) -> RequestManager = { view -> Glide.with(view) }
    ) : AvatarLoaderEngine {
        override fun clear(imageView: ImageView) {
            requestManagerProvider(imageView).clear(imageView)
        }

        override fun loadCircleAvatar(
            imageView: ImageView,
            url: String,
            sizePx: Int,
            @DrawableRes placeholderRes: Int,
            @DrawableRes errorRes: Int
        ) {
            requestManagerProvider(imageView)
                .load(url)
                .override(sizePx, sizePx)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .transform(CircleCrop())
                .placeholder(placeholderRes)
                .error(errorRes)
                .into(imageView)
        }
    }

    var defaultEngine: AvatarLoaderEngine = GlideAvatarLoader()

    /**
     * Gắn ảnh đại diện hoặc fallback về drawable mặc định.
     * BẮT BUỘC: Nếu không có URL hoặc đăng xuất, phải gọi engine.clear(imageView)
     * TRƯỚC KHI gọi imageView.setImageResource(fallbackRes) để hủy request cũ đang chạy.
     */
    fun bindAvatar(
        imageView: ImageView,
        photoUrl: String?,
        @DrawableRes fallbackRes: Int,
        sizePx: Int = 128,
        engine: AvatarLoaderEngine = defaultEngine,
        imageResourceSetter: (ImageView, Int) -> Unit = { view, resId -> view.setImageResource(resId) }
    ) {
        if (!photoUrl.isNullOrEmpty()) {
            engine.loadCircleAvatar(
                imageView = imageView,
                url = photoUrl,
                sizePx = sizePx,
                placeholderRes = fallbackRes,
                errorRes = fallbackRes
            )
        } else {
            // Quan trọng: Hủy request in-flight trước khi đặt drawable mặc định
            engine.clear(imageView)
            imageResourceSetter(imageView, fallbackRes)
        }
    }

    /**
     * Hủy triệt để request trên ImageView khi view bị hủy hoặc stop
     */
    fun clearAvatar(
        imageView: ImageView,
        engine: AvatarLoaderEngine = defaultEngine
    ) {
        engine.clear(imageView)
    }
}
