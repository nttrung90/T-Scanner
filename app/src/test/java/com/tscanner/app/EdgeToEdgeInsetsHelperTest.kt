package com.tscanner.app

import com.tscanner.app.utils.EdgeToEdgeInsetsHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EdgeToEdgeInsetsHelperTest {

    @Test
    fun testCalculateEffectiveBottom_withoutIme_usesSystemBarsOnly() {
        val sysBottom = 60
        val imeBottom = 300

        val result = EdgeToEdgeInsetsHelper.calculateEffectiveBottom(
            systemBarBottom = sysBottom,
            imeBottom = imeBottom,
            includeIme = false
        )
        assertEquals(60, result)
    }

    @Test
    fun testCalculateEffectiveBottom_withIme_usesMaxAndNeverDoubleCounts() {
        val sysBottom = 60
        val imeBottom = 320

        val result = EdgeToEdgeInsetsHelper.calculateEffectiveBottom(
            systemBarBottom = sysBottom,
            imeBottom = imeBottom,
            includeIme = true
        )
        // Must be max(60, 320) = 320, strictly NOT 60 + 320 = 380
        assertEquals(320, result)
        assertNotEquals(60 + 320, result)
    }

    @Test
    fun testCalculateEffectiveBottom_withImeClosed_usesSystemBarBottom() {
        val sysBottom = 72
        val imeBottom = 0 // Keyboard closed

        val result = EdgeToEdgeInsetsHelper.calculateEffectiveBottom(
            systemBarBottom = sysBottom,
            imeBottom = imeBottom,
            includeIme = true
        )
        assertEquals(72, result)
    }

    private open class TestView(context: android.content.Context? = null) : android.view.View(context) {
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
    fun testApplyTopAndBottomBarInsets_productionCalls_withoutDoublePadding() {
        val topBar = TestView().apply { setInitial(16, 12, 16, 12) }
        val bottomBar = TestView().apply { setInitial(16, 10, 16, 12) }

        val initialTop = EdgeToEdgeInsetsHelper.recordInitialPadding(topBar)
        val initialBottom = EdgeToEdgeInsetsHelper.recordInitialPadding(bottomBar)

        val sysInsets = androidx.core.graphics.Insets.of(20, 48, 20, 64)

        // Gọi trực tiếp production method applyTopBarInsets và applyBottomBarInsets (extra = 0)
        EdgeToEdgeInsetsHelper.applyTopBarInsets(topBar, initialTop, sysInsets)
        EdgeToEdgeInsetsHelper.applyBottomBarInsets(
            bottomBar,
            initialBottom,
            sysInsets,
            bottomInset = sysInsets.bottom,
            extraBottom = 0
        )

        // Top bar: initial (12) + sysInsets.top (48) = 60, không bị cộng thừa
        assertEquals(16 + 20, topBar.paddingLeft)
        assertEquals(12 + 48, topBar.paddingTop)
        assertEquals(16 + 20, topBar.paddingRight)
        assertEquals(12, topBar.paddingBottom)

        // Bottom bar: initial (12) + bottomInset (64) = 76, KHÔNG bị thành 12 + 64 + 12 = 88
        assertEquals(16 + 20, bottomBar.paddingLeft)
        assertEquals(10, bottomBar.paddingTop)
        assertEquals(16 + 20, bottomBar.paddingRight)
        assertEquals(12 + 64, bottomBar.paddingBottom)
    }

    @Test
    fun testInitialPaddingRecord_andMultipleDispatchInvariance_callingProductionHelper() {
        val view = TestView().apply { setInitial(16, 12, 16, 20) }
        val initialPadding = EdgeToEdgeInsetsHelper.recordInitialPadding(view)
        val insets = androidx.core.graphics.Insets.of(24, 48, 24, 72)

        // Gọi trực tiếp production method applyBottomBarInsets qua 10 lần dispatch
        for (i in 1..10) {
            EdgeToEdgeInsetsHelper.applyBottomBarInsets(
                view = view,
                initial = initialPadding,
                sysInsets = insets,
                bottomInset = insets.bottom,
                extraBottom = 0
            )

            // Invariant check: Insets must NEVER accumulate across dispatches
            assertEquals(16 + 24, view.paddingLeft)
            assertEquals(12, view.paddingTop)
            assertEquals(16 + 24, view.paddingRight)
            assertEquals(20 + 72, view.paddingBottom)
        }
    }
}
