package com.tscanner.app.ui.ocr.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.tscanner.app.ocr.data.OcrDocumentRepository
import com.tscanner.app.ocr.data.RepositoryResult
import com.tscanner.app.ocr.model.OcrDocument
import com.tscanner.app.ocr.model.OcrPage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.InputStream
import java.util.Collections

enum class OcrReaderTab {
    SCAN,
    TEXT,
    TABLE
}

enum class DocumentSaveState {
    SAVED,
    SAVING,
    DIRTY,
    ERROR
}

/**
 * Pure Kotlin LRU bounded cache with entry count, byte budget, and eviction listener.
 * Free of android.util dependencies for instant, reliable testing on JVM.
 */
class BoundedMemoryCache<K : Any, V : Any>(
    val maxEntries: Int,
    val maxSizeBytes: Long = Long.MAX_VALUE,
    private val sizeOf: ((V) -> Long)? = null,
    private val onEntryEvicted: ((key: K, value: V) -> Unit)? = null
) {
    constructor(
        maxEntries: Int,
        onEntryEvicted: ((key: K, value: V) -> Unit)?
    ) : this(maxEntries, Long.MAX_VALUE, null, onEntryEvicted)

    private val lock = Any()
    private val map = LinkedHashMap<K, V>(maxEntries.coerceAtLeast(16), 0.75f, true)
    private var currentByteSize: Long = 0L

    fun get(key: K): V? = synchronized(lock) {
        map[key]
    }

    fun put(key: K, value: V): V? = synchronized(lock) {
        val entrySize = sizeOf?.invoke(value) ?: 0L
        val old = map.put(key, value)
        if (old != null) {
            currentByteSize -= (sizeOf?.invoke(old) ?: 0L)
        }
        currentByteSize += entrySize

        trimToSize()
        old
    }

    private fun trimToSize() {
        val iterator = map.entries.iterator()
        while (iterator.hasNext()) {
            val sizeExceeded = maxEntries in 1..Int.MAX_VALUE && map.size > maxEntries
            val bytesExceeded = maxSizeBytes < Long.MAX_VALUE && currentByteSize > maxSizeBytes
            if (!sizeExceeded && !bytesExceeded) break
            val eldest = iterator.next()
            iterator.remove()
            currentByteSize -= (sizeOf?.invoke(eldest.value) ?: 0L)
            onEntryEvicted?.invoke(eldest.key, eldest.value)
        }
    }

    fun size(): Int = synchronized(lock) { map.size }

    fun currentBytes(): Long = synchronized(lock) { currentByteSize }

    fun containsValueIdentity(value: V): Boolean = synchronized(lock) {
        map.values.any { it === value }
    }

    fun evictAll() {
        val evicted = mutableListOf<Pair<K, V>>()
        synchronized(lock) {
            for ((k, v) in map) {
                evicted.add(k to v)
            }
            map.clear()
            currentByteSize = 0L
        }
        for ((k, v) in evicted) {
            onEntryEvicted?.invoke(k, v)
        }
    }
}

internal fun <K : Any, V : Any> publishThenCache(
    cache: BoundedMemoryCache<K, V>,
    key: K,
    value: V,
    publish: (V) -> Unit
) {
    publish(value)
    cache.put(key, value)
}

/**
 * ViewModel managing document reader state, bounded memory caching of page bitmaps,
 * and zoom/pan states across orientation changes.
 *
 * Complies with PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md (Package S09).
 */
class OcrReaderViewModel @JvmOverloads constructor(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
    private val repositoryOverride: OcrDocumentRepository? = null,
    private val externalScope: CoroutineScope? = null
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "OcrReaderViewModel"
        private const val KEY_DOC_ID = "saved_doc_id"
        private const val KEY_PAGE_INDEX = "saved_page_index"
        private const val KEY_TAB = "saved_reader_tab"
        private const val KEY_ZOOM = "saved_zoom_scale"
        private const val KEY_PAN_X = "saved_pan_x"
        private const val KEY_PAN_Y = "saved_pan_y"

        const val MAX_CACHED_BITMAPS = 3
        const val MAX_CACHE_BYTES = 24L * 1024L * 1024L // 24 MB memory ceiling

        internal fun calculateSampleSize(width: Int, height: Int, maxBytes: Long): Int {
            if (width <= 0 || height <= 0 || maxBytes <= 0L) return 1
            var sample = 1
            while (true) {
                val sampledWidth = (width.toLong() + sample - 1L) / sample
                val sampledHeight = (height.toLong() + sample - 1L) / sample
                if (sampledWidth * sampledHeight * 4L <= maxBytes || sample >= (1 shl 29)) return sample
                sample *= 2
            }
        }
    }

    private val scope: CoroutineScope
        get() = externalScope ?: viewModelScope

    private val repository: OcrDocumentRepository
        get() = repositoryOverride ?: OcrDocumentRepository.getInstance(getApplication())

    private val _document = MutableStateFlow<OcrDocument?>(null)
    val document: StateFlow<OcrDocument?> = _document.asStateFlow()

    private val saveMutex = Mutex()
    private var editGeneration: Long = 0L

    private var editHistory: com.tscanner.app.ocr.edit.OcrEditHistory? = null
    private var lastPersistedRevision: Long? = null
    private var documentLoadGeneration: Long = 0L
    private var imageLoadGeneration: Long = 0L

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    private val _isDirty = MutableStateFlow(false)
    val isDirty: StateFlow<Boolean> = _isDirty.asStateFlow()

    private val _hasUserEdits = MutableStateFlow(false)
    val hasUserEdits: StateFlow<Boolean> = _hasUserEdits.asStateFlow()

    private val _saveState = MutableStateFlow(DocumentSaveState.SAVED)
    val saveState: StateFlow<DocumentSaveState> = _saveState.asStateFlow()

    private var autosaveJob: Job? = null
    var autosaveDebounceMs: Long = 1000L

    private val _currentPageIndex = MutableStateFlow(savedStateHandle.get<Int>(KEY_PAGE_INDEX) ?: 1)
    val currentPageIndex: StateFlow<Int> = _currentPageIndex.asStateFlow()

    private val _currentTab = MutableStateFlow(
        try {
            OcrReaderTab.valueOf(savedStateHandle.get<String>(KEY_TAB) ?: OcrReaderTab.SCAN.name)
        } catch (_: Exception) {
            OcrReaderTab.SCAN
        }
    )
    val currentTab: StateFlow<OcrReaderTab> = _currentTab.asStateFlow()

    private val _zoomScale = MutableStateFlow(savedStateHandle.get<Float>(KEY_ZOOM) ?: 1.0f)
    val zoomScale: StateFlow<Float> = _zoomScale.asStateFlow()

    private val _panX = MutableStateFlow(savedStateHandle.get<Float>(KEY_PAN_X) ?: 0f)
    val panX: StateFlow<Float> = _panX.asStateFlow()

    private val _panY = MutableStateFlow(savedStateHandle.get<Float>(KEY_PAN_Y) ?: 0f)
    val panY: StateFlow<Float> = _panY.asStateFlow()

    private val _currentPageBitmap = MutableStateFlow<Bitmap?>(null)
    val currentPageBitmap: StateFlow<Bitmap?> = _currentPageBitmap.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isImageMissing = MutableStateFlow(false)
    val isImageMissing: StateFlow<Boolean> = _isImageMissing.asStateFlow()

    // Bounded LRU cache holding maximum 3 bitmaps or 24MB (e.g. prev, current, next)
    val bitmapCache = BoundedMemoryCache<String, Bitmap>(
        maxEntries = MAX_CACHED_BITMAPS,
        maxSizeBytes = MAX_CACHE_BYTES,
        sizeOf = { bmp -> calculateBitmapBytes(bmp) },
        onEntryEvicted = { _, evictedBmp ->
            if (evictedBmp != _currentPageBitmap.value && !evictedBmp.isRecycled) {
                try {
                    evictedBmp.recycle()
                } catch (_: Throwable) {}
            }
        }
    )

    private fun calculateBitmapBytes(bmp: Bitmap): Long {
        return try {
            bmp.allocationByteCount.toLong()
        } catch (_: Throwable) {
            try {
                (bmp.rowBytes * bmp.height).toLong()
            } catch (_: Throwable) {
                0L
            }
        }
    }

    private var imageLoadingJob: Job? = null

    /** Immutable reader state used to reject recognition results produced for an older draft. */
    internal data class RecognitionBase(
        val document: OcrDocument,
        val editGeneration: Long
    )

    internal sealed class RecognitionCommitResult {
        data class Success(val document: OcrDocument) : RecognitionCommitResult()
        data class Conflict(val message: String) : RecognitionCommitResult()
        data object Stale : RecognitionCommitResult()
        data class Failure(val message: String) : RecognitionCommitResult()
    }

    fun initialize(documentId: String, initialDoc: OcrDocument? = null) {
        savedStateHandle[KEY_DOC_ID] = documentId
        if (_document.value != null && _document.value?.id == documentId) {
            return
        }
        if (initialDoc != null) {
            documentLoadGeneration++
            _document.value = initialDoc
            lastPersistedRevision = if (initialDoc.revision > 1L) initialDoc.revision else null
            editGeneration = 0L
            editHistory = com.tscanner.app.ocr.edit.OcrEditHistory(initialDoc)
            _canUndo.value = editHistory?.canUndo ?: false
            _canRedo.value = editHistory?.canRedo ?: false
            _isDirty.value = editHistory?.isDirty ?: false
            _hasUserEdits.value = initialDoc.hasUserEdits
            loadPageImage(_currentPageIndex.value)
        } else {
            loadDocument(documentId)
        }
    }

    fun loadDocument(documentId: String) {
        val generation = ++documentLoadGeneration
        scope.launch {
            _isLoading.value = true
            when (val res = repository.loadDocument(documentId)) {
                is RepositoryResult.Success -> {
                    if (generation != documentLoadGeneration) return@launch
                    _document.value = res.value
                    lastPersistedRevision = res.value.revision
                    editGeneration = 0L
                    editHistory = com.tscanner.app.ocr.edit.OcrEditHistory(res.value)
                    _canUndo.value = editHistory?.canUndo ?: false
                    _canRedo.value = editHistory?.canRedo ?: false
                    _isDirty.value = editHistory?.isDirty ?: false
                    _hasUserEdits.value = res.value.hasUserEdits
                    val validIndex = _currentPageIndex.value.coerceIn(1, res.value.totalPages.coerceAtLeast(1))
                    _currentPageIndex.value = validIndex
                    loadPageImage(validIndex)
                }
                else -> {
                    if (generation == documentLoadGeneration) {
                        Log.w(TAG, "Failed to load document $documentId from repository: $res")
                    }
                }
            }
            if (generation == documentLoadGeneration) _isLoading.value = false
        }
    }

    /** Applies a newly committed recognition result to the live reader state. */
    fun applyCommittedRecognitionResult(
        committedDocument: OcrDocument,
        expectedCurrentRevision: Long,
        expectedDocument: OcrDocument? = null
    ): Boolean {
        val current = _document.value ?: return false
        if (current.id != committedDocument.id || current.revision != expectedCurrentRevision) return false
        if (expectedDocument != null && current != expectedDocument) return false
        if (committedDocument.revision <= current.revision) return false

        documentLoadGeneration++
        imageLoadGeneration++
        imageLoadingJob?.cancel()
        _document.value = committedDocument
        lastPersistedRevision = committedDocument.revision
        editGeneration = 0L
        unacknowledgedCommitCandidates = emptyList()
        editHistory = com.tscanner.app.ocr.edit.OcrEditHistory(committedDocument)
        _canUndo.value = false
        _canRedo.value = false
        _isDirty.value = false
        _hasUserEdits.value = committedDocument.hasUserEdits
        _saveState.value = DocumentSaveState.SAVED
        val validIndex = _currentPageIndex.value.coerceIn(1, committedDocument.totalPages.coerceAtLeast(1))
        _currentPageIndex.value = validIndex
        loadPageImage(validIndex)
        return true
    }

    /** Flushes the editor/autosave first and captures the exact state that recognition may replace. */
    internal suspend fun captureRecognitionBase(): RecognitionBase? {
        if (!flushPendingSaves()) return null
        return saveMutex.withLock {
            val current = _document.value ?: return@withLock null
            if (_isDirty.value || current.revision != (lastPersistedRevision ?: current.revision)) {
                return@withLock null
            }
            RecognitionBase(current, editGeneration)
        }
    }

    /**
     * Commits OCR output only if the captured reader state is still current. The repository write
     * shares the autosave mutex; if a synchronous edit arrives while storage is completing, restore
     * that edit with a revision CAS instead of publishing a replacement into a stale reader.
     */
    internal suspend fun commitRecognitionResult(
        base: RecognitionBase,
        recognitionDocument: OcrDocument
    ): RecognitionCommitResult = saveMutex.withLock {
        val current = _document.value
        if (current == null || current != base.document || current.id != base.document.id ||
            editGeneration != base.editGeneration || _isDirty.value ||
            recognitionDocument.id != base.document.id
        ) {
            return@withLock RecognitionCommitResult.Stale
        }

        withContext(NonCancellable) {
            val saveResult = try {
                repository.saveDocument(
                    recognitionDocument,
                    expectedRevision = base.document.revision
                )
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                return@withContext RecognitionCommitResult.Failure(t.message ?: "Recognition commit failed")
            }

            when (saveResult) {
                is RepositoryResult.Conflict -> RecognitionCommitResult.Conflict(saveResult.message)
                is RepositoryResult.Error -> RecognitionCommitResult.Failure(saveResult.message)
                is RepositoryResult.NotFound -> RecognitionCommitResult.Failure(saveResult.message)
                is RepositoryResult.Corrupted -> RecognitionCommitResult.Failure(saveResult.message)
                is RepositoryResult.Success -> {
                    val committed = saveResult.value
                    if (applyCommittedRecognitionResult(committed, base.document.revision, base.document)) {
                        RecognitionCommitResult.Success(committed)
                    } else {
                        // A user edit can be made from another callback while repository IO is in flight.
                        // Restore the live draft using CAS; a third-party writer then remains protected.
                        val liveDraft = _document.value
                        if (liveDraft != null && liveDraft.id == committed.id) {
                            val draftGeneration = editGeneration
                            when (val restore = repository.saveDocument(
                                liveDraft,
                                expectedRevision = committed.revision
                            )) {
                                is RepositoryResult.Success -> acknowledgeCommittedSave(restore.value, draftGeneration)
                                is RepositoryResult.Conflict -> {
                                    _isDirty.value = true
                                    _saveState.value = DocumentSaveState.ERROR
                                    return@withContext RecognitionCommitResult.Conflict(restore.message)
                                }
                                is RepositoryResult.Error -> {
                                    _isDirty.value = true
                                    _saveState.value = DocumentSaveState.ERROR
                                    return@withContext RecognitionCommitResult.Failure(restore.message)
                                }
                                is RepositoryResult.NotFound -> {
                                    _isDirty.value = true
                                    _saveState.value = DocumentSaveState.ERROR
                                    return@withContext RecognitionCommitResult.Failure(restore.message)
                                }
                                is RepositoryResult.Corrupted -> {
                                    _isDirty.value = true
                                    _saveState.value = DocumentSaveState.ERROR
                                    return@withContext RecognitionCommitResult.Failure(restore.message)
                                }
                            }
                        }
                        RecognitionCommitResult.Stale
                    }
                }
            }
        }
    }

    fun executeCommand(command: com.tscanner.app.ocr.edit.OcrEditCommand) {
        val history = editHistory ?: return
        editGeneration++
        _hasUserEdits.value = true
        val updated = history.execute(command)
        _document.value = updated
        _canUndo.value = history.canUndo
        _canRedo.value = history.canRedo
        _isDirty.value = history.isDirty
        scheduleAutosave()
    }

    private fun scheduleAutosave() {
        _saveState.value = DocumentSaveState.DIRTY
        autosaveJob?.cancel()
        autosaveJob = scope.launch(Dispatchers.IO) {
            delay(autosaveDebounceMs)
            saveDocumentInternal()
        }
    }

    suspend fun flushPendingSaves(): Boolean {
        autosaveJob?.cancel()
        return if (_isDirty.value) {
            withContext(Dispatchers.IO) {
                saveDocumentInternal()
            }
        } else {
            true
        }
    }

    /** Snapshots for commits that may have reached disk before their caller was cancelled. */
    private var unacknowledgedCommitCandidates: List<OcrDocument> = emptyList()

    private suspend fun saveDocumentInternal(): Boolean = saveMutex.withLock {
        val doc = _document.value ?: return false
        val generationAtStart = editGeneration
        _saveState.value = DocumentSaveState.SAVING

        val expectedRev = lastPersistedRevision ?: (if (doc.revision > 1L) doc.revision else null)
        val commitToken = java.util.UUID.randomUUID().toString()
        val attemptedDoc = doc.copy(lastCommitToken = commitToken)
        var inFlightAttempt: OcrDocument? = null
        try {
            inFlightAttempt = attemptedDoc
            val saveRes = repository.saveDocument(doc, expectedRevision = expectedRev, commitToken = commitToken)
            inFlightAttempt = null
            return when (saveRes) {
                is RepositoryResult.Success -> {
                    unacknowledgedCommitCandidates = emptyList()
                    val committedDoc = saveRes.value
                    acknowledgeCommittedSave(committedDoc, generationAtStart)
                    true
                }
                is RepositoryResult.Conflict -> {
                    if (unacknowledgedCommitCandidates.isNotEmpty()) {
                        Log.w(TAG, "Autosave revision conflict following unacknowledged cancellation on ${doc.id}: attempting reconcile...")
                        val diskDoc = repository.loadDocument(doc.id).getOrNull()
                        val matchingCommit = diskDoc?.takeIf { disk ->
                            disk.revision == saveRes.currentRevision &&
                                unacknowledgedCommitCandidates.any { attempted ->
                                    disk.lastCommitToken == attempted.lastCommitToken &&
                                        hasSamePersistedContent(disk, attempted)
                                }
                        }
                        if (matchingCommit != null) {
                            lastPersistedRevision = matchingCommit.revision
                            val retryToken = java.util.UUID.randomUUID().toString()
                            val retryAttempt = doc.copy(lastCommitToken = retryToken)
                            inFlightAttempt = retryAttempt
                            val retryRes = repository.saveDocument(
                                doc,
                                expectedRevision = matchingCommit.revision,
                                commitToken = retryToken
                            )
                            inFlightAttempt = null
                            if (retryRes is RepositoryResult.Success) {
                                unacknowledgedCommitCandidates = emptyList()
                                acknowledgeCommittedSave(retryRes.value, generationAtStart)
                                return@withLock true
                            }
                        }
                    }
                    Log.w(TAG, "Autosave revision conflict on document ${doc.id}: ${saveRes.message}")
                    _saveState.value = DocumentSaveState.ERROR
                    false
                }
                else -> {
                    Log.e(TAG, "Autosave failed on document ${doc.id}: $saveRes")
                    _saveState.value = DocumentSaveState.ERROR
                    false
                }
            }
        } catch (c: CancellationException) {
            inFlightAttempt?.let { attempt ->
                if (unacknowledgedCommitCandidates.none { it.lastCommitToken == attempt.lastCommitToken }) {
                    unacknowledgedCommitCandidates = unacknowledgedCommitCandidates + attempt
                }
            }
            throw c
        }
    }

    private fun hasSamePersistedContent(disk: OcrDocument, attempted: OcrDocument): Boolean =
        disk.copy(
            revision = attempted.revision,
            updatedAt = attempted.updatedAt,
            schemaVersion = attempted.schemaVersion
        ) == attempted

    private fun acknowledgeCommittedSave(committedDocument: OcrDocument, generationAtStart: Long) {
        lastPersistedRevision = committedDocument.revision
        val noNewerEdits = editGeneration == generationAtStart
        val acknowledgedDocument = editHistory?.acknowledgeCommit(committedDocument, noNewerEdits)
            ?: _document.value?.takeIf { it.id == committedDocument.id }?.copy(
                schemaVersion = committedDocument.schemaVersion,
                revision = committedDocument.revision,
                updatedAt = committedDocument.updatedAt,
                lastCommitToken = committedDocument.lastCommitToken
            )
        if (acknowledgedDocument != null) _document.value = acknowledgedDocument

        _isDirty.value = !noNewerEdits
        _saveState.value = if (noNewerEdits) DocumentSaveState.SAVED else DocumentSaveState.DIRTY
        if (!noNewerEdits) scheduleAutosave()
    }

    fun updatePageText(pageIndex: Int, newText: String) {
        val doc = _document.value ?: return
        val targetPage = doc.pages.getOrNull(pageIndex - 1) ?: return
        val oldText = targetPage.resolvedText
        if (oldText != newText) {
            executeCommand(
                com.tscanner.app.ocr.edit.OcrEditCommand.ReplacePageText(
                    pageIndex = pageIndex,
                    oldText = oldText,
                    newText = newText
                )
            )
        }
    }

    fun updateCurrentPageText(newText: String) {
        updatePageText(_currentPageIndex.value, newText)
    }

    fun updateTableCell(
        tableId: String,
        cellId: String,
        newText: String,
        newCellType: com.tscanner.app.ocr.model.OcrCellType
    ) {
        val doc = _document.value ?: return
        val pIdx = _currentPageIndex.value
        val curPage = doc.pages.getOrNull(pIdx - 1) ?: return
        val table = curPage.tables.find { it.tableId == tableId } ?: return
        val cell = table.cells.find { it.cellId == cellId } ?: return

        if (cell.editedText != newText || cell.cellType != newCellType) {
            executeCommand(
                com.tscanner.app.ocr.edit.OcrEditCommand.UpdateTableCell(
                    pageIndex = pIdx,
                    tableId = tableId,
                    cellId = cellId,
                    oldText = cell.editedText,
                    newText = newText,
                    oldCellType = cell.cellType,
                    newCellType = newCellType
                )
            )
        }
    }

    fun createManualTable(rowCount: Int = 3, colCount: Int = 3) {
        val doc = _document.value ?: return
        val pIdx = _currentPageIndex.value
        val curPage = doc.pages.getOrNull(pIdx - 1) ?: return

        val cells = mutableListOf<com.tscanner.app.ocr.model.OcrTableCell>()
        val tableId = "tbl_${pIdx}_manual"
        for (r in 0 until rowCount) {
            for (c in 0 until colCount) {
                val defaultHeader = if (r == 0) "Cột ${c + 1}" else ""
                cells.add(
                    com.tscanner.app.ocr.model.OcrTableCell(
                        cellId = "c_r${r}_c${c}",
                        rowIndex = r,
                        colIndex = c,
                        rowSpan = 1,
                        colSpan = 1,
                        rawText = defaultHeader,
                        editedText = defaultHeader,
                        cellType = com.tscanner.app.ocr.model.OcrCellType.TEXT
                    )
                )
            }
        }
        val newTable = com.tscanner.app.ocr.model.OcrTable(
            tableId = tableId,
            rowCount = rowCount,
            columnCount = colCount,
            cells = cells
        )
        executeCommand(com.tscanner.app.ocr.edit.OcrEditCommand.AddTable(pIdx, newTable))
    }

    fun addTableRow(tableId: String, rowIndex: Int) {
        val doc = _document.value ?: return
        val pIdx = _currentPageIndex.value
        val curPage = doc.pages.getOrNull(pIdx - 1) ?: return
        val table = curPage.tables.find { it.tableId == tableId } ?: return

        val newCells = (0 until table.columnCount).map { c ->
            com.tscanner.app.ocr.model.OcrTableCell(
                cellId = "c_r${rowIndex}_c${c}_${System.currentTimeMillis() % 10000}",
                rowIndex = rowIndex,
                colIndex = c,
                rowSpan = 1,
                colSpan = 1,
                rawText = "",
                editedText = "",
                cellType = com.tscanner.app.ocr.model.OcrCellType.TEXT
            )
        }

        executeCommand(
            com.tscanner.app.ocr.edit.OcrEditCommand.AddTableRow(
                pageIndex = pIdx,
                tableId = tableId,
                rowIndex = rowIndex,
                newCells = newCells
            )
        )
    }

    fun deleteTableRow(tableId: String, rowIndex: Int) {
        val doc = _document.value ?: return
        val pIdx = _currentPageIndex.value
        val curPage = doc.pages.getOrNull(pIdx - 1) ?: return
        val table = curPage.tables.find { it.tableId == tableId } ?: return
        if (table.rowCount <= 1) return

        val deletedCells = table.cells.filter { it.rowIndex == rowIndex }

        executeCommand(
            com.tscanner.app.ocr.edit.OcrEditCommand.DeleteTableRow(
                pageIndex = pIdx,
                tableId = tableId,
                rowIndex = rowIndex,
                deletedCells = deletedCells
            )
        )
    }

    fun addTableColumn(tableId: String, colIndex: Int) {
        val doc = _document.value ?: return
        val pIdx = _currentPageIndex.value
        val curPage = doc.pages.getOrNull(pIdx - 1) ?: return
        val table = curPage.tables.find { it.tableId == tableId } ?: return

        val newCells = (0 until table.rowCount).map { r ->
            com.tscanner.app.ocr.model.OcrTableCell(
                cellId = "c_r${r}_c${colIndex}_${System.currentTimeMillis() % 10000}",
                rowIndex = r,
                colIndex = colIndex,
                rowSpan = 1,
                colSpan = 1,
                rawText = "",
                editedText = "",
                cellType = com.tscanner.app.ocr.model.OcrCellType.TEXT
            )
        }

        executeCommand(
            com.tscanner.app.ocr.edit.OcrEditCommand.AddTableColumn(
                pageIndex = pIdx,
                tableId = tableId,
                colIndex = colIndex,
                newCells = newCells
            )
        )
    }

    fun deleteTableColumn(tableId: String, colIndex: Int) {
        val doc = _document.value ?: return
        val pIdx = _currentPageIndex.value
        val curPage = doc.pages.getOrNull(pIdx - 1) ?: return
        val table = curPage.tables.find { it.tableId == tableId } ?: return
        if (table.columnCount <= 1) return

        val deletedCells = table.cells.filter { it.colIndex == colIndex }

        executeCommand(
            com.tscanner.app.ocr.edit.OcrEditCommand.DeleteTableColumn(
                pageIndex = pIdx,
                tableId = tableId,
                colIndex = colIndex,
                deletedCells = deletedCells
            )
        )
    }

    fun mergeCells(tableId: String, cellIds: List<String>) {
        val doc = _document.value ?: return
        val pIdx = _currentPageIndex.value
        val curPage = doc.pages.getOrNull(pIdx - 1) ?: return
        val table = curPage.tables.find { it.tableId == tableId } ?: return

        val originalCells = table.cells.filter { cellIds.contains(it.cellId) }
        if (originalCells.size < 2) return

        // Preserve contents without silent loss
        val mergedText = originalCells.map { it.editedText.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

        val minR = originalCells.minOf { it.rowIndex }
        val maxR = originalCells.maxOf { it.rowIndex + it.rowSpan }
        val minC = originalCells.minOf { it.colIndex }
        val maxC = originalCells.maxOf { it.colIndex + it.colSpan }

        val mergedCell = com.tscanner.app.ocr.model.OcrTableCell(
            cellId = "c_r${minR}_c${minC}_merged",
            rowIndex = minR,
            colIndex = minC,
            rowSpan = maxR - minR,
            colSpan = maxC - minC,
            rawText = mergedText,
            editedText = mergedText,
            cellType = com.tscanner.app.ocr.model.OcrCellType.TEXT
        )

        executeCommand(
            com.tscanner.app.ocr.edit.OcrEditCommand.MergeCells(
                pageIndex = pIdx,
                tableId = tableId,
                originalCells = originalCells,
                mergedCell = mergedCell
            )
        )
    }

    fun splitCell(tableId: String, cellId: String) {
        val doc = _document.value ?: return
        val pIdx = _currentPageIndex.value
        val curPage = doc.pages.getOrNull(pIdx - 1) ?: return
        val table = curPage.tables.find { it.tableId == tableId } ?: return

        val mergedCell = table.cells.find { it.cellId == cellId } ?: return
        if (mergedCell.rowSpan <= 1 && mergedCell.colSpan <= 1) return

        val restoredCells = mutableListOf<com.tscanner.app.ocr.model.OcrTableCell>()
        for (r in mergedCell.rowIndex until (mergedCell.rowIndex + mergedCell.rowSpan)) {
            for (c in mergedCell.colIndex until (mergedCell.colIndex + mergedCell.colSpan)) {
                val isFirst = (r == mergedCell.rowIndex && c == mergedCell.colIndex)
                restoredCells.add(
                    com.tscanner.app.ocr.model.OcrTableCell(
                        cellId = "c_r${r}_c${c}",
                        rowIndex = r,
                        colIndex = c,
                        rowSpan = 1,
                        colSpan = 1,
                        rawText = if (isFirst) mergedCell.editedText else "",
                        editedText = if (isFirst) mergedCell.editedText else "",
                        cellType = mergedCell.cellType
                    )
                )
            }
        }

        executeCommand(
            com.tscanner.app.ocr.edit.OcrEditCommand.SplitCell(
                pageIndex = pIdx,
                tableId = tableId,
                mergedCell = mergedCell,
                restoredCells = restoredCells
            )
        )
    }

    fun undo(): Boolean {
        val history = editHistory ?: return false
        editGeneration++
        _hasUserEdits.value = true
        val doc = history.undo() ?: return false
        _document.value = doc
        _canUndo.value = history.canUndo
        _canRedo.value = history.canRedo
        _isDirty.value = history.isDirty
        scheduleAutosave()
        return true
    }

    fun redo(): Boolean {
        val history = editHistory ?: return false
        editGeneration++
        _hasUserEdits.value = true
        val doc = history.redo() ?: return false
        _document.value = doc
        _canUndo.value = history.canUndo
        _canRedo.value = history.canRedo
        _isDirty.value = history.isDirty
        scheduleAutosave()
        return true
    }

    fun markCommitted() {
        editHistory?.markCommitted()
        _isDirty.value = false
        _saveState.value = DocumentSaveState.SAVED
    }

    suspend fun prepareDocumentForExport(): com.tscanner.app.ocr.model.OcrDocument? {
        val flushed = flushPendingSaves()
        if (!flushed) {
            Log.w(TAG, "prepareDocumentForExport: flushPendingSaves returned false, exporting in-memory snapshot")
        }
        return createExportSnapshot()
    }

    fun createExportSnapshot(): com.tscanner.app.ocr.model.OcrDocument? {
        return editHistory?.createExportSnapshot() ?: _document.value
    }

    fun selectTab(tab: OcrReaderTab) {
        _currentTab.value = tab
        savedStateHandle[KEY_TAB] = tab.name
    }

    fun setPageIndex(index: Int) {
        val total = _document.value?.totalPages ?: 1
        val target = index.coerceIn(1, total)
        if (target != _currentPageIndex.value) {
            _currentPageIndex.value = target
            savedStateHandle[KEY_PAGE_INDEX] = target
            // Reset zoom & pan on page flip
            setZoomAndPan(1.0f, 0f, 0f)
            loadPageImage(target)
        }
    }

    fun nextPage() {
        setPageIndex(_currentPageIndex.value + 1)
    }

    fun previousPage() {
        setPageIndex(_currentPageIndex.value - 1)
    }

    fun setZoomAndPan(scale: Float, pX: Float, pY: Float) {
        _zoomScale.value = scale
        _panX.value = pX
        _panY.value = pY
        savedStateHandle[KEY_ZOOM] = scale
        savedStateHandle[KEY_PAN_X] = pX
        savedStateHandle[KEY_PAN_Y] = pY
    }

    private fun loadPageImage(pageIndex: Int) {
        val generation = ++imageLoadGeneration
        imageLoadingJob?.cancel()
        imageLoadingJob = scope.launch {
            val doc = _document.value ?: return@launch
            val page = doc.pages.getOrNull(pageIndex - 1)
            val uriStr = page?.imageInfo?.localUri

            if (uriStr.isNullOrBlank()) {
                _isImageMissing.value = true
                publishCurrentBitmap(null)
                return@launch
            }

            // Check in-memory LRU cache
            val cached = bitmapCache.get(uriStr)
            if (cached != null && !cached.isRecycled) {
                _isImageMissing.value = false
                publishCurrentBitmap(cached)
                return@launch
            }

            _isLoading.value = true
            val decoded = decodeBitmapSafely(uriStr)
            if (!isActive || generation != imageLoadGeneration ||
                _document.value?.id != doc.id || _currentPageIndex.value != pageIndex
            ) {
                if (decoded != null && !decoded.isRecycled) {
                    try { decoded.recycle() } catch (_: Throwable) {}
                }
                return@launch
            }
            if (decoded != null) {
                // The displayed reference owns the bitmap while it is inserted; an oversized
                // entry may be evicted synchronously by put() and must remain drawable.
                publishThenCache(bitmapCache, uriStr, decoded, ::publishCurrentBitmap)
                _isImageMissing.value = false
            } else {
                _isImageMissing.value = true
                publishCurrentBitmap(null)
            }
            _isLoading.value = false
        }
    }

    private fun publishCurrentBitmap(bitmap: Bitmap?) {
        val previous = _currentPageBitmap.value
        _currentPageBitmap.value = bitmap
        if (previous != null && previous !== bitmap &&
            !bitmapCache.containsValueIdentity(previous) && !previous.isRecycled
        ) {
            try {
                previous.recycle()
            } catch (_: Throwable) {}
        }
    }

    private suspend fun decodeBitmapSafely(uriString: String): Bitmap? = withContext(Dispatchers.IO) {
        var inputStream: InputStream? = null
        try {
            val uri = Uri.parse(uriString)
            inputStream = if (uri.scheme == "file" || uri.scheme == null) {
                val file = File(uri.path ?: uriString)
                if (!file.exists() || !file.canRead() || file.length() == 0L) return@withContext null
                file.inputStream()
            } else {
                getApplication<Application>().contentResolver.openInputStream(uri)
            } ?: return@withContext null

            // First decode bounds
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(inputStream, null, options)
            inputStream.close()

            if (options.outWidth <= 0 || options.outHeight <= 0) return@withContext null

            val inSample = calculateSampleSize(options.outWidth, options.outHeight, MAX_CACHE_BYTES)

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = inSample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            inputStream = if (uri.scheme == "file" || uri.scheme == null) {
                File(uri.path ?: uriString).inputStream()
            } else {
                getApplication<Application>().contentResolver.openInputStream(uri)
            }

            BitmapFactory.decodeStream(inputStream, null, decodeOpts)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "Error decoding bitmap from $uriString: ${t.message}")
            null
        } finally {
            try {
                inputStream?.close()
            } catch (_: Throwable) {}
        }
    }

    override fun onCleared() {
        super.onCleared()
        imageLoadingJob?.cancel()
        autosaveJob?.cancel()
        if (_isDirty.value) {
            try {
                runBlocking(Dispatchers.IO) {
                    saveDocumentInternal()
                }
            } catch (_: Throwable) {}
        }
        bitmapCache.evictAll()
        publishCurrentBitmap(null)
    }
}
