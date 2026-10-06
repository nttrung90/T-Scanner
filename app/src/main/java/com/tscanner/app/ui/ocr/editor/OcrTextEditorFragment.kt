package com.tscanner.app.ui.ocr.editor

import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.style.AbsoluteSizeSpan
import android.text.style.AlignmentSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tscanner.app.R
import com.tscanner.app.databinding.FragmentOcrTextEditorBinding
import com.tscanner.app.ocr.edit.OcrEditCommand
import com.tscanner.app.ocr.model.*
import com.tscanner.app.ui.ocr.reader.OcrReaderViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Fragment providing full text editing capabilities for the active document page.
 * Supports bold, italic, font size, paragraph alignment, undo, redo, and safe IME typing.
 *
 * Complies with PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md (Packages S12, S13).
 */
class OcrTextEditorFragment : Fragment() {

    private var _binding: FragmentOcrTextEditorBinding? = null
    private val binding get() = _binding!!

    private val viewModel: OcrReaderViewModel by activityViewModels()

    private var isInternalTextUpdate = false
    private var debounceCommitJob: Job? = null
    private var currentObservedPageIndex: Int = -1
    private var isUserTextDirty: Boolean = false
    private var lastAppliedStyleSignature: String? = null

    private val fontSizes = listOf(11f, 12f, 14f, 16f, 18f)
    private var currentFontSizeIndex = 1 // default 12pt
    private var currentAlignment = OcrTextAlignment.LEFT

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOcrTextEditorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupActions()
        setupFormattingTools()
        observeState()
    }

    private fun setupActions() {
        binding.btnUndo.setOnClickListener {
            viewModel.undo()
        }

        binding.btnRedo.setOnClickListener {
            viewModel.redo()
        }

        binding.etEditorText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (isInternalTextUpdate) return
                isUserTextDirty = true

                val text = s?.toString() ?: ""
                val editingPageIndex = currentObservedPageIndex

                // Debounce commit to avoid generating commands on intermediate IME composing keys
                debounceCommitJob?.cancel()
                debounceCommitJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(300)
                    // Check if IME composing is still active
                    val editable = binding.etEditorText.text
                    val isComposing = editable != null && BaseInputConnection.getComposingSpanStart(editable) >= 0

                    if (!isComposing && editingPageIndex > 0) {
                        viewModel.updatePageText(editingPageIndex, text)
                        isUserTextDirty = false
                    }
                }
            }

            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupFormattingTools() {
        binding.btnFormatBold.setOnClickListener {
            toggleSelectionBold()
        }

        binding.btnFormatItalic.setOnClickListener {
            toggleSelectionItalic()
        }

        binding.btnFormatFontSize.setOnClickListener {
            cycleFontSize()
        }

        binding.btnFormatAlign.setOnClickListener {
            cycleAlignment()
        }
    }

    private fun toggleSelectionBold() {
        applyStyleToSelection(toggleBold = true)
    }

    private fun toggleSelectionItalic() {
        applyStyleToSelection(toggleItalic = true)
    }

    private fun cycleFontSize() {
        currentFontSizeIndex = (currentFontSizeIndex + 1) % fontSizes.size
        val newSize = fontSizes[currentFontSizeIndex]
        binding.btnFormatFontSize.text = "${newSize.toInt()}pt"
        applyStyleToSelection(targetFontSize = newSize)
    }

    private fun cycleAlignment() {
        currentAlignment = when (currentAlignment) {
            OcrTextAlignment.LEFT -> OcrTextAlignment.CENTER
            OcrTextAlignment.CENTER -> OcrTextAlignment.RIGHT
            OcrTextAlignment.RIGHT -> OcrTextAlignment.LEFT
            OcrTextAlignment.JUSTIFY -> OcrTextAlignment.LEFT
        }
        binding.btnFormatAlign.text = when (currentAlignment) {
            OcrTextAlignment.LEFT -> "≡"
            OcrTextAlignment.CENTER -> "≍"
            OcrTextAlignment.RIGHT -> "≣"
            OcrTextAlignment.JUSTIFY -> "≡"
        }
        applyAlignment(currentAlignment)
    }

    private fun applyStyleToSelection(
        toggleBold: Boolean = false,
        toggleItalic: Boolean = false,
        targetFontSize: Float? = null
    ) {
        val doc = viewModel.document.value ?: return
        val pageIdx = viewModel.currentPageIndex.value
        val curPage = doc.pages.getOrNull(pageIdx - 1) ?: return

        val selStart = binding.etEditorText.selectionStart
        val selEnd = binding.etEditorText.selectionEnd
        val text = binding.etEditorText.text?.toString() ?: curPage.resolvedText
        if (text.isEmpty()) return

        val s = selStart.coerceIn(0, text.length)
        val e = selEnd.coerceIn(s, text.length)
        val oldParas = curPage.editedContent?.paragraphs?.ifEmpty { null }
            ?: run {
                val lines = text.split("\n")
                lines.mapIndexed { idx, line ->
                    OcrParagraph("p_${pageIdx}_$idx", text = line, runs = listOf(OcrTextRun(line)))
                }
            }

        var globalOffset = 0
        val newParas = oldParas.map { para ->
            val paraStart = globalOffset
            val paraLen = para.text.length
            val paraEnd = paraStart + paraLen
            globalOffset = paraEnd + 1 // +1 for the newline separator

            // Determine if this paragraph intersects selection
            val intersects = if (s == e) {
                s in paraStart..paraEnd
            } else {
                maxOf(s, paraStart) < minOf(e, paraEnd)
            }

            if (!intersects) {
                para
            } else if (s == e) {
                // Cursor only: format entire paragraph
                val updatedRuns = para.runs.map { run ->
                    val newBold = if (toggleBold) !run.isBold else run.isBold
                    val newItalic = if (toggleItalic) !run.isItalic else run.isItalic
                    val newSize = targetFontSize ?: run.fontSizePt
                    run.copy(isBold = newBold, isItalic = newItalic, fontSizePt = newSize)
                }
                para.copy(runs = updatedRuns)
            } else {
                // Range selection: split runs according to [s, e]
                var runOffset = paraStart
                val splitRuns = mutableListOf<OcrTextRun>()

                for (run in para.runs) {
                    val rStart = runOffset
                    val rEnd = rStart + run.text.length
                    runOffset = rEnd

                    val overlapStart = maxOf(rStart, s)
                    val overlapEnd = minOf(rEnd, e)

                    if (overlapStart >= overlapEnd) {
                        splitRuns.add(run)
                    } else {
                        val preLen = overlapStart - rStart
                        val targetLen = overlapEnd - overlapStart

                        if (preLen > 0) {
                            splitRuns.add(run.copy(text = run.text.substring(0, preLen)))
                        }

                        val styledText = run.text.substring(preLen, preLen + targetLen)
                        val newBold = if (toggleBold) !run.isBold else run.isBold
                        val newItalic = if (toggleItalic) !run.isItalic else run.isItalic
                        val newSize = targetFontSize ?: run.fontSizePt
                        splitRuns.add(
                            run.copy(
                                text = styledText,
                                isBold = newBold,
                                isItalic = newItalic,
                                fontSizePt = newSize
                            )
                        )

                        val postStart = preLen + targetLen
                        if (postStart < run.text.length) {
                            splitRuns.add(run.copy(text = run.text.substring(postStart)))
                        }
                    }
                }
                para.copy(runs = splitRuns)
            }
        }

        viewModel.executeCommand(
            OcrEditCommand.ReplaceParagraphs(
                pageIndex = pageIdx,
                oldParagraphs = oldParas,
                newParagraphs = newParas
            )
        )
    }

    private fun applyAlignment(newAlignment: OcrTextAlignment) {
        val doc = viewModel.document.value ?: return
        val pageIdx = viewModel.currentPageIndex.value
        val curPage = doc.pages.getOrNull(pageIdx - 1) ?: return

        val oldParas = curPage.editedContent?.paragraphs?.ifEmpty { null }
            ?: listOf(OcrParagraph("p_${pageIdx}_0", text = curPage.resolvedText, runs = listOf(OcrTextRun(curPage.resolvedText))))

        val newParas = oldParas.map { it.copy(alignment = newAlignment) }

        viewModel.executeCommand(
            OcrEditCommand.ReplaceParagraphs(
                pageIndex = pageIdx,
                oldParagraphs = oldParas,
                newParagraphs = newParas
            )
        )
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Observe current document and page index to update editor content
                launch {
                    kotlinx.coroutines.flow.combine(viewModel.document, viewModel.currentPageIndex) { doc, pageIdx ->
                        doc to pageIdx
                    }.collect { (doc, pageIdx) ->
                        if (doc != null) {
                            if (currentObservedPageIndex != pageIdx) {
                                if (currentObservedPageIndex > 0 && isUserTextDirty) {
                                    flushPendingTextEdit()
                                }
                                currentObservedPageIndex = pageIdx
                            }

                            val curPage = doc.pages.getOrNull(pageIdx - 1)
                            val resolved = curPage?.resolvedText ?: ""
                            val currentInput = binding.etEditorText.text?.toString() ?: ""
                            val styleSig = "${curPage?.editedContent?.paragraphs?.hashCode()}_${doc.revision}"

                            if (currentInput != resolved || lastAppliedStyleSignature != styleSig) {
                                lastAppliedStyleSignature = styleSig
                                isInternalTextUpdate = true
                                val selStart = binding.etEditorText.selectionStart
                                val selEnd = binding.etEditorText.selectionEnd

                                // Build styled spannable representation
                                val spannable = buildSpannable(curPage)
                                binding.etEditorText.setText(spannable)

                                val safeSel = selStart.coerceIn(0, resolved.length)
                                val safeSelEnd = selEnd.coerceIn(safeSel, resolved.length)
                                try {
                                    binding.etEditorText.setSelection(safeSel, safeSelEnd)
                                } catch (_: Exception) {}
                                isInternalTextUpdate = false
                            }
                        }
                    }
                }

                // Observe undo capability
                launch {
                    viewModel.canUndo.collect { canUndo ->
                        binding.btnUndo.isEnabled = canUndo
                        binding.btnUndo.alpha = if (canUndo) 1.0f else 0.4f
                    }
                }

                // Observe redo capability
                launch {
                    viewModel.canRedo.collect { canRedo ->
                        binding.btnRedo.isEnabled = canRedo
                        binding.btnRedo.alpha = if (canRedo) 1.0f else 0.4f
                    }
                }

                // Observe dirty / edited state
                launch {
                    viewModel.isDirty.collect { isDirty ->
                        binding.tvEditorStatus.text = if (isDirty) {
                            getString(R.string.ocr_editor_status_edited)
                        } else {
                            getString(R.string.ocr_editor_status_saved)
                        }
                    }
                }
            }
        }
    }

    private fun buildSpannable(page: OcrPage?): SpannableStringBuilder {
        val ssb = SpannableStringBuilder()
        if (page == null) return ssb

        val paragraphs = page.editedContent?.paragraphs
        if (paragraphs.isNullOrEmpty()) {
            ssb.append(page.resolvedText)
            return ssb
        }

        val density = resources.displayMetrics.density

        for ((pIdx, para) in paragraphs.withIndex()) {
            if (pIdx > 0) ssb.append("\n")
            val paraStart = ssb.length

            if (para.runs.isEmpty()) {
                ssb.append(para.text)
            } else {
                for (run in para.runs) {
                    val runStart = ssb.length
                    ssb.append(run.text)
                    val runEnd = ssb.length

                    val style = when {
                        run.isBold && run.isItalic -> Typeface.BOLD_ITALIC
                        run.isBold -> Typeface.BOLD
                        run.isItalic -> Typeface.ITALIC
                        else -> Typeface.NORMAL
                    }
                    if (style != Typeface.NORMAL) {
                        ssb.setSpan(StyleSpan(style), runStart, runEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }

                    if (run.fontSizePt > 0f) {
                        val px = (run.fontSizePt * density).toInt()
                        ssb.setSpan(AbsoluteSizeSpan(px), runStart, runEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }

            val paraEnd = ssb.length
            val align = when (para.alignment) {
                OcrTextAlignment.CENTER -> Layout.Alignment.ALIGN_CENTER
                OcrTextAlignment.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
                else -> Layout.Alignment.ALIGN_NORMAL
            }
            ssb.setSpan(AlignmentSpan.Standard(align), paraStart, paraEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        return ssb
    }

    fun flushPendingTextEdit() {
        debounceCommitJob?.cancel()
        if (!isInternalTextUpdate && _binding != null && isUserTextDirty && currentObservedPageIndex > 0) {
            val text = binding.etEditorText.text?.toString() ?: ""
            viewModel.updatePageText(currentObservedPageIndex, text)
            isUserTextDirty = false
        }
    }

    override fun onPause() {
        super.onPause()
        flushPendingTextEdit()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        debounceCommitJob?.cancel()
        _binding = null
    }
}
