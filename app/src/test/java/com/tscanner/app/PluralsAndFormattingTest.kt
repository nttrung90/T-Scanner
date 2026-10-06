package com.tscanner.app

import com.tscanner.app.utils.FileUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class PluralsAndFormattingTest {

    @Test
    fun testFileUtils_formatFileSize() {
        assertEquals("0 KB", FileUtils.formatFileSize(0))
        assertEquals("0 KB", FileUtils.formatFileSize(-100))
        assertEquals("1 KB", FileUtils.formatFileSize(1024))
        assertEquals("100 KB", FileUtils.formatFileSize(100 * 1024))
        assertEquals("1.0 MB", FileUtils.formatFileSize(1024 * 1024))
        assertEquals("2.5 MB", FileUtils.formatFileSize((2.5 * 1024 * 1024).toLong()))
    }

    @Test
    fun testFileUtils_formatDate_fallbackDefault() {
        val timestamp = 1700000000000L
        val formatted = FileUtils.formatDate(timestamp)
        assertNotNull(formatted)
        assertTrue(formatted.contains(":"))
    }

    @Test
    fun testPluralsXml_tagsAndQuantities() {
        data class LocalePluralExpectation(
            val folder: String,
            val requiredQuantities: Set<String>
        )

        val locales = listOf(
            LocalePluralExpectation("values", setOf("one", "other")),
            LocalePluralExpectation("values-vi", setOf("other")),
            LocalePluralExpectation("values-es", setOf("one", "other")),
            LocalePluralExpectation("values-pt", setOf("one", "other")),
            LocalePluralExpectation("values-fr", setOf("one", "other")),
            LocalePluralExpectation("values-in", setOf("other")),
            LocalePluralExpectation("values-de", setOf("one", "other")),
            LocalePluralExpectation("values-ja", setOf("other"))
        )

        val requiredPluralNames = setOf(
            "files_count_plurals",
            "pages_count_plurals",
            "items_count_plurals",
            "folder_info_documents_plurals",
            "extracted_images_count_plurals",
            "auto_syncing_docs_to_drive_plurals",
            "synced_docs_from_drive_plurals"
        )

        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()

        for (locale in locales) {
            val file = File("src/main/res/${locale.folder}/strings.xml").let {
                if (it.exists()) it else File("app/src/main/res/${locale.folder}/strings.xml")
            }
            assertTrue("Resource file ${locale.folder}/strings.xml must exist", file.exists())

            val doc = builder.parse(file)
            val pluralsNodes = doc.getElementsByTagName("plurals")
            assertTrue(
                "Resource file ${locale.folder}/strings.xml must declare at least 7 plurals",
                pluralsNodes.length >= 7
            )

            val foundPlurals = mutableMapOf<String, Set<String>>()
            for (i in 0 until pluralsNodes.length) {
                val node = pluralsNodes.item(i)
                val name = node.attributes.getNamedItem("name")?.nodeValue ?: continue
                val itemNodes = (node as org.w3c.dom.Element).getElementsByTagName("item")
                val quantities = mutableSetOf<String>()
                for (j in 0 until itemNodes.length) {
                    val itemNode = itemNodes.item(j)
                    val q = itemNode.attributes.getNamedItem("quantity")?.nodeValue
                    val text = itemNode.textContent?.trim() ?: ""
                    assertTrue(
                        "Plural item '$name' ($q) in ${locale.folder} must not be empty",
                        text.isNotEmpty()
                    )
                    if (q != null) quantities.add(q)
                }
                foundPlurals[name] = quantities
            }

            for (req in requiredPluralNames) {
                assertTrue(
                    "${locale.folder} plurals must contain $req",
                    foundPlurals.containsKey(req)
                )
                val quantities = foundPlurals[req]!!
                for (expectedQ in locale.requiredQuantities) {
                    assertTrue(
                        "Plural $req in ${locale.folder} must have quantity '$expectedQ'",
                        quantities.contains(expectedQ)
                    )
                }
            }
        }
    }

    @Test
    fun testRtlDrawables_haveAutoMirrored() {
        val arrowBackFile = File("src/main/res/drawable/ic_arrow_back.xml").let { if (it.exists()) it else File("app/src/main/res/drawable/ic_arrow_back.xml") }
        val chevronRightFile = File("src/main/res/drawable/ic_chevron_right.xml").let { if (it.exists()) it else File("app/src/main/res/drawable/ic_chevron_right.xml") }

        assertTrue("ic_arrow_back.xml must exist", arrowBackFile.exists())
        assertTrue("ic_chevron_right.xml must exist", chevronRightFile.exists())

        val arrowBackContent = arrowBackFile.readText()
        assertTrue("ic_arrow_back must have android:autoMirrored=\"true\"", arrowBackContent.contains("android:autoMirrored=\"true\""))

        val chevronRightContent = chevronRightFile.readText()
        assertTrue("ic_chevron_right must have android:autoMirrored=\"true\"", chevronRightContent.contains("android:autoMirrored=\"true\""))
    }

    @Test
    fun testAllToolsAndPdfConversionResources() {
        val locales = listOf("values", "values-vi", "values-es", "values-pt", "values-fr", "values-in", "values-de", "values-ja")
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()

        for (loc in locales) {
            val file = File("src/main/res/$loc/strings.xml").let {
                if (it.exists()) it else File("app/src/main/res/$loc/strings.xml")
            }
            assertTrue("Resource file $loc/strings.xml must exist", file.exists())
            val doc = builder.parse(file)
            val stringNodes = doc.getElementsByTagName("string")
            var foundAllTools = false
            var foundCreatePdf = false
            for (i in 0 until stringNodes.length) {
                val node = stringNodes.item(i)
                val name = node.attributes?.getNamedItem("name")?.nodeValue
                if (name == "action_all_tools") {
                    foundAllTools = true
                    assertTrue("action_all_tools in $loc must not be blank", node.textContent.isNotBlank())
                }
                if (name == "action_create_pdf") {
                    foundCreatePdf = true
                    assertTrue("action_create_pdf in $loc must not be blank", node.textContent.isNotBlank())
                }
            }
            assertTrue("action_all_tools must be declared in $loc", foundAllTools)
            assertTrue("action_create_pdf must be declared in $loc", foundCreatePdf)
        }

        val homeLayout = File("src/main/res/layout/fragment_home.xml").let {
            if (it.exists()) it else File("app/src/main/res/layout/fragment_home.xml")
        }
        val homeText = homeLayout.readText()
        assertTrue("fragment_home.xml must contain btn_action_all_tools", homeText.contains("android:id=\"@+id/btn_action_all_tools\""))
        assertFalse("fragment_home.xml must not contain btn_action_create_pdf", homeText.contains("android:id=\"@+id/btn_action_create_pdf\""))

        val toolsLayout = File("src/main/res/layout/fragment_tools.xml").let {
            if (it.exists()) it else File("app/src/main/res/layout/fragment_tools.xml")
        }
        val toolsText = toolsLayout.readText()
        assertTrue("fragment_tools.xml must contain tool_convert_create_pdf", toolsText.contains("android:id=\"@+id/tool_convert_create_pdf\""))
    }
}
