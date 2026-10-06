package com.tscanner.app.ui.idcard

import android.os.Bundle
import com.tscanner.app.utils.IdCardComposeConfig
import com.tscanner.app.utils.IdCardLayoutMode
import com.tscanner.app.utils.IdCardScaleMode
import com.tscanner.app.utils.WatermarkHelper

/**
 * Lightweight state holder representing an in-progress ID card compose session.
 * Used for state restoration across configuration changes (such as language switching)
 * and process death without storing heavy Bitmaps in the Bundle.
 */
data class IdCardSessionDraft(
    val frontImagePath: String? = null,
    val backImagePath: String? = null,
    val config: IdCardComposeConfig = IdCardComposeConfig()
) {
    fun toBundle(): Bundle = Bundle().apply {
        putString(KEY_FRONT_PATH, frontImagePath)
        putString(KEY_BACK_PATH, backImagePath)
        putString(KEY_LAYOUT_MODE, config.layoutMode.name)
        putString(KEY_SCALE_MODE, config.scaleMode.name)
        putBoolean(KEY_SHOW_CUT_BORDER, config.showCutBorder)
        putBoolean(KEY_ADD_WATERMARK, config.addWatermark)
        putString(KEY_WATERMARK_TEXT, config.watermarkText)
    }

    fun toMap(): Map<String, Any?> = mapOf(
        KEY_FRONT_PATH to frontImagePath,
        KEY_BACK_PATH to backImagePath,
        KEY_LAYOUT_MODE to config.layoutMode.name,
        KEY_SCALE_MODE to config.scaleMode.name,
        KEY_SHOW_CUT_BORDER to config.showCutBorder,
        KEY_ADD_WATERMARK to config.addWatermark,
        KEY_WATERMARK_TEXT to config.watermarkText
    )

    companion object {
        const val EXTRA_SESSION_DRAFT = "extra_id_card_session_draft"

        const val KEY_FRONT_PATH = "key_draft_front_path"
        const val KEY_BACK_PATH = "key_draft_back_path"
        const val KEY_LAYOUT_MODE = "key_draft_layout_mode"
        const val KEY_SCALE_MODE = "key_draft_scale_mode"
        const val KEY_SHOW_CUT_BORDER = "key_draft_show_cut_border"
        const val KEY_ADD_WATERMARK = "key_draft_add_watermark"
        const val KEY_WATERMARK_TEXT = "key_draft_watermark_text"

        fun fromValues(
            frontPath: String?,
            backPath: String?,
            layoutModeStr: String?,
            scaleModeStr: String?,
            showCutBorder: Boolean = true,
            addWatermark: Boolean = true,
            watermarkText: String? = null
        ): IdCardSessionDraft {
            val layoutMode = layoutModeStr?.let { runCatching { IdCardLayoutMode.valueOf(it) }.getOrNull() }
                ?: IdCardLayoutMode.A4_PORTRAIT_TWO_SIDES
            val scaleMode = scaleModeStr?.let { runCatching { IdCardScaleMode.valueOf(it) }.getOrNull() }
                ?: IdCardScaleMode.ACTUAL_SIZE

            return IdCardSessionDraft(
                frontImagePath = frontPath,
                backImagePath = backPath,
                config = IdCardComposeConfig(
                    layoutMode = layoutMode,
                    scaleMode = scaleMode,
                    showCutBorder = showCutBorder,
                    addWatermark = addWatermark,
                    watermarkText = watermarkText ?: WatermarkHelper.WATERMARK_TEXT
                )
            )
        }

        fun fromBundle(bundle: Bundle?): IdCardSessionDraft? {
            if (bundle == null) return null
            val layoutModeStr = bundle.getString(KEY_LAYOUT_MODE) ?: return null
            val scaleModeStr = bundle.getString(KEY_SCALE_MODE) ?: return null
            return fromValues(
                frontPath = bundle.getString(KEY_FRONT_PATH),
                backPath = bundle.getString(KEY_BACK_PATH),
                layoutModeStr = layoutModeStr,
                scaleModeStr = scaleModeStr,
                showCutBorder = bundle.getBoolean(KEY_SHOW_CUT_BORDER, true),
                addWatermark = bundle.getBoolean(KEY_ADD_WATERMARK, true),
                watermarkText = bundle.getString(KEY_WATERMARK_TEXT)
            )
        }
    }
}
