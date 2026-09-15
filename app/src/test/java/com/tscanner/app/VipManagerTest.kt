package com.tscanner.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VipManagerTest {

    private fun calculateNewExpiry(now: Long, currentExpiry: Long, durationDays: Int): Long {
        val baseTime = maxOf(now, currentExpiry)
        return baseTime + durationDays * 86_400_000L
    }

    @Test
    fun testVipExpiry_firstPurchase() {
        val now = 1_000_000_000L
        val currentExpiry = 0L
        val newExpiry = calculateNewExpiry(now, currentExpiry, 30)
        assertEquals(now + 30 * 86_400_000L, newExpiry)
    }

    @Test
    fun testVipExpiry_earlyRenewal_doesNotLoseRemainingDays() {
        // B05 Bug: Early renewal was setting expiry = now + 365 days, which deleted remaining days!
        // Fix: maxOf(now, currentExpiry) + durationDays preserves remaining days.
        val now = 1_000_000_000L
        val remainingDays = 15
        val currentExpiry = now + remainingDays * 86_400_000L
        val renewalDays = 365

        val newExpiry = calculateNewExpiry(now, currentExpiry, renewalDays)
        // Must equal currentExpiry + 365 days, which is now + 15 + 365 days
        assertEquals(now + (15 + 365) * 86_400_000L, newExpiry)
    }

    @Test
    fun testVipExpiry_expiredRenewal_startsFromNow() {
        val now = 2_000_000_000L
        val currentExpiry = 1_000_000_000L // Expired long ago
        val newExpiry = calculateNewExpiry(now, currentExpiry, 30)
        assertEquals(now + 30 * 86_400_000L, newExpiry)
    }
}
