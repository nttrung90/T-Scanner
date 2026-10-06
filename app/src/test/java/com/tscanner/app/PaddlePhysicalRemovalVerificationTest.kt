package com.tscanner.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * P04: Kiểm thử xác minh loại bỏ hoàn toàn mã thực thi, assets, runtime ONNX và cấu hình Paddle.
 */
class PaddlePhysicalRemovalVerificationTest {

    @Test
    fun testPaddleOcrEngineClass_doesNotExist() {
        try {
            Class.forName("com.tscanner.app.paddleocr.PaddleOcrEngine")
            fail("Class PaddleOcrEngine không được phép tồn tại sau khi đã loại bỏ")
        } catch (_: ClassNotFoundException) {
            // Expected
        }
    }

    @Test
    fun testOnnxRuntimeClasses_doNotExistOnClasspath() {
        val onnxClasses = listOf(
            "ai.onnxruntime.OrtEnvironment",
            "ai.onnxruntime.OrtSession",
            "ai.onnxruntime.OnnxTensor",
            "ai.onnxruntime.TensorInfo"
        )
        for (cls in onnxClasses) {
            try {
                Class.forName(cls)
                fail("Class $cls không được phép tồn tại trên classpath sau khi gỡ dependency ONNX")
            } catch (_: ClassNotFoundException) {
                // Expected
            }
        }
    }

    @Test
    fun testPaddleAssets_doNotExistInProject() {
        val projectRoot = findProjectRoot()
        val assetsDir = File(projectRoot, "app/src/main/assets/paddleocr")
        assertFalse("Thư mục assets/paddleocr không được phép tồn tại", assetsDir.exists())

        val detModel = File(projectRoot, "app/src/main/assets/paddleocr/ch_PP-OCRv4_det.onnx")
        val recModel = File(projectRoot, "app/src/main/assets/paddleocr/ch_PP-OCRv4_rec.onnx")
        val keysTxt = File(projectRoot, "app/src/main/assets/paddleocr/ppocr_keys_v1.txt")
        val dictTxt = File(projectRoot, "app/src/main/assets/paddleocr/vi_dict.txt")

        assertFalse("Asset ch_PP-OCRv4_det.onnx không được phép tồn tại", detModel.exists())
        assertFalse("Asset ch_PP-OCRv4_rec.onnx không được phép tồn tại", recModel.exists())
        assertFalse("Asset ppocr_keys_v1.txt không được phép tồn tại", keysTxt.exists())
        assertFalse("Asset vi_dict.txt không được phép tồn tại", dictTxt.exists())
    }

    @Test
    fun testBuildGradle_hasNoOnnxReferences() {
        val projectRoot = findProjectRoot()
        val buildGradle = File(projectRoot, "app/build.gradle").readText()

        assertFalse("build.gradle không được chứa onnxruntime dependency", buildGradle.contains("com.microsoft.onnxruntime"))
        assertFalse("build.gradle aaptOptions không được chứa 'onnx'", buildGradle.contains("'onnx'"))
        assertTrue("build.gradle vẫn phải giữ traineddata trong noCompress", buildGradle.contains("noCompress 'traineddata'"))
    }

    @Test
    fun testProguardRules_hasNoOnnxRules() {
        val projectRoot = findProjectRoot()
        val proguardRules = File(projectRoot, "app/proguard-rules.pro").readText()

        assertFalse("proguard-rules.pro không được chứa ai.onnxruntime", proguardRules.contains("ai.onnxruntime"))
        assertTrue("proguard-rules.pro vẫn phải giữ rules ML Kit", proguardRules.contains("com.google.mlkit"))
        assertTrue("proguard-rules.pro vẫn phải giữ rules Tesseract", proguardRules.contains("cz.adaptech.tesseract4android"))
    }

    @Test
    fun testTScannerApplication_hasNoPaddleStartupInit() {
        val projectRoot = findProjectRoot()
        val appSource = File(projectRoot, "app/src/main/java/com/tscanner/app/TScannerApplication.kt").readText()

        assertFalse("TScannerApplication.kt không được gọi PaddleOcrEngine", appSource.contains("PaddleOcrEngine"))
        assertFalse("TScannerApplication.kt không được tham chiếu package paddleocr", appSource.contains("paddleocr"))
        assertTrue("TScannerApplication.kt vẫn phải khởi tạo Tesseract trong background", appSource.contains("TesseractOcrHelper.prepareTessData"))
        assertTrue("TScannerApplication.kt vẫn phải dọn temp scans trong background", appSource.contains("FileUtils.cleanOrphanedTempScans"))
    }

    private fun findProjectRoot(): File {
        var dir: File? = File(".").canonicalFile
        while (dir != null && !File(dir, "settings.gradle").exists()) {
            dir = dir.parentFile
        }
        return dir ?: File(".")
    }
}
