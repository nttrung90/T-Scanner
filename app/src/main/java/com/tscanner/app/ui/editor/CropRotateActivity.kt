package com.tscanner.app.ui.editor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import com.tscanner.app.R
import com.tscanner.app.databinding.ActivityCropRotateBinding
import com.tscanner.app.utils.EdgeToEdgeInsetsHelper
import com.tscanner.app.utils.SafeFileWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class CropRotateActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCropRotateBinding
    private val viewModel: CropRotateViewModel by viewModels()
    private var imagePath: String = ""
    private var currentBitmap: Bitmap? = null
    private var currentRotationAngle: Float = 0f

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putFloat(KEY_ROTATION_DEGREES, currentRotationAngle)
        val normRect = viewModel.resolveCropRectForSaveState(
            isOverlayInitialized = binding.cropOverlayView.isInitialized(),
            currentOverlayRect = binding.cropOverlayView.getNormalizedCropRect()
        )
        outState.putFloatArray(
            KEY_NORMALIZED_CROP_RECT,
            floatArrayOf(normRect.left, normRect.top, normRect.right, normRect.bottom)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        binding = ActivityCropRotateBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState != null) {
            currentRotationAngle = savedInstanceState.getFloat(KEY_ROTATION_DEGREES, 0f)
            val rectArray = savedInstanceState.getFloatArray(KEY_NORMALIZED_CROP_RECT)
            if (rectArray != null && rectArray.size == 4) {
                viewModel.pendingNormalizedCropRect = RectF(rectArray[0], rectArray[1], rectArray[2], rectArray[3])
            }
        }

        val initialToolbarPadding = EdgeToEdgeInsetsHelper.recordInitialPadding(binding.layoutCropToolbar)
        val initialBottomPadding = EdgeToEdgeInsetsHelper.recordInitialPadding(binding.layoutCropBottomActions)
        val initialContainerPadding = EdgeToEdgeInsetsHelper.recordInitialPadding(binding.layoutCropContainer)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val sysInsets = EdgeToEdgeInsetsHelper.getSystemBarAndCutoutInsets(insets)

            EdgeToEdgeInsetsHelper.applyTopBarInsets(
                binding.layoutCropToolbar,
                initialToolbarPadding,
                sysInsets
            )

            EdgeToEdgeInsetsHelper.applyBottomBarInsets(
                binding.layoutCropBottomActions,
                initialBottomPadding,
                sysInsets,
                sysInsets.bottom
            )

            EdgeToEdgeInsetsHelper.applyContentHorizontalInsets(
                binding.layoutCropContainer,
                initialContainerPadding,
                sysInsets
            )

            insets
        }
        ViewCompat.requestApplyInsets(binding.root)

        imagePath = intent.getStringExtra(EXTRA_IMAGE_PATH) ?: ""
        if (imagePath.isEmpty() || !File(imagePath).exists()) {
            Toast.makeText(this, R.string.crop_image_not_found, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        viewModel.saveState.observe(this) { state ->
            when (state) {
                is CropSaveState.Idle -> {
                    binding.pbCropLoading.visibility = View.GONE
                    binding.btnSaveCrop.isEnabled = true
                    binding.btnCropRotateLeft.isEnabled = true
                    binding.btnCropRotateRight.isEnabled = true
                    binding.btnCropReset.isEnabled = true
                    binding.btnCancelCrop.isEnabled = true
                }
                is CropSaveState.Saving -> {
                    binding.pbCropLoading.visibility = View.VISIBLE
                    binding.btnSaveCrop.isEnabled = false
                    binding.btnCropRotateLeft.isEnabled = false
                    binding.btnCropRotateRight.isEnabled = false
                    binding.btnCropReset.isEnabled = false
                    binding.btnCancelCrop.isEnabled = false
                }
                is CropSaveState.Committed -> {
                    binding.pbCropLoading.visibility = View.GONE
                    Toast.makeText(this, R.string.crop_save_success, Toast.LENGTH_SHORT).show()
                    val resultIntent = Intent().apply {
                        putExtra(EXTRA_IMAGE_PATH, state.imagePath)
                        putExtra(EXTRA_PAGE_INDEX, state.pageIndex)
                    }
                    setResult(Activity.RESULT_OK, resultIntent)
                    finish()
                }
                is CropSaveState.Error -> {
                    binding.pbCropLoading.visibility = View.GONE
                    binding.btnSaveCrop.isEnabled = true
                    binding.btnCropRotateLeft.isEnabled = true
                    binding.btnCropRotateRight.isEnabled = true
                    binding.btnCropReset.isEnabled = true
                    binding.btnCancelCrop.isEnabled = true
                    Toast.makeText(this, state.messageResId, Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnCancelCrop.setOnClickListener {
            if (viewModel.isSaving()) return@setOnClickListener
            finish()
        }

        binding.btnSaveCrop.setOnClickListener {
            saveCroppedImage()
        }

        binding.btnCropRotateLeft.setOnClickListener {
            rotateCurrentBitmap(-90f)
        }

        binding.btnCropRotateRight.setOnClickListener {
            rotateCurrentBitmap(90f)
        }

        binding.btnCropReset.setOnClickListener {
            if (viewModel.isSaving()) return@setOnClickListener
            viewModel.pendingNormalizedCropRect = null
            binding.cropOverlayView.resetToFull()
        }

        if (!viewModel.isCommitted() && !viewModel.isSaving() && currentBitmap == null) {
            loadImage()
        }
    }

    private fun loadImage() {
        binding.pbCropLoading.visibility = View.VISIBLE
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                try {
                    val decoded = BitmapFactory.decodeFile(imagePath)
                    if (decoded != null && currentRotationAngle != 0f) {
                        val matrix = Matrix().apply { postRotate(currentRotationAngle) }
                        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                        if (rotated != decoded) {
                            decoded.recycle()
                        }
                        rotated
                    } else {
                        decoded
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }

            binding.pbCropLoading.visibility = View.GONE
            if (bmp != null) {
                currentBitmap = bmp
                updateImageDisplay()
            } else {
                Toast.makeText(this@CropRotateActivity, R.string.crop_image_read_error, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun updateImageDisplay() {
        val bmp = currentBitmap ?: return
        binding.ivCropTarget.setImageBitmap(bmp)
        binding.layoutCropContainer.doOnLayout {
            val bounds = calculateImageBounds(binding.ivCropTarget, bmp)
            val pending = viewModel.pendingNormalizedCropRect
            if (pending != null) {
                binding.cropOverlayView.setImageBounds(bounds)
                binding.cropOverlayView.setNormalizedCropRect(pending)
                viewModel.pendingNormalizedCropRect = null
            } else {
                binding.cropOverlayView.setImageBoundsPreservingNormalizedRect(bounds)
            }
        }
    }

    private fun rotateCurrentBitmap(degrees: Float) {
        if (viewModel.isSaving() || viewModel.isCommitted()) return
        val bmp = currentBitmap ?: return
        currentRotationAngle = (currentRotationAngle + degrees) % 360f
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        if (bmp != rotated) {
            bmp.recycle()
        }
        currentBitmap = rotated
        updateImageDisplay()
    }

    private fun saveCroppedImage() {
        if (viewModel.isSaving() || viewModel.isCommitted()) return
        val bmp = currentBitmap ?: return
        val normRect = viewModel.resolveCropRectForSaveState(
            isOverlayInitialized = binding.cropOverlayView.isInitialized(),
            currentOverlayRect = binding.cropOverlayView.getNormalizedCropRect()
        )
        val pageIndex = intent.getIntExtra(EXTRA_PAGE_INDEX, 0)
        viewModel.save(
            bitmap = bmp,
            normalizedRect = normRect,
            imagePath = imagePath,
            pageIndex = pageIndex
        )
    }

    private fun calculateImageBounds(imageView: ImageView, bitmap: Bitmap): RectF {
        val viewWidth = imageView.width.toFloat()
        val viewHeight = imageView.height.toFloat()
        if (viewWidth <= 0 || viewHeight <= 0 || bitmap.width <= 0 || bitmap.height <= 0) {
            return RectF(0f, 0f, viewWidth, viewHeight)
        }
        val scale = minOf(viewWidth / bitmap.width, viewHeight / bitmap.height)
        val drawnWidth = bitmap.width * scale
        val drawnHeight = bitmap.height * scale
        val left = (viewWidth - drawnWidth) / 2f
        val top = (viewHeight - drawnHeight) / 2f
        return RectF(left, top, left + drawnWidth, top + drawnHeight)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && !viewModel.isSaving()) {
            currentBitmap?.recycle()
            currentBitmap = null
        }
    }

    companion object {
        const val EXTRA_IMAGE_PATH = "extra_image_path"
        const val EXTRA_PAGE_INDEX = "extra_page_index"
        private const val KEY_ROTATION_DEGREES = "key_rotation_degrees"
        private const val KEY_NORMALIZED_CROP_RECT = "key_normalized_crop_rect"

        fun createIntent(context: Context, imagePath: String, pageIndex: Int): Intent {
            return Intent(context, CropRotateActivity::class.java).apply {
                putExtra(EXTRA_IMAGE_PATH, imagePath)
                putExtra(EXTRA_PAGE_INDEX, pageIndex)
            }
        }
    }
}
