package com.tscanner.app.utils

import java.lang.Character.UnicodeBlock

/**
 * Họ hệ chữ viết nhận diện được từ văn bản / hình ảnh (Gói O05).
 */
enum class ScriptFamily {
    LATIN,
    JAPANESE_KANA,      // Chứa Hiragana hoặc Katakana (xác định tiếng Nhật chắc chắn)
    CJK_IDEOGRAPH,      // Chứa Hán tự (chưa phân biệt chắc chắn Trung giản thể/phồn thể/Kanji)
    HANGUL,             // Tiếng Hàn
    DEVANAGARI,         // Chữ Devanagari (Tiếng Hindi)
    UNKNOWN
}

/**
 * Kết quả phân tích hệ chữ (Script Analysis Result).
 */
data class ScriptAnalysisResult(
    val primaryScript: ScriptFamily,
    val scriptCounts: Map<ScriptFamily, Int>,
    val totalScriptCharacters: Int,
    val isAmbiguousCjk: Boolean = false,
    val recommendedTag: String? = null
)

/**
 * Thử nghiệm và phân loại hệ chữ (Script Probe) độc lập (Gói O05).
 * Tuân thủ nghiêm ngặt các nguyên tắc:
 * - Nhận diện dựa trên khối Unicode chuẩn.
 * - Có Kana (Hiragana/Katakana) -> Nhận diện chắc chắn Tiếng Nhật ("ja").
 * - Chỉ có Hán tự mà không có Kana -> Đánh dấu mơ hồ (isAmbiguousCjk = true), không tự đoán giản thể/phồn thể.
 * - Chữ Hangul -> Nhận diện Tiếng Hàn ("ko").
 * - Chữ Devanagari -> Nhận diện Tiếng Hindi ("hi").
 */
object OcrScriptProbe {

    fun analyzeTextScript(text: String): ScriptAnalysisResult {
        val counts = mutableMapOf<ScriptFamily, Int>().withDefault { 0 }
        var total = 0

        for (ch in text) {
            if (!ch.isLetter()) continue
            val block = try {
                UnicodeBlock.of(ch)
            } catch (_: Throwable) {
                null
            } ?: continue

            val family = when (block) {
                UnicodeBlock.HIRAGANA,
                UnicodeBlock.KATAKANA,
                UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS -> ScriptFamily.JAPANESE_KANA

                UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
                UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
                UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS -> ScriptFamily.CJK_IDEOGRAPH

                UnicodeBlock.HANGUL_SYLLABLES,
                UnicodeBlock.HANGUL_JAMO,
                UnicodeBlock.HANGUL_COMPATIBILITY_JAMO -> ScriptFamily.HANGUL

                UnicodeBlock.DEVANAGARI,
                UnicodeBlock.DEVANAGARI_EXTENDED -> ScriptFamily.DEVANAGARI

                UnicodeBlock.BASIC_LATIN,
                UnicodeBlock.LATIN_1_SUPPLEMENT,
                UnicodeBlock.LATIN_EXTENDED_A,
                UnicodeBlock.LATIN_EXTENDED_B,
                UnicodeBlock.LATIN_EXTENDED_ADDITIONAL -> ScriptFamily.LATIN

                else -> null
            }

            if (family != null) {
                counts[family] = counts.getValue(family) + 1
                total++
            }
        }

        if (total == 0) {
            return ScriptAnalysisResult(
                primaryScript = ScriptFamily.UNKNOWN,
                scriptCounts = emptyMap(),
                totalScriptCharacters = 0
            )
        }

        val kanaCount = counts.getValue(ScriptFamily.JAPANESE_KANA)
        val cjkCount = counts.getValue(ScriptFamily.CJK_IDEOGRAPH)
        val hangulCount = counts.getValue(ScriptFamily.HANGUL)
        val devanagariCount = counts.getValue(ScriptFamily.DEVANAGARI)
        val latinCount = counts.getValue(ScriptFamily.LATIN)

        // 1. Tiếng Nhật: có chữ Kana (Hiragana / Katakana)
        if (kanaCount > 0) {
            return ScriptAnalysisResult(
                primaryScript = ScriptFamily.JAPANESE_KANA,
                scriptCounts = counts,
                totalScriptCharacters = total,
                isAmbiguousCjk = false,
                recommendedTag = "ja"
            )
        }

        // 2. Tiếng Hàn: có chữ Hangul
        if (hangulCount > 0) {
            return ScriptAnalysisResult(
                primaryScript = ScriptFamily.HANGUL,
                scriptCounts = counts,
                totalScriptCharacters = total,
                isAmbiguousCjk = false,
                recommendedTag = "ko"
            )
        }

        // 3. Devanagari (Hindi)
        if (devanagariCount > 0) {
            return ScriptAnalysisResult(
                primaryScript = ScriptFamily.DEVANAGARI,
                scriptCounts = counts,
                totalScriptCharacters = total,
                isAmbiguousCjk = false,
                recommendedTag = "hi"
            )
        }

        // 4. Chỉ chứa Hán tự (CJK): Không đủ bằng chứng phân biệt Giản thể / Phồn thể / Nhật Kanji thuần
        if (cjkCount > 0) {
            return ScriptAnalysisResult(
                primaryScript = ScriptFamily.CJK_IDEOGRAPH,
                scriptCounts = counts,
                totalScriptCharacters = total,
                isAmbiguousCjk = true,
                recommendedTag = null // Không đoán chắc, để người dùng chọn
            )
        }

        // 5. Hệ chữ Latin
        return ScriptAnalysisResult(
            primaryScript = ScriptFamily.LATIN,
            scriptCounts = counts,
            totalScriptCharacters = total,
            isAmbiguousCjk = false,
            recommendedTag = null
        )
    }
}
