package com.tscanner.app.ui.editor.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tscanner.app.R
import com.tscanner.app.data.model.PostScanSessionDraft
import com.tscanner.app.data.repository.PostScanSessionRepository
import com.tscanner.app.ui.editor.model.DocumentFilterType
import com.tscanner.app.ui.editor.model.NormalizedCropRect
import com.tscanner.app.ui.editor.model.PageEditState
import com.tscanner.app.ui.editor.model.PageGeometry
import com.tscanner.app.utils.FileUtils
import com.tscanner.app.utils.ImageProcessingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

enum class ActiveEditorTool {
    FILTER,
    ENHANCE,
    CROP_ROTATE,
    CLEAN
}

enum class DraftStatus {
    SAVED,
    SAVING,
    ERROR
}

enum class ExportErrorCode {
    NO_PAGES,
    PROCESS_PAGE_FAILED,
    PAGE_COUNT_MISMATCH,
    UNKNOWN
}

sealed class ExportResult {
    data class Success(val exportId: String, val sessionRevision: Long, val pagePaths: List<String>) : ExportResult()
    data class Failure(
        val pageIndex: Int,
        val stage: String,
        val message: String,
        val canRetry: Boolean,
        val errorCode: ExportErrorCode = ExportErrorCode.UNKNOWN,
        val errorArgs: List<String> = emptyList()
    ) : ExportResult()
    object Cancelled : ExportResult()
}

data class RenderRequestToken(
    val sessionId: String,
    val pageIndex: Int,
    val pagePath: String,
    val revision: Long,
    val requestId: Long,
    val stateSnapshot: PageEditState
)

data class EditorUiState(
    val sessionId: String = "",
    val documentTitle: String = "",
    val currentPageIndex: Int = 0,
    val totalPages: Int = 0,
    val activeTool: ActiveEditorTool? = null,
    val isLoading: Boolean = false,
    val isRenderingPreview: Boolean = false,
    val isShowingInitial: Boolean = false,
    val draftStatus: DraftStatus = DraftStatus.SAVED,
    val currentPageState: PageEditState? = null
)

class PostScanEditorViewModel(application: Application) : AndroidViewModel(application) {

    private val sessionRepo = PostScanSessionRepository.getInstance(application)

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val _displayBitmap = MutableStateFlow<Bitmap?>(null)
    val displayBitmap: StateFlow<Bitmap?> = _displayBitmap.asStateFlow()

    private val _thumbnailPaths = MutableStateFlow<List<String>>(emptyList())
    val thumbnailPaths: StateFlow<List<String>> = _thumbnailPaths.asStateFlow()

    private var currentDraft: PostScanSessionDraft? = null
    private val pageStates = mutableListOf<PageEditState>()
    private var currentRevision: Long = 1L
    private var initialTitle: String = ""
    private var saveJob: Job? = null

    // Base bitmap cache
    private var activeBaseBitmap: Bitmap? = null
    private var activeBaseBitmapPath: String? = null
    private var lastRenderedPreviewBitmap: Bitmap? = null

    // Job and token controls (R02)
    private val requestCounter = AtomicLong(0L)
    @Volatile
    private var currentToken: RenderRequestToken? = null
    private var activeLoadJob: Job? = null
    private var activeRenderJob: Job? = null
    private var roiRenderJob: Job? = null

    fun hasAnyModifiedPages(): Boolean = pageStates.any { it.isModified }
    fun isTitleModified(): Boolean = _uiState.value.documentTitle != initialTitle
    fun isDirty(): Boolean = hasAnyModifiedPages() || isTitleModified()

    suspend fun flushPendingChanges(): Boolean {
        saveJob?.join()
        val sid = _uiState.value.sessionId
        return if (sid.isNotEmpty()) {
            sessionRepo.flush(sid, currentRevision)
        } else {
            true
        }
    }

    fun initialize(sessionId: String, pagePaths: List<String>, title: String?) {
        if (currentDraft != null && currentDraft?.sessionId == sessionId) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            val existing = sessionRepo.loadDraft(sessionId)
            val draft = if (existing != null && existing.pageStates.isNotEmpty()) {
                existing
            } else {
                sessionRepo.initializeSession(sessionId, pagePaths, title)
            }

            if (draft == null) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    draftStatus = DraftStatus.ERROR
                )
                return@launch
            }

            currentDraft = draft
            currentRevision = draft.revision
            pageStates.clear()
            pageStates.addAll(draft.pageStates)

            val defaultTitle = getApplication<Application>().getString(R.string.editor_default_doc_title)
            val resolvedTitle = draft.documentTitle.ifEmpty { defaultTitle }
            initialTitle = resolvedTitle
            _thumbnailPaths.value = pageStates.map { it.inputImagePath }
            _uiState.value = _uiState.value.copy(
                sessionId = draft.sessionId,
                documentTitle = resolvedTitle,
                currentPageIndex = 0,
                totalPages = pageStates.size,
                currentPageState = pageStates.firstOrNull(),
                isLoading = false,
                draftStatus = DraftStatus.SAVED
            )

            loadCurrentPage()
        }
    }

    fun selectPage(index: Int) {
        if (index == _uiState.value.currentPageIndex || index !in pageStates.indices) return
        _uiState.value = _uiState.value.copy(
            currentPageIndex = index,
            currentPageState = pageStates[index]
        )
        loadCurrentPage()
    }

    private fun loadCurrentPage() {
        val index = _uiState.value.currentPageIndex
        if (index !in pageStates.indices) return
        val state = pageStates[index]
        val reqId = requestCounter.incrementAndGet()
        val token = RenderRequestToken(
            sessionId = _uiState.value.sessionId,
            pageIndex = index,
            pagePath = state.inputImagePath,
            revision = currentRevision,
            requestId = reqId,
            stateSnapshot = state
        )
        currentToken = token

        activeLoadJob?.cancel()
        activeRenderJob?.cancel()
        roiRenderJob?.cancel()

        activeLoadJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRenderingPreview = true)

            if (activeBaseBitmap == null || activeBaseBitmapPath != token.pagePath) {
                val decoded = withContext(Dispatchers.IO) {
                    ImageProcessingEngine.loadNormalizedPreviewBitmap(token.pagePath)
                }
                if (currentToken != token || !isActive) return@launch
                activeBaseBitmap = decoded
                activeBaseBitmapPath = token.pagePath
            }

            renderPreviewInternal(token, activeBaseBitmap)
        }
    }

    fun selectTool(tool: ActiveEditorTool) {
        val current = _uiState.value.activeTool
        _uiState.value = _uiState.value.copy(
            activeTool = if (current == tool) null else tool
        )
    }

    fun closeCustomizationPanel() {
        _uiState.value = _uiState.value.copy(activeTool = null)
    }

    fun setDocumentTitle(title: String) {
        val defaultTitle = getApplication<Application>().getString(R.string.editor_default_doc_title)
        val sanitized = title.trim().ifEmpty { defaultTitle }
        _uiState.value = _uiState.value.copy(documentTitle = sanitized)
        currentDraft?.let { d ->
            currentRevision++
            val updated = d.copy(documentTitle = sanitized, revision = currentRevision)
            currentDraft = updated
            persistDraftSnapshot()
        }
    }

    fun updateFilter(filterType: DocumentFilterType) {
        val index = _uiState.value.currentPageIndex
        if (index !in pageStates.indices) return
        val updated = pageStates[index].copy(filterType = filterType)
        applyPageStateChange(index, updated)
    }

    fun updateSharpness(intensity: Int) {
        val index = _uiState.value.currentPageIndex
        if (index !in pageStates.indices) return
        val updated = pageStates[index].copy(sharpnessIntensity = intensity.coerceIn(0, 100))
        applyPageStateChange(index, updated)
    }

    fun updateShadow(intensity: Int) {
        val index = _uiState.value.currentPageIndex
        if (index !in pageStates.indices) return
        val updated = pageStates[index].copy(shadowRemovalIntensity = intensity.coerceIn(0, 100))
        applyPageStateChange(index, updated)
    }

    fun updateBackgroundLighten(intensity: Int) {
        val index = _uiState.value.currentPageIndex
        if (index !in pageStates.indices) return
        val updated = pageStates[index].copy(backgroundLightenIntensity = intensity.coerceIn(0, 100))
        applyPageStateChange(index, updated)
    }

    fun updateLighten(intensity: Int) = updateBackgroundLighten(intensity)

    /**
     * R01: Xoay trang chỉ cập nhật góc xoay rotationDegrees.
     * TUYỆT ĐỐI KHÔNG làm biến đổi hay xoay cropRect nguồn.
     */
    fun rotatePage(degrees: Int) {
        val index = _uiState.value.currentPageIndex
        if (index !in pageStates.indices) return
        val current = pageStates[index]
        val newRot = PageGeometry.normalizeRotation(current.rotationDegrees + degrees)
        val updated = current.copy(rotationDegrees = newRot)
        applyPageStateChange(index, updated)
    }

    fun updateCrop(cropRect: NormalizedCropRect) {
        val index = _uiState.value.currentPageIndex
        if (index !in pageStates.indices) return
        val updated = pageStates[index].copy(cropRect = cropRect.safeNormalized())
        applyPageStateChange(index, updated)
    }

    fun resetCrop() {
        updateCrop(NormalizedCropRect())
    }

    fun undoCurrentPage() {
        val index = _uiState.value.currentPageIndex
        if (index !in pageStates.indices) return
        val reset = pageStates[index].resetToOriginal()
        applyPageStateChange(index, reset)
    }

    fun applyToAllPages() {
        val currentIndex = _uiState.value.currentPageIndex
        if (currentIndex !in pageStates.indices) return
        val sourceState = pageStates[currentIndex]

        currentRevision++
        for (i in pageStates.indices) {
            if (i != currentIndex) {
                pageStates[i] = pageStates[i].applyGlobalStyleFrom(sourceState)
            }
        }
        persistDraftSnapshot()
        schedulePreviewRender()
    }

    fun setShowingInitialComparison(showInitial: Boolean) {
        _uiState.value = _uiState.value.copy(isShowingInitial = showInitial)
        if (showInitial) {
            _displayBitmap.value = activeBaseBitmap
        } else {
            _displayBitmap.value = lastRenderedPreviewBitmap ?: activeBaseBitmap
        }
    }

    private fun applyPageStateChange(index: Int, updated: PageEditState) {
        currentRevision++
        pageStates[index] = updated
        _uiState.value = _uiState.value.copy(currentPageState = updated)
        persistDraftSnapshot()
        schedulePreviewRender()
    }

    private fun schedulePreviewRender() {
        val index = _uiState.value.currentPageIndex
        if (index !in pageStates.indices) return
        val state = pageStates[index]
        val reqId = requestCounter.incrementAndGet()
        val token = RenderRequestToken(
            sessionId = _uiState.value.sessionId,
            pageIndex = index,
            pagePath = state.inputImagePath,
            revision = currentRevision,
            requestId = reqId,
            stateSnapshot = state
        )
        currentToken = token

        activeRenderJob?.cancel()
        activeRenderJob = viewModelScope.launch {
            delay(35) // Debounce khi người dùng kéo slider liên tục
            val baseBmp = activeBaseBitmap
            if (baseBmp != null) {
                renderPreviewInternal(token, baseBmp)
            } else {
                loadCurrentPage()
            }
        }
    }

    private suspend fun renderPreviewInternal(token: RenderRequestToken, baseBmp: Bitmap?) {
        if (baseBmp == null) return
        if (currentToken != token) return

        _uiState.value = _uiState.value.copy(isRenderingPreview = true)

        val rendered = withContext(Dispatchers.Default) {
            if (currentToken != token) return@withContext null
            ImageProcessingEngine.renderPagePreview(baseBmp, token.stateSnapshot)
        }

        if (currentToken == token) {
            if (rendered != null) {
                lastRenderedPreviewBitmap = rendered
                if (!_uiState.value.isShowingInitial) {
                    _displayBitmap.value = rendered
                }
            }
            _uiState.value = _uiState.value.copy(isRenderingPreview = false)
        }
    }

    private fun persistDraftSnapshot() {
        val sid = _uiState.value.sessionId
        if (sid.isEmpty()) return
        val draft = currentDraft?.copy(
            pageStates = ArrayList(pageStates),
            revision = currentRevision,
            updatedAt = System.currentTimeMillis()
        ) ?: return

        currentDraft = draft
        _uiState.value = _uiState.value.copy(draftStatus = DraftStatus.SAVING)

        saveJob = viewModelScope.launch {
            val success = sessionRepo.saveDraft(draft)
            if (success) {
                _uiState.value = _uiState.value.copy(draftStatus = DraftStatus.SAVED)
            } else {
                _uiState.value = _uiState.value.copy(draftStatus = DraftStatus.ERROR)
            }
        }
    }

    fun onViewportChanged(viewportRect: RectF, zoomScale: Float) {
        if (zoomScale <= 1.2f) return
        val index = _uiState.value.currentPageIndex
        if (index !in pageStates.indices) return
        val state = pageStates[index]

        roiRenderJob?.cancel()
        roiRenderJob = viewModelScope.launch {
            delay(150)
            ImageProcessingEngine.renderDetailRoi(
                sourceImagePath = state.inputImagePath,
                state = state,
                viewportRectNorm = viewportRect,
                targetWidth = 600,
                targetHeight = 600
            )
        }
    }

    /**
     * R03: Kết xuất tất cả các trang dạng nguyên tử (Atomic Export).
     * Tuyệt đối không fallback âm thầm về inputImagePath khi có lỗi.
     */
    suspend fun exportFullResolutionPages(
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): ExportResult = withContext(Dispatchers.IO) {
        val sid = _uiState.value.sessionId
        if (sid.isEmpty() || pageStates.isEmpty()) {
            return@withContext ExportResult.Failure(
                pageIndex = -1,
                stage = "init",
                message = "No document pages to export",
                canRetry = false,
                errorCode = ExportErrorCode.NO_PAGES
            )
        }

        val flushed = flushPendingChanges()
        if (!flushed) {
            return@withContext ExportResult.Failure(
                pageIndex = -1,
                stage = "flush_draft",
                message = "Failed to flush draft revision $currentRevision to storage",
                canRetry = true,
                errorCode = ExportErrorCode.PROCESS_PAGE_FAILED
            )
        }

        val exportId = "export_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
        val exportDir = File(sessionRepo.getSessionDir(sid), exportId)
        exportDir.mkdirs()

        val pagesSnapshot = ArrayList(pageStates)
        val resultPaths = mutableListOf<String>()

        for (i in pagesSnapshot.indices) {
            onProgress(i + 1, pagesSnapshot.size)
            val state = pagesSnapshot[i]
            val outFile = File(exportDir, "page_${i + 1}.jpg")
            val success = ImageProcessingEngine.processAndSaveFullResolution(state.inputImagePath, state, outFile)

            if (!success || !outFile.exists() || outFile.length() == 0L) {
                FileUtils.deleteDir(exportDir)
                return@withContext ExportResult.Failure(
                    pageIndex = i + 1,
                    stage = "process_image",
                    message = "Failed to process page ${i + 1}",
                    canRetry = true,
                    errorCode = ExportErrorCode.PROCESS_PAGE_FAILED,
                    errorArgs = listOf((i + 1).toString())
                )
            }
            resultPaths.add(outFile.absolutePath)
        }

        if (resultPaths.size != pagesSnapshot.size) {
            FileUtils.deleteDir(exportDir)
            return@withContext ExportResult.Failure(
                pageIndex = -1,
                stage = "verification",
                message = "Exported page count mismatch (${resultPaths.size}/${pagesSnapshot.size})",
                canRetry = true,
                errorCode = ExportErrorCode.PAGE_COUNT_MISMATCH,
                errorArgs = listOf(resultPaths.size.toString(), pagesSnapshot.size.toString())
            )
        }

        ExportResult.Success(exportId, currentRevision, resultPaths)
    }

    suspend fun discardSession(): Boolean {
        saveJob?.cancel()
        val sid = _uiState.value.sessionId
        return if (sid.isNotEmpty()) {
            sessionRepo.discardSession(sid)
        } else false
    }

    override fun onCleared() {
        super.onCleared()
        activeLoadJob?.cancel()
        activeRenderJob?.cancel()
        roiRenderJob?.cancel()
        activeBaseBitmap = null
        lastRenderedPreviewBitmap = null
        _displayBitmap.value = null
    }
}
