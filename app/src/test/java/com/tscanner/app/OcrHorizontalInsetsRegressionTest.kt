package com.tscanner.app

import android.content.Context
import android.view.View
import androidx.core.graphics.Insets
import com.tscanner.app.utils.EdgeToEdgeInsetsHelper
import org.junit.Assert.assertEquals
import org.junit.Test

class OcrHorizontalInsetsRegressionTest {

    private open class InspectableView(context: Context? = null) : View(context) {
        private var _left = 0
        private var _top = 0
        private var _right = 0
        private var _bottom = 0

        fun setInitial(l: Int, t: Int, r: Int, b: Int) {
            _left = l
            _top = t
            _right = r
            _bottom = b
        }

        override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
            _left = left
            _top = top
            _right = right
            _bottom = bottom
        }

        override fun getPaddingLeft(): Int = _left
        override fun getPaddingTop(): Int = _top
        override fun getPaddingRight(): Int = _right
        override fun getPaddingBottom(): Int = _bottom
    }

    @Test
    fun testApplyContentHorizontalInsets_protectsLandscapeCutoutAndNavBar() {
        val searchBar = InspectableView().apply { setInitial(16, 6, 16, 6) }
        val languageBanner = InspectableView().apply { setInitial(16, 8, 16, 8) }
        val contentContainer = InspectableView().apply { setInitial(0, 0, 0, 0) }

        val initialSearchBar = EdgeToEdgeInsetsHelper.recordInitialPadding(searchBar)
        val initialLanguage = EdgeToEdgeInsetsHelper.recordInitialPadding(languageBanner)
        val initialContent = EdgeToEdgeInsetsHelper.recordInitialPadding(contentContainer)

        // Giả lập Landscape: Tai thỏ (cutout) bên trái 48px, Navigation 3 nút bên phải 56px
        val landscapeCutoutInsets = Insets.of(48, 0, 56, 0)

        // Áp dụng production method
        EdgeToEdgeInsetsHelper.applyContentHorizontalInsets(searchBar, initialSearchBar, landscapeCutoutInsets)
        EdgeToEdgeInsetsHelper.applyContentHorizontalInsets(languageBanner, initialLanguage, landscapeCutoutInsets)
        EdgeToEdgeInsetsHelper.applyContentHorizontalInsets(contentContainer, initialContent, landscapeCutoutInsets)

        // Khẳng định Search Bar được đẩy vào trong an toàn
        assertEquals(16 + 48, searchBar.paddingLeft)
        assertEquals(16 + 56, searchBar.paddingRight)
        assertEquals(6, searchBar.paddingTop)
        assertEquals(6, searchBar.paddingBottom)

        // Khẳng định Language Banner không bị che bởi cutout
        assertEquals(16 + 48, languageBanner.paddingLeft)
        assertEquals(16 + 56, languageBanner.paddingRight)

        // Khẳng định Container nội dung 3 tab OCR có safe area bảo vệ
        assertEquals(48, contentContainer.paddingLeft)
        assertEquals(56, contentContainer.paddingRight)
    }

    @Test
    fun testMultipleDispatches_remainInvariant_withoutAccumulatingPadding() {
        val container = InspectableView().apply { setInitial(12, 10, 12, 10) }
        val initial = EdgeToEdgeInsetsHelper.recordInitialPadding(container)
        val insets = Insets.of(32, 0, 32, 0)

        // Dispatch insets 10 lần liên tục
        for (i in 1..10) {
            EdgeToEdgeInsetsHelper.applyContentHorizontalInsets(container, initial, insets)
        }

        // Padding tuyệt đối không bị cộng dồn
        assertEquals(12 + 32, container.paddingLeft)
        assertEquals(12 + 32, container.paddingRight)
        assertEquals(10, container.paddingTop)
        assertEquals(10, container.paddingBottom)
    }
}
