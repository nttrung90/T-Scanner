package com.tscanner.app.utils

import android.util.Log
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.regex.Pattern

/**
 * Bộ phân tích và nhận diện ngôn ngữ văn bản tài liệu OCR (Gói O03).
 * Sử dụng Google ML Kit Language Identification kết hợp phân đoạn đoạn văn
 * và nhận dạng dấu tiếng Việt đặc trưng để phát hiện chính xác:
 * - CONFIDENT: Tiếng Việt ("vi"), English ("en"), hoặc ngôn ngữ khác ("ja", "de", "fr"...)
 * - MIXED_BILINGUAL: Tài liệu song ngữ Việt + Anh
 * - UNCERTAIN: Chưa xác định chắc chắn (chuỗi ngắn, toàn số, tên riêng, tiếng Việt không dấu...)
 */
object OcrLanguageIdentifier {

    private const val TAG = "OcrLanguageIdentifier"

    // Regex phát hiện các ký tự đặc trưng độc nhất của Tiếng Việt
    // Không bao gồm các ký tự dấu Latin đơn dùng chung với tiếng Pháp, Tây Ban Nha, Bồ Đào Nha (é, è, à, á, ó, ú, ã...)
    private val VIETNAMESE_UNIQUE_DIACRITICS_REGEX = Pattern.compile(
        "[đĐ" +
        "ăắằẳẵặĂẮẰẲẴẶ" +
        "ơớờởỡợƠỚỜỞỠỢ" +
        "ưứừửữựƯỨỪỬỮỰ" +
        "ấầẩẫậẤẦẨẪẬ" +
        "ếềểễệẾỀỂỄỆ" +
        "ốồổỗộỐỒỔỖỘ" +
        "ạẠẹẸịỊọỌụỤỵỴ" +
        "ảẢẻẺỉỈỏỎủỦỷỶ" +
        "ẽẼĩĨũŨỹỸ]"
    )

    // Tập từ vựng tiếng Việt phổ biến nhất để đối chiếu khi dấu bị mờ hoặc kiểm chứng ngôn ngữ
    private val COMMON_VIETNAMESE_WORDS = setOf(
        "và", "của", "trong", "người", "không", "được", "có", "là", "cho", "với",
        "các", "những", "này", "khi", "tại", "từ", "nhiều", "đã", "sẽ", "đang",
        "về", "đến", "theo", "như", "một", "hai", "ba", "năm", "ngày",
        "tháng", "cộng", "hòa", "xã", "hội", "chủ", "nghĩa", "việt", "nam", "độc",
        "lập", "tự", "do", "hạnh", "phúc"
    )

    // Tập từ vựng tiếng Anh phổ biến nhất để chống nhận nhầm tiếng Việt không dấu thành tiếng Anh
    private val COMMON_ENGLISH_WORDS = setOf(
        "the", "be", "to", "of", "and", "a", "in", "that", "have", "i",
        "it", "for", "not", "on", "with", "he", "as", "you", "do", "at",
        "this", "but", "his", "by", "from", "they", "we", "say", "her", "she",
        "or", "an", "will", "my", "one", "all", "would", "there", "their", "what",
        "so", "up", "out", "if", "about", "who", "get", "which", "go", "me",
        "when", "make", "can", "like", "time", "no", "just", "him", "know", "take",
        "people", "into", "year", "your", "good", "some", "could", "them", "see", "other",
        "than", "then", "now", "look", "only", "come", "its", "over", "think", "also",
        "back", "after", "use", "two", "how", "our", "work", "first", "well", "way",
        "even", "new", "want", "because", "any", "these", "give", "day", "most", "us"
    )

    fun hasVietnameseDiacritics(text: String): Boolean {
        return VIETNAMESE_UNIQUE_DIACRITICS_REGEX.matcher(text).find()
    }

    fun countVietnameseDiacriticWords(text: String): Int {
        val words = text.split("\\s+".toRegex())
        return words.count { hasVietnameseDiacritics(it) }
    }

    fun countCommonVietnameseWords(text: String): Int {
        val tokens = text.split("\\s+".toRegex())
        return tokens.count { token ->
            val cleaned = token.trim { !it.isLetter() }.lowercase(Locale.ROOT)
            cleaned.isNotEmpty() && COMMON_VIETNAMESE_WORDS.contains(cleaned)
        }
    }

    fun countCommonEnglishWords(text: String): Int {
        val tokens = text.split("\\s+".toRegex())
        return tokens.count { token ->
            val cleaned = token.trim { !it.isLetter() }.lowercase(Locale.ROOT)
            cleaned.isNotEmpty() && cleaned.all { it in 'a'..'z' } && COMMON_ENGLISH_WORDS.contains(cleaned)
        }
    }

    /**
     * Tách văn bản thành các đoạn văn riêng biệt sử dụng biểu thức chính quy chuẩn (F03).
     */
    fun splitIntoParagraphs(text: String): List<String> {
        return text.trim()
            .split(Regex("\\R+"))
            .map { it.trim() }
            .filter { it.length >= 20 }
    }

    /**
     * Thuật toán tổng hợp kết quả nhận diện ngôn ngữ độc lập với ML Kit:
     * - Ngắn hoặc tỷ lệ chữ cái < 40% (số/ký hiệu/bảng biểu) -> UNCERTAIN
     * - Đoạn Việt + Đoạn Anh -> MIXED_BILINGUAL
     * - Tiếng Việt có dấu độc nhất / từ vựng Việt -> CONFIDENT ("vi")
     * - Tiếng Anh có từ vựng chuẩn -> CONFIDENT ("en")
     * - Các ngôn ngữ khác từ ML Kit -> CONFIDENT nếu không xung đột với dấu tiếng Việt độc nhất
     * - Tiếng Việt không dấu hoặc từ vựng mơ hồ / xung đột -> UNCERTAIN
     */
    fun synthesizeDetectionResult(
        text: String,
        paragraphLanguages: List<String> = emptyList(),
        candidateLanguages: List<String> = listOf("vi", "en"),
        modelLanguages: String = "vie+eng"
    ): OcrPageDetectionResult {
        val trimmed = text.trim()
        if (trimmed.length < 15) {
            return OcrPageDetectionResult(
                status = OcrDetectionStatus.UNCERTAIN,
                detectedLanguages = emptyList(),
                candidateLanguages = candidateLanguages,
                modelLanguages = modelLanguages,
                confidence = 0f
            )
        }

        val lettersCount = trimmed.count { it.isLetter() }
        val letterRatio = lettersCount.toFloat() / trimmed.length
        if (letterRatio < 0.40f) {
            return OcrPageDetectionResult(
                status = OcrDetectionStatus.UNCERTAIN,
                detectedLanguages = emptyList(),
                candidateLanguages = candidateLanguages,
                modelLanguages = modelLanguages,
                confidence = 0f
            )
        }

        val viWordsCount = countVietnameseDiacriticWords(trimmed)
        val viCommonCount = countCommonVietnameseWords(trimmed)
        val enWordsCount = countCommonEnglishWords(trimmed)

        // Phân tích theo từng đoạn nếu có
        val viParagraphs = paragraphLanguages.count { it == "vi" }
        val enParagraphs = paragraphLanguages.count { it == "en" }

        // Trường hợp song ngữ: Khi có cả đoạn tiếng Việt và đoạn tiếng Anh đủ rõ
        val hasViSignal = viParagraphs >= 1 || (viWordsCount >= 1 && viCommonCount >= 1) || viWordsCount >= 2
        val hasEnSignal = enParagraphs >= 1 || enWordsCount >= 3
        if ((viParagraphs >= 1 && enParagraphs >= 1) || (hasViSignal && hasEnSignal && (viParagraphs + enParagraphs >= 2 || (viWordsCount >= 2 && enWordsCount >= 4)))) {
            val dynamicConfidence = (0.70f + 0.05f * (viParagraphs + enParagraphs)).coerceIn(0.75f, 0.95f)
            return OcrPageDetectionResult(
                status = OcrDetectionStatus.MIXED_BILINGUAL,
                detectedLanguages = listOf("vi", "en"),
                candidateLanguages = candidateLanguages,
                modelLanguages = modelLanguages,
                confidence = dynamicConfidence
            )
        }

        // Kiểm tra các ngôn ngữ khác từ ML Kit (Nhật, Trung, Đức, Pháp, Tây Ban Nha...)
        val otherLangs = paragraphLanguages.filter { it != "vi" && it != "en" && it != "und" }
        if (otherLangs.isNotEmpty()) {
            val mostFrequent = otherLangs.groupBy { it }.maxByOrNull { it.value.size }?.key
            if (mostFrequent != null) {
                // Nếu ML Kit nhận diện ngôn ngữ khác nhưng văn bản lại có dấu tiếng Việt độc nhất rõ ràng -> Xung đột
                if (viWordsCount >= 2 && viCommonCount >= 2) {
                    return OcrPageDetectionResult(
                        status = OcrDetectionStatus.UNCERTAIN,
                        detectedLanguages = emptyList(),
                        candidateLanguages = candidateLanguages,
                        modelLanguages = modelLanguages,
                        confidence = 0.20f
                    )
                }
                val otherFreq = otherLangs.count { it == mostFrequent }
                val dynamicConfidence = (0.65f + 0.10f * otherFreq).coerceIn(0.70f, 0.92f)
                return OcrPageDetectionResult(
                    status = OcrDetectionStatus.CONFIDENT,
                    detectedLanguages = listOf(mostFrequent),
                    candidateLanguages = candidateLanguages,
                    modelLanguages = modelLanguages,
                    confidence = dynamicConfidence
                )
            }
        }

        // Trường hợp Tiếng Việt rõ ràng:
        // Cần dấu tiếng Việt độc nhất HOẶC (có từ vựng tiếng Việt phổ biến VÀ detector nhận diện vi)
        val isConfirmedVietnamese = viWordsCount >= 2 ||
                (viWordsCount >= 1 && (viParagraphs >= 1 || viCommonCount >= 1)) ||
                (viParagraphs >= 1 && viCommonCount >= 2)

        if (isConfirmedVietnamese) {
            val dynamicConfidence = (0.65f + 0.08f * viWordsCount + 0.05f * viCommonCount + 0.10f * viParagraphs).coerceIn(0.75f, 0.95f)
            return OcrPageDetectionResult(
                status = OcrDetectionStatus.CONFIDENT,
                detectedLanguages = listOf("vi"),
                candidateLanguages = candidateLanguages,
                modelLanguages = modelLanguages,
                confidence = dynamicConfidence
            )
        }

        // Trường hợp Tiếng Anh: Chỉ gắn nhãn Anh khi có đủ từ vựng tiếng Anh đặc trưng và không có dấu Việt
        if (viWordsCount == 0 && (enWordsCount >= 3 || enParagraphs >= 1)) {
            val dynamicConfidence = (0.70f + 0.05f * enWordsCount + 0.10f * enParagraphs).coerceIn(0.75f, 0.95f)
            return OcrPageDetectionResult(
                status = OcrDetectionStatus.CONFIDENT,
                detectedLanguages = listOf("en"),
                candidateLanguages = candidateLanguages,
                modelLanguages = modelLanguages,
                confidence = dynamicConfidence
            )
        }

        // Tiếng Việt không dấu, tên riêng, chuỗi mơ hồ -> UNCERTAIN (Hợp đồng quy tắc R06)
        return OcrPageDetectionResult(
            status = OcrDetectionStatus.UNCERTAIN,
            detectedLanguages = emptyList(),
            candidateLanguages = candidateLanguages,
            modelLanguages = modelLanguages,
            confidence = 0.25f
        )
    }

    /**
     * Nhận diện ngôn ngữ bằng Google ML Kit trên từng đoạn văn (block/paragraph),
     * sau đó tổng hợp kết quả theo tiêu chí an toàn, chống gắn nhãn sai (F08).
     */
    suspend fun identify(
        text: String,
        candidateLanguages: List<String> = listOf("vi", "en"),
        modelLanguages: String = "vie+eng"
    ): OcrPageDetectionResult {
        val trimmed = text.trim()
        if (trimmed.length < 15) {
            return synthesizeDetectionResult(text, emptyList(), candidateLanguages, modelLanguages)
        }

        val paragraphs = splitIntoParagraphs(trimmed)
        val paragraphLangs = mutableListOf<String>()

        val options = LanguageIdentificationOptions.Builder()
            .setConfidenceThreshold(0.40f)
            .build()
        val client = LanguageIdentification.getClient(options)

        try {
            for (p in paragraphs.take(10)) {
                val lang = detectSingleString(client, p)
                if (lang != "und") {
                    paragraphLangs.add(lang)
                }
            }
        } catch (c: CancellationException) {
            // F08: Rethrow CancellationException để bảo toàn cơ chế hủy của coroutine
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "ML Kit Language Identification error: ${t.message}")
        } finally {
            // F08: Giải phóng client ngay khi kết thúc hoặc gặp lỗi
            try {
                client.close()
            } catch (_: Throwable) {}
        }

        return synthesizeDetectionResult(
            text = text,
            paragraphLanguages = paragraphLangs,
            candidateLanguages = candidateLanguages,
            modelLanguages = modelLanguages
        )
    }

    private suspend fun detectSingleString(
        client: com.google.mlkit.nl.languageid.LanguageIdentifier,
        input: String
    ): String = suspendCancellableCoroutine { cont ->
        client.identifyLanguage(input)
            .addOnSuccessListener { code ->
                if (cont.isActive) {
                    cont.resumeWith(Result.success(code))
                }
            }
            .addOnFailureListener { e ->
                if (cont.isActive) {
                    if (e is CancellationException) {
                        cont.cancel(e)
                    } else {
                        cont.resumeWith(Result.success("und"))
                    }
                }
            }
    }
}
