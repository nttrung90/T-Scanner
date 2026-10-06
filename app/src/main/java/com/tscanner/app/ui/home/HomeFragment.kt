package com.tscanner.app.ui.home

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tscanner.app.MainActivity
import com.tscanner.app.R
import com.tscanner.app.data.model.DocumentItem
import com.tscanner.app.data.repository.DocumentRepo
import com.tscanner.app.databinding.FragmentHomeBinding
import com.tscanner.app.ui.adapter.DocumentAdapter
import com.tscanner.app.ui.dialogs.ConfirmDeleteDialog
import com.tscanner.app.ui.dialogs.IdCardOptionsDialog
import com.tscanner.app.ui.dialogs.OcrLanguageSelectionDialog
import com.tscanner.app.ui.dialogs.QrResultDialog
import com.tscanner.app.ui.dialogs.RenameDocumentDialog
import com.tscanner.app.ui.dialogs.VipUpgradeDialog
import com.tscanner.app.ui.idcard.IdCardComposeActivity
import com.tscanner.app.ui.ocr.OcrResultActivity
import com.tscanner.app.ui.viewer.PdfViewerActivity
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.CloudBackupManager
import com.tscanner.app.utils.SyncCatalogResult
import com.tscanner.app.utils.SyncResultPresenter
import com.tscanner.app.utils.DriveAuthorizationAttempt
import com.tscanner.app.utils.FileUtils
import com.tscanner.app.utils.MultiPageOcrAggregator
import com.tscanner.app.utils.MultiPageOcrResult
import com.tscanner.app.utils.OcrResult
import com.tscanner.app.utils.PdfConverterHelper
import com.tscanner.app.utils.QrCodeResult
import com.tscanner.app.data.model.PostScanSessionDraft
import com.tscanner.app.data.repository.PostScanSessionRepository
import com.tscanner.app.ui.editor.PostScanEditorActivity
import com.tscanner.app.utils.QrScannerHelper
import com.tscanner.app.utils.TextRecognitionHelper
import com.tscanner.app.utils.DocumentImportHelper
import com.tscanner.app.utils.WatermarkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private var vipExpiryJob: Job? = null

    private lateinit var docAdapter: DocumentAdapter
    private lateinit var repo: DocumentRepo

    // Image picker for "Nhập ảnh"
    private lateinit var imagePickerLauncher: ActivityResultLauncher<String>
    // File picker for "Nhập tập tin"
    private lateinit var filePickerLauncher: ActivityResultLauncher<Array<String>>
    // Image picker for OCR
    private lateinit var ocrImagePickerLauncher: ActivityResultLauncher<String>
    // Image picker for "Quét thẻ" (chọn 2 mặt từ bộ sưu tập)
    private lateinit var idCardPickerLauncher: ActivityResultLauncher<String>
    // Image picker for "Quét QR" từ bộ sưu tập
    private lateinit var qrGalleryPickerLauncher: ActivityResultLauncher<String>
    // Drive authorization launcher (V06/S04)
    private lateinit var driveAuthorizationLauncher: ActivityResultLauncher<Intent>
    private var pendingDriveAuthAttempt: DriveAuthorizationAttempt? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = DocumentRepo.getInstance(requireContext())

        if (savedInstanceState != null) {
            @Suppress("DEPRECATION")
            pendingDriveAuthAttempt = savedInstanceState.getSerializable(KEY_PENDING_DRIVE_AUTH_ATTEMPT) as? DriveAuthorizationAttempt
        }

        // Image picker
        imagePickerLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
            if (uris.isNotEmpty()) {
                handleImportImages(uris)
            }
        }

        // File picker
        filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                handleImportFile(uri)
            }
        }

        // OCR Image Picker
        ocrImagePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                handleOcrFromUri(uri)
            }
        }

        // ID Card Image Picker (2 mặt thẻ)
        idCardPickerLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
            if (uris.isNotEmpty()) {
                handleImportIdCardImages(uris)
            }
        }

        // QR Gallery Image Picker
        qrGalleryPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                handleQrFromGallery(uri)
            }
        }

        // Drive authorization launcher (V06/S04)
        driveAuthorizationLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val ctx = context ?: return@registerForActivityResult
            val attempt = pendingDriveAuthAttempt
            pendingDriveAuthAttempt = null
            AppAuthManager.handleDrivePermissionResult(
                context = ctx,
                resultCode = result.resultCode,
                data = result.data,
                attempt = attempt,
                onSuccess = {
                    if (!isAdded) return@handleDrivePermissionResult
                    Toast.makeText(ctx, getString(R.string.drive_permission_granted_toast), Toast.LENGTH_SHORT).show()
                    val startUser = AppAuthManager.getCurrentUser()?.id
                    val startGen = AppAuthManager.getSessionGeneration()
                    AppAuthManager.runPostAuthorizationSync(ctx) { handlePostAuthSyncResult(it, startUser, startGen) }
                },
                onCancelled = {
                    // User cancelled consent prompt; quiet finish
                },
                onError = { errorMsg ->
                    if (!isAdded) return@handleDrivePermissionResult
                    Toast.makeText(ctx, errorMsg, Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupListeners()
        observeData()
        renderVipStatus()
    }

    override fun onResume() {
        super.onResume()
        checkActiveDraft()
        renderVipStatus()
    }

    private fun checkActiveDraft() {
        viewLifecycleOwner.lifecycleScope.launch {
            val sessionRepo = PostScanSessionRepository.getInstance(requireContext())
            val latestDraft = sessionRepo.getLatestDraft()
            if (_binding == null) return@launch

            if (latestDraft != null && latestDraft.pageStates.isNotEmpty()) {
                binding.layoutActiveDraft.visibility = View.VISIBLE
                binding.tvDraftTitle.text = latestDraft.documentTitle
                binding.tvDraftSubtitle.text = getString(
                    R.string.draft_resume_subtitle,
                    latestDraft.documentTitle,
                    latestDraft.pageStates.size
                )
                binding.layoutActiveDraft.setOnClickListener {
                    resumeDraft(latestDraft)
                }
                binding.btnResumeDraft.setOnClickListener {
                    resumeDraft(latestDraft)
                }
                binding.btnDiscardActiveDraft.setOnClickListener {
                    confirmDiscardDraft(latestDraft)
                }
            } else {
                binding.layoutActiveDraft.visibility = View.GONE
            }
        }
    }

    private fun resumeDraft(draft: PostScanSessionDraft) {
        val validPaths = draft.pageStates.map { it.inputImagePath }.filter { path ->
            val f = File(path)
            f.exists() && f.length() > 0L
        }
        if (validPaths.isNotEmpty()) {
            PostScanEditorActivity.start(
                context = requireContext(),
                sessionId = draft.sessionId,
                pagePaths = ArrayList(validPaths),
                title = draft.documentTitle
            )
        } else {
            binding.layoutActiveDraft.visibility = View.GONE
        }
    }

    private fun confirmDiscardDraft(draft: PostScanSessionDraft) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.draft_discard_confirm_title)
            .setMessage(R.string.draft_discard_confirm_msg)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val sessionRepo = PostScanSessionRepository.getInstance(requireContext())
                    sessionRepo.discardSession(draft.sessionId)
                    checkActiveDraft()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun setupRecyclerView() {
        docAdapter = DocumentAdapter(
            onItemClick = { doc ->
                openDocument(doc)
            },
            onActionClick = { doc, action ->
                handleDocumentAction(doc, action)
            }
        )

        binding.rvRecentDocs.layoutManager = LinearLayoutManager(requireContext())
        binding.rvRecentDocs.adapter = docAdapter
    }

    private fun setupListeners() {
        // Quick Action 1: Quét (Vào luồng quét mặc định theo chính sách, nhấn giữ để mở Camera Siêu Tốc)
        binding.btnActionScan.setOnClickListener {
            (activity as? MainActivity)?.startDocumentScan()
        }
        binding.btnActionScan.setOnLongClickListener {
            Toast.makeText(requireContext(), getString(R.string.opening_fast_scan_toast), Toast.LENGTH_SHORT).show()
            (activity as? MainActivity)?.startFastDocumentScan()
            true
        }

        // Quick Action 2: Quét thẻ (ID Card) (Mở hộp thoại hướng dẫn & tùy chọn, nhấn giữ để mở Camera Siêu Tốc)
        binding.btnActionIdCard.setOnClickListener {
            showIdCardOptionsDialog()
        }
        binding.btnActionIdCard.setOnLongClickListener {
            Toast.makeText(requireContext(), getString(R.string.opening_fast_id_card_scan_toast), Toast.LENGTH_SHORT).show()
            (activity as? MainActivity)?.startFastIdCardScan()
            true
        }

        // Quick Action 3: Quét QR
        binding.btnActionQr.setOnClickListener {
            showQrOptionsDialog()
        }

        // Quick Action 4: Công cụ PDF
        binding.btnActionPdfTools.setOnClickListener {
            showPdfToolsDialog()
        }

        // Quick Action 5: Nhập ảnh
        binding.btnActionImportImage.setOnClickListener {
            imagePickerLauncher.launch("image/*")
        }

        // Quick Action 6: Nhập tập tin
        binding.btnActionImportFile.setOnClickListener {
            filePickerLauncher.launch(arrayOf("application/pdf", "image/*", "text/*"))
        }

        // Quick Action 7: Trích xuất văn bản (OCR)
        binding.btnActionOcr.setOnClickListener {
            ocrImagePickerLauncher.launch("image/*")
        }

        // Quick Action 8: Tất cả -> Chuyển sang tab Công cụ
        binding.btnActionAllTools.setOnClickListener {
            (activity as? MainActivity)?.selectTab(R.id.nav_tools)
        }

        // Empty state scan button
        binding.btnScanNewDocument.setOnClickListener {
            (activity as? MainActivity)?.startDocumentScan()
        }

        // VIP icon
        binding.btnVipHome.setOnClickListener {
            val user = AppAuthManager.getCurrentUser()
            val dialog = VipUpgradeDialog(
                context = requireContext(),
                onRequestDrivePermission = { requestDrivePermission() },
                onRequestSignIn = {
                    val currentUser = AppAuthManager.getCurrentUser()
                    val opId = java.util.UUID.randomUUID().toString()
                    (activity as? MainActivity)?.navigateToMoreForVipSignIn(
                        action = com.tscanner.app.utils.VipContinuationAction.UPGRADE.name,
                        forceReauth = false,
                        expectedOwnerId = currentUser?.id,
                        originGeneration = AppAuthManager.getSessionGeneration(),
                        processEpoch = AppAuthManager.getProcessEpoch(),
                        operationId = opId
                    )
                },
                onRequestSignInForAction = { action ->
                    val currentUser = AppAuthManager.getCurrentUser()
                    val opId = java.util.UUID.randomUUID().toString()
                    (activity as? MainActivity)?.navigateToMoreForVipSignIn(
                        action = action.name,
                        forceReauth = true,
                        authReason = "AUTH_REQUIRED",
                        expectedOwnerId = currentUser?.id,
                        originGeneration = AppAuthManager.getSessionGeneration(),
                        processEpoch = AppAuthManager.getProcessEpoch(),
                        operationId = opId
                    )
                },
                onSyncResult = { result ->
                    val startUser = user?.id ?: ""
                    val startGen = AppAuthManager.getSessionGeneration()
                    handlePostAuthSyncResult(result, startUser, startGen)
                }
            )
            dialog.onRequestSignInForRecovery = { action, opContext, onStarted, onRefused ->
                val currentUser = AppAuthManager.getCurrentUser()
                val opId = opContext?.operationId ?: java.util.UUID.randomUUID().toString()
                val recoveryReq = com.tscanner.app.utils.billing.VipRecoveryRequest(
                    action = action,
                    operationContext = opContext,
                    expectedOwnerId = currentUser?.id,
                    originGeneration = AppAuthManager.getSessionGeneration(),
                    processEpoch = AppAuthManager.getProcessEpoch(),
                    onStarted = onStarted,
                    onRefused = onRefused
                )
                com.tscanner.app.utils.billing.VipRecoveryRegistry.register(opId, recoveryReq)
                (activity as? MainActivity)?.navigateToMoreForVipSignIn(
                    action = action.name,
                    forceReauth = true,
                    authReason = "AUTH_REQUIRED",
                    expectedOwnerId = currentUser?.id,
                    originGeneration = AppAuthManager.getSessionGeneration(),
                    processEpoch = AppAuthManager.getProcessEpoch(),
                    operationId = opId
                )
            }
            dialog.show()
        }

        // See all recent files -> switch to Files tab
        binding.tvSeeAllRecent.setOnClickListener {
            (activity as? MainActivity)?.selectTab(R.id.nav_files)
        }

        // Search text change
        binding.etSearchHome.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                refreshRecentDocs()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun observeData() {
        repo.documents.observe(viewLifecycleOwner) {
            refreshRecentDocs()
        }
        AppAuthManager.currentUser.observe(viewLifecycleOwner) {
            refreshRecentDocs()
            renderVipStatus()
        }
    }

    private fun renderVipStatus() {
        val currentBinding = _binding ?: return
        if (!isAdded) return

        val currentUser = AppAuthManager.getCurrentUser()
        val isVip = currentUser?.isVipActive == true

        vipExpiryJob?.cancel()
        vipExpiryJob = null

        if (isVip) {
            currentBinding.ivVipHomeBadge.visibility = View.VISIBLE
            currentBinding.tvFreeHomeBadge.visibility = View.GONE
            currentBinding.btnVipHome.contentDescription = getString(R.string.vip_home_desc_active)

            val expiresAt = currentUser?.vipExpiresAt
            if (expiresAt != null) {
                val delayMs = expiresAt - System.currentTimeMillis() + 100L
                if (delayMs > 0) {
                    vipExpiryJob = viewLifecycleOwner.lifecycleScope.launch {
                        delay(delayMs)
                        renderVipStatus()
                    }
                }
            }
        } else {
            currentBinding.ivVipHomeBadge.visibility = View.GONE
            currentBinding.tvFreeHomeBadge.visibility = View.VISIBLE
            currentBinding.btnVipHome.contentDescription = getString(R.string.vip_home_desc_free)
        }
    }

    private fun refreshRecentDocs() {
        val currentUserId = AppAuthManager.getCurrentUser()?.id
        val query = binding.etSearchHome.text?.toString().orEmpty().trim()
        val filtered = if (query.isBlank()) {
            repo.getRecentDocuments(10, currentUserId)
        } else {
            repo.searchDocuments(query, currentUserId)
        }
        updateListUI(filtered)
    }

    private fun requestDrivePermission() {
        if (!isAdded) return
        try {
            val intent = AppAuthManager.getGoogleDriveSignInIntent(requireContext()) { attempt ->
                pendingDriveAuthAttempt = attempt
            }
            driveAuthorizationLauncher.launch(intent)
        } catch (e: Exception) {
            val toCancel = pendingDriveAuthAttempt
            pendingDriveAuthAttempt = null
            AppAuthManager.cancelDriveAuthorizationAttempt(toCancel)
            Toast.makeText(requireContext(), getString(R.string.error_occurred_format, e.message.orEmpty()), Toast.LENGTH_SHORT).show()
        }
    }

    private fun handlePostAuthSyncResult(result: SyncCatalogResult, originUserId: String?, originSessionGen: Long) {
        if (!isAdded || _binding == null) return
        SyncResultPresenter.present(
            context = requireContext(),
            result = result,
            expectedSessionGeneration = originSessionGen,
            expectedUserId = originUserId,
            isHostValid = { isAdded && _binding != null && viewLifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) },
            onRequestDrivePermission = {
                requestDrivePermission()
            },
            onRetry = {
                if (isAdded && _binding != null) {
                    val retryUser = AppAuthManager.getCurrentUser()?.id
                    val retryGen = AppAuthManager.getSessionGeneration()
                    AppAuthManager.runPostAuthorizationSync(requireContext()) { retryResult ->
                        handlePostAuthSyncResult(retryResult, retryUser, retryGen)
                    }
                }
            },
            onDocumentsAdded = {
                refreshRecentDocs()
            }
        )
    }

    private fun updateListUI(docs: List<DocumentItem>) {
        if (docs.isEmpty()) {
            binding.layoutEmptyRecent.visibility = View.VISIBLE
            binding.rvRecentDocs.visibility = View.GONE
        } else {
            binding.layoutEmptyRecent.visibility = View.GONE
            binding.rvRecentDocs.visibility = View.VISIBLE
            docAdapter.submitList(docs)
        }
    }

    private fun openDocument(doc: DocumentItem) {
        val currentUserId = AppAuthManager.getCurrentUser()?.id
        val validDoc = repo.getDocumentForUser(doc.id, currentUserId, allowGuest = (currentUserId == null))
        if (validDoc == null) {
            Toast.makeText(requireContext(), getString(R.string.document_access_denied), Toast.LENGTH_SHORT).show()
            return
        }

        if (validDoc.pdfPath != null && File(validDoc.pdfPath).exists()) {
            PdfViewerActivity.start(requireContext(), validDoc.pdfPath, validDoc.title, validDoc.pagePaths)
        } else if (!validDoc.driveFileId.isNullOrEmpty()) {
            val progress = MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.downloading_from_drive_title))
                .setMessage(getString(R.string.downloading_from_drive_message, validDoc.title))
                .setCancelable(false)
                .create()
            progress.show()

            CloudBackupManager.downloadDocument(requireContext(), validDoc) { downloadedFile ->
                progress.dismiss()
                if (downloadedFile != null && downloadedFile.exists()) {
                    PdfViewerActivity.start(requireContext(), downloadedFile.absolutePath, validDoc.title, emptyList())
                } else {
                    Toast.makeText(requireContext(), getString(R.string.download_from_drive_failed), Toast.LENGTH_SHORT).show()
                }
            }
        } else if (validDoc.pagePaths.isNotEmpty()) {
            val first = validDoc.pagePaths.first()
            val intent = Intent(Intent.ACTION_VIEW).apply {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.provider",
                    File(first)
                )
                setDataAndType(uri, "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } else {
            Toast.makeText(requireContext(), getString(R.string.pdf_not_found), Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleDocumentAction(doc: DocumentItem, action: DocumentAdapter.ActionType) {
        val currentUserId = AppAuthManager.getCurrentUser()?.id
        val validDoc = repo.getDocumentForUser(doc.id, currentUserId, allowGuest = (currentUserId == null))
        if (validDoc == null) {
            Toast.makeText(requireContext(), getString(R.string.document_access_denied), Toast.LENGTH_SHORT).show()
            return
        }

        when (action) {
            DocumentAdapter.ActionType.SHARE -> {
                val path = validDoc.pdfPath ?: validDoc.pagePaths.firstOrNull()
                if (path != null) {
                    val file = File(path)
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        requireContext(),
                        "${requireContext().packageName}.provider",
                        file
                    )
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = if (path.endsWith(".pdf", true)) "application/pdf" else "image/*"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(shareIntent, getString(R.string.share)))
                }
            }
            DocumentAdapter.ActionType.DELETE -> {
                val deleteMsg = if (validDoc.isSynced || !validDoc.driveFileId.isNullOrEmpty()) {
                    getString(R.string.delete_document_synced_message, validDoc.title)
                } else {
                    getString(R.string.delete_document_named_message, validDoc.title)
                }
                ConfirmDeleteDialog(
                    context = requireContext(),
                    title = getString(R.string.delete_document_title),
                    message = deleteMsg
                ) {
                    repo.deleteDocument(validDoc.id, currentUserId)
                    Toast.makeText(requireContext(), getString(R.string.doc_deleted_toast), Toast.LENGTH_SHORT).show()
                }.show()
            }
            DocumentAdapter.ActionType.RENAME -> {
                RenameDocumentDialog(
                    context = requireContext(),
                    currentName = validDoc.title
                ) { newName ->
                    repo.renameDocument(validDoc.id, newName, currentUserId)
                    Toast.makeText(requireContext(), getString(R.string.doc_renamed_toast), Toast.LENGTH_SHORT).show()
                }.show()
            }
            DocumentAdapter.ActionType.OCR -> {
                processDocumentOcr(validDoc, isWordExport = false)
            }
            DocumentAdapter.ActionType.CONVERT_WORD -> {
                processDocumentOcr(validDoc, isWordExport = true)
            }
            DocumentAdapter.ActionType.MOVE_FOLDER -> {
                showMoveToFolderDialog(validDoc)
            }
            else -> {}
        }
    }

    private fun showMoveToFolderDialog(doc: DocumentItem) {
        val folders = repo.folders.value ?: emptyList()
        val folderNames = mutableListOf(getString(R.string.root_folder_default))
        folderNames.addAll(folders.map { it.name })

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.move_to_folder_title))
            .setItems(folderNames.toTypedArray()) { _, which ->
                val targetFolderId = if (which == 0) null else folders[which - 1].id
                val targetName = folderNames[which]
                repo.moveDocumentToFolder(doc.id, targetFolderId)
                Toast.makeText(requireContext(), getString(R.string.moved_doc_to_folder_format, doc.title, targetName), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun processDocumentOcr(doc: DocumentItem, isWordExport: Boolean) {
        val ctx = context ?: return
        if (!TextRecognitionHelper.isOcrDocumentLanguageConfigured(ctx)) {
            OcrLanguageSelectionDialog(requireActivity()) {
                processDocumentOcr(doc, isWordExport)
            }.show()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            var pdfFile = doc.pdfPath?.let { File(it) }?.takeIf { it.exists() }
            if (pdfFile == null && !doc.driveFileId.isNullOrEmpty()) {
                Toast.makeText(ctx, getString(R.string.downloading_from_drive_toast), Toast.LENGTH_SHORT).show()
                val downloaded = kotlinx.coroutines.suspendCancellableCoroutine<File?> { cont ->
                    CloudBackupManager.downloadDocument(ctx, doc) { file ->
                        if (cont.isActive) cont.resumeWith(Result.success(file))
                    }
                }
                if (!isAdded || view == null) return@launch
                pdfFile = downloaded
            }

            var pages = doc.pagePaths.filter { File(it).exists() }
            if (pages.isEmpty() && pdfFile != null) {
                Toast.makeText(ctx, getString(R.string.extracting_pages_toast), Toast.LENGTH_SHORT).show()
                val conv = PdfConverterHelper.convertPdfToImagesStructured(ctx, pdfFile)
                when (conv) {
                    is PdfConverterHelper.PdfToImagesResult.Success -> {
                        pages = conv.imagePaths
                    }
                    is PdfConverterHelper.PdfToImagesResult.Failure -> {
                        val msg = if (conv.totalPages > 0) {
                            getString(R.string.ocr_page_error_format, conv.failedPage, conv.message)
                        } else {
                            getString(R.string.no_pages_for_ocr)
                        }
                        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                        return@launch
                    }
                }
            }

            if (!isAdded || view == null) return@launch

            if (pages.isEmpty()) {
                Toast.makeText(ctx, getString(R.string.no_pages_for_ocr), Toast.LENGTH_SHORT).show()
                return@launch
            }

            val actionMsg = if (isWordExport) getString(R.string.extracting_and_converting_word) else getString(R.string.ocr_processing)
            Toast.makeText(ctx, actionMsg, Toast.LENGTH_SHORT).show()

            val ocrRequest = TextRecognitionHelper.getDefaultOcrRequest(ctx)
            val pageResults = mutableListOf<OcrResult>()

            for ((idx, pagePath) in pages.withIndex()) {
                if (!isAdded || view == null) return@launch
                val result = TextRecognitionHelper.recognizeTextFromFileStructured(ctx, pagePath, ocrRequest)
                pageResults.add(result)
                // Dừng xử lý các trang tiếp theo nếu gặp lỗi engine nghiêm trọng
                if (MultiPageOcrAggregator.isBlockingError(result)) {
                    break
                }
            }

            if (!isAdded || view == null) return@launch

            val aggResult = TextRecognitionHelper.aggregateMultiPageResults(ctx, pageResults)
            when (aggResult) {
                is MultiPageOcrResult.PageError -> {
                    val errorMsg = TextRecognitionHelper.formatPageErrorMessage(ctx, aggResult)
                    Toast.makeText(ctx, errorMsg, Toast.LENGTH_LONG).show()
                    return@launch
                }
                is MultiPageOcrResult.AllNoText -> {
                    Toast.makeText(ctx, getString(R.string.no_text_found), Toast.LENGTH_SHORT).show()
                    return@launch
                }
                is MultiPageOcrResult.Success -> {
                    TextRecognitionHelper.formatPartialSuccessNotice(ctx, aggResult)?.let { notice ->
                        Toast.makeText(ctx, notice, Toast.LENGTH_SHORT).show()
                    }

                    if (isWordExport) {
                        val exportDir = FileUtils.getExportsDir(ctx)
                        val file = File(exportDir, "${doc.title}_Word.doc")
                        val success = PdfConverterHelper.exportTextToWord(
                            text = aggResult.fullText,
                            outputFile = file,
                            addWatermark = WatermarkHelper.shouldApplyWatermark(ctx)
                        )
                        if (success) {
                            FileUtils.saveFileToDownloads(ctx, file, "application/msword")
                            Toast.makeText(ctx, getString(R.string.word_saved_format, file.name), Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(ctx, getString(R.string.export_word_failed), Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        val resolvedEngineLabel = if (aggResult.enginesUsed.isNotEmpty()) {
                            aggResult.enginesUsed.joinToString(", ")
                        } else {
                            TextRecognitionHelper.getPreferredEngineDisplayName(ctx)
                        }
                        OcrResultActivity.start(ctx, aggResult, resolvedEngineLabel, imagePaths = ArrayList(pages))
                    }
                }
            }
        }
    }

    private fun handleImportImages(uris: List<Uri>) {
        viewLifecycleOwner.lifecycleScope.launch {
            DocumentImportHelper.importImagesToPdf(requireContext(), uris, repo)
        }
    }

    private fun handleImportFile(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            val (savedFile, fileName) = withContext(Dispatchers.IO) {
                val fName = FileUtils.getFileName(requireContext(), uri)
                val docDir = FileUtils.getDocumentsDir(requireContext())
                val sFile = FileUtils.copyUriToAppStorage(requireContext(), uri, docDir, "file")
                Pair(sFile, fName)
            }

            if (savedFile != null) {
                val docId = UUID.randomUUID().toString()
                val isPdf = savedFile.name.endsWith(".pdf", ignoreCase = true)
                val thumbPath = withContext(Dispatchers.IO) {
                    if (isPdf) {
                        val thumbsDir = FileUtils.getThumbnailsDir(requireContext())
                        val thumbFile = File(thumbsDir, "thumb_${docId}.jpg")
                        if (PdfConverterHelper.renderPdfFirstPage(savedFile, thumbFile)) {
                            thumbFile.absolutePath
                        } else null
                    } else {
                        savedFile.absolutePath
                    }
                }

                val docItem = DocumentItem(
                    id = docId,
                    title = fileName,
                    pdfPath = if (isPdf) savedFile.absolutePath else null,
                    thumbnailPath = thumbPath,
                    pagePaths = if (!isPdf) listOf(savedFile.absolutePath) else emptyList(),
                    pageCount = 1,
                    sizeBytes = savedFile.length(),
                    createdAt = System.currentTimeMillis()
                )
                repo.addDocument(docItem)
                if (isAdded && context != null) {
                    Toast.makeText(requireContext(), getString(R.string.file_imported, fileName), Toast.LENGTH_SHORT).show()
                    if (isPdf) {
                        PdfViewerActivity.start(requireContext(), savedFile.absolutePath, docItem.title)
                    }
                }
            } else {
                if (isAdded && context != null) {
                    Toast.makeText(requireContext(), getString(R.string.import_file_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun handleOcrFromUri(uri: Uri) {
        val ctx = context ?: return
        if (!TextRecognitionHelper.isOcrDocumentLanguageConfigured(ctx)) {
            OcrLanguageSelectionDialog(requireActivity()) {
                handleOcrFromUri(uri)
            }.show()
            return
        }

        Toast.makeText(requireContext(), getString(R.string.ocr_processing), Toast.LENGTH_SHORT).show()
        val ocrRequest = TextRecognitionHelper.getDefaultOcrRequest(requireContext())
        val tempDir = FileUtils.getTempScanSessionDir(requireContext(), "ocr_single_${System.currentTimeMillis()}")
        val tempFile = FileUtils.copyUriToAppStorage(requireContext(), uri, tempDir, "ocr_input")
        val localImagePath = tempFile?.absolutePath

        TextRecognitionHelper.recognizeTextFromUriStructuredCallback(
            requireContext(),
            uri,
            ocrRequest
        ) { result ->
            if (!isAdded || context == null) return@recognizeTextFromUriStructuredCallback
            when (result) {
                is OcrResult.Success -> {
                    if (result.text.isNotBlank()) {
                        OcrResultActivity.start(requireContext(), result, imagePath = localImagePath)
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.no_text_found), Toast.LENGTH_SHORT).show()
                    }
                }
                else -> {
                    TextRecognitionHelper.showOcrErrorToast(requireContext(), result)
                }
            }
        }
    }

    private fun showIdCardOptionsDialog() {
        IdCardOptionsDialog(
            context = requireContext(),
            onCameraScan = {
                (activity as? MainActivity)?.startIdCardScan()
            },
            onFastScan = {
                (activity as? MainActivity)?.startFastIdCardScan()
            },
            onGalleryPick = {
                idCardPickerLauncher.launch("image/*")
            }
        ).show()
    }

    private fun handleImportIdCardImages(uris: List<Uri>) {
        viewLifecycleOwner.lifecycleScope.launch {
            val sessionId = UUID.randomUUID().toString()
            val tempDir = FileUtils.getTempScanSessionDir(requireContext(), sessionId)

            val frontFile = uris.getOrNull(0)?.let { uri ->
                val f = File(tempDir, "id_front.jpg")
                requireContext().contentResolver.openInputStream(uri)?.use { input ->
                    java.io.FileOutputStream(f).use { output -> input.copyTo(output) }
                }
                f.absolutePath
            }

            val backFile = uris.getOrNull(1)?.let { uri ->
                val f = File(tempDir, "id_back.jpg")
                requireContext().contentResolver.openInputStream(uri)?.use { input ->
                    java.io.FileOutputStream(f).use { output -> input.copyTo(output) }
                }
                f.absolutePath
            }

            if (frontFile != null || backFile != null) {
                IdCardComposeActivity.startWithSides(
                    context = requireContext(),
                    frontPath = frontFile,
                    backPath = backFile
                )
            } else {
                Toast.makeText(requireContext(), getString(R.string.cannot_read_id_card_images), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showPdfToolsDialog() {
        val options = arrayOf(
            getString(R.string.pdf_tool_create_from_images),
            getString(R.string.pdf_tool_import_view),
            getString(R.string.pdf_tool_merge)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.action_pdf_tools))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> imagePickerLauncher.launch("image/*")
                    1 -> filePickerLauncher.launch(arrayOf("application/pdf"))
                    2 -> {
                        imagePickerLauncher.launch("image/*")
                        Toast.makeText(requireContext(), getString(R.string.select_images_to_merge_prompt), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(getString(R.string.close), null)
            .show()
    }

    private fun showQrOptionsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_qr_options, null)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<View>(R.id.btn_option_qr_camera)?.setOnClickListener {
            dialog.dismiss()
            startQrCameraScan()
        }

        dialogView.findViewById<View>(R.id.btn_option_qr_gallery)?.setOnClickListener {
            dialog.dismiss()
            qrGalleryPickerLauncher.launch("image/*")
        }

        dialog.show()
    }

    private fun startQrCameraScan() {
        val act = activity ?: return
        QrScannerHelper.startCameraScan(
            activity = act,
            onSuccess = { result ->
                if (isAdded && context != null) {
                    showQrResult(result)
                }
            },
            onCancel = {
                // Người dùng hủy quét camera
            },
            onError = { err ->
                if (isAdded && context != null) {
                    Toast.makeText(requireContext(), err, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun handleQrFromGallery(uri: Uri) {
        val ctx = context ?: return
        Toast.makeText(ctx, getString(R.string.reading_qr_code_toast), Toast.LENGTH_SHORT).show()
        QrScannerHelper.scanFromUri(
            context = ctx,
            uri = uri,
            onSuccess = { result ->
                if (isAdded && context != null) {
                    showQrResult(result)
                }
            },
            onError = { err ->
                if (isAdded && context != null) {
                    Toast.makeText(requireContext(), err, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun showQrResult(result: QrCodeResult) {
        if (!isAdded || context == null) return
        QrResultDialog(
            context = requireContext(),
            result = result,
            onRescan = {
                if (isAdded && context != null) {
                    showQrOptionsDialog()
                }
            }
        ).show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putSerializable(KEY_PENDING_DRIVE_AUTH_ATTEMPT, pendingDriveAuthAttempt)
    }

    override fun onDestroyView() {
        vipExpiryJob?.cancel()
        vipExpiryJob = null
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val KEY_PENDING_DRIVE_AUTH_ATTEMPT = "key_pending_drive_auth_attempt"
    }
}
