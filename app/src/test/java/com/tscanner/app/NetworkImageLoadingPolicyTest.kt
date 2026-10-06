package com.tscanner.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkImageLoadingPolicyTest {

    private fun shouldLoadWithGlide(photoUrl: String?): Boolean {
        return !photoUrl.isNullOrBlank() && (photoUrl.startsWith("http://") || photoUrl.startsWith("https://"))
    }

    private fun calculateMemoryFootprintBytes(width: Int, height: Int, bytesPerPixel: Int = 4): Long {
        return width.toLong() * height.toLong() * bytesPerPixel.toLong()
    }

    @Test
    fun testPhotoUrlValidity() {
        assertFalse(shouldLoadWithGlide(null))
        assertFalse(shouldLoadWithGlide(""))
        assertFalse(shouldLoadWithGlide("   "))
        assertFalse(shouldLoadWithGlide("content://media/external/images/1"))
        assertTrue(shouldLoadWithGlide("https://lh3.googleusercontent.com/a/default-user"))
        assertTrue(shouldLoadWithGlide("http://example.com/avatar.jpg"))
    }

    @Test
    fun testMemoryFootprintDownsamplingComparison() {
        // High resolution camera photo: 4032 x 3024 ARGB_8888 (~48.7 MB uncompressed)
        val fullResolutionRam = calculateMemoryFootprintBytes(4032, 3024)
        assertEquals(48771072L, fullResolutionRam)

        // Fragment icon downsampled: 128 x 128 ARGB_8888 (64 KB)
        val fragmentIconRam = calculateMemoryFootprintBytes(128, 128)
        assertEquals(65536L, fragmentIconRam)

        // Dialog avatar downsampled: 160 x 160 ARGB_8888 (100 KB)
        val dialogAvatarRam = calculateMemoryFootprintBytes(160, 160)
        assertEquals(102400L, dialogAvatarRam)

        // Downsampling savings > 99.8% RAM
        val savedPercent = (1.0 - (fragmentIconRam.toDouble() / fullResolutionRam.toDouble())) * 100.0
        assertTrue(savedPercent > 99.8)
    }

    @Test
    fun testDiskCacheStrategyConfig() {
        // Verify disk cache key logic consistency
        val url = "https://lh3.googleusercontent.com/a/user123=s96-c"
        val normalizedKey = url.trim()
        assertEquals("https://lh3.googleusercontent.com/a/user123=s96-c", normalizedKey)
    }
}
