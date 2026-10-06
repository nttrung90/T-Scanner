package com.tscanner.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Regression test for G06: Validating VIP session & restore strings across all 8 supported locales.
 */
class VipSessionLanguageRegressionTest {

    private val supportedLocales = listOf(
        "values",
        "values-vi",
        "values-es",
        "values-pt",
        "values-fr",
        "values-in",
        "values-de",
        "values-ja"
    )

    private val requiredKeys = listOf(
        "vip_session_expired_reauth_prompt",
        "vip_restore_purchases_btn",
        "vip_restore_success",
        "vip_restore_not_found",
        "vip_purchase_success",
        "vip_purchase_failed",
        "vip_billing_not_ready"
    )

    private fun getStringsFile(localeDir: String): File {
        val candidates = listOf(
            File("src/main/res/$localeDir/strings.xml"),
            File("app/src/main/res/$localeDir/strings.xml")
        )
        return candidates.firstOrNull { it.exists() }
            ?: throw IllegalStateException("Cannot find strings.xml for $localeDir")
    }

    private fun parseStrings(file: File): Map<String, String> {
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(file)
        val stringNodes = doc.getElementsByTagName("string")
        val result = mutableMapOf<String, String>()
        for (i in 0 until stringNodes.length) {
            val element = stringNodes.item(i) as Element
            val name = element.getAttribute("name")
            if (name.isNotEmpty()) {
                result[name] = element.textContent.trim()
            }
        }
        return result
    }

    @Test
    fun testAllEightLocales_containAllVipSessionAndRestoreKeys() {
        for (localeDir in supportedLocales) {
            val file = getStringsFile(localeDir)
            val stringsMap = parseStrings(file)
            for (key in requiredKeys) {
                assertTrue("Locale '$localeDir' must declare string key '$key'", stringsMap.containsKey(key))
                val value = stringsMap[key]
                assertNotNull("Value for '$key' in '$localeDir' must not be null", value)
                assertFalse("Value for '$key' in '$localeDir' must not be empty", value.isNullOrBlank())
            }
        }
    }

    @Test
    fun testDefaultStrings_mustBeInEnglish_notVietnamese() {
        val defaultFile = getStringsFile("values")
        val stringsMap = parseStrings(defaultFile)

        val reauthPrompt = stringsMap["vip_session_expired_reauth_prompt"]
        assertNotNull(reauthPrompt)
        assertTrue("Default reauth prompt must be in English", reauthPrompt!!.startsWith("Authentication session"))

        val restoreBtn = stringsMap["vip_restore_purchases_btn"]
        assertEquals("Default restore button must be 'Restore purchases'", "Restore purchases", restoreBtn)

        val restoreSuccess = stringsMap["vip_restore_success"]
        assertEquals("Default restore success must be English", "VIP package successfully restored from Google Play!", restoreSuccess)

        val billingNotReady = stringsMap["vip_billing_not_ready"]
        assertEquals("Default billing not ready must be English", "Google Play Billing service is not ready. Please try again later.", billingNotReady)
    }

    @Test
    fun testVietnameseStrings_mustBeProperVietnamese() {
        val viFile = getStringsFile("values-vi")
        val stringsMap = parseStrings(viFile)

        val reauthPrompt = stringsMap["vip_session_expired_reauth_prompt"]
        assertNotNull(reauthPrompt)
        assertTrue("Vietnamese reauth prompt must contain 'Phiên xác thực đã hết hạn'", reauthPrompt!!.contains("Phiên xác thực đã hết hạn"))

        val restoreBtn = stringsMap["vip_restore_purchases_btn"]
        assertEquals("Khôi phục giao dịch mua", restoreBtn)
    }
}
