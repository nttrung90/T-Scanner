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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import com.tscanner.app.databinding.ActivityCropRotateBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class CropRotateActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCropRotateBinding
    private var imagePath: String = ""
    private var currentBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        binding = ActivityCropRotateBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBarInsets = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val navInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())

            binding.layoutCropToolbar.setPadding(
                binding.layoutCropToolbar.paddingLeft,
                statusBarInsets.top,
                binding.layoutCropToolbar.paddingRight,
                binding.layoutCropToolbar.paddingBottom
            )

            binding.layoutCropBottomActions.setPadding(
                binding.layoutCropBottomActions.paddingLeft,
                binding.layoutCropBottomActions.paddingTop,
                binding.layoutCropBottomActions.paddingRight,
                (12 * resources.displayMetrics.density).toInt() + navInsets.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)

        imagePath = intent.getStringExtra(EXTRA_IMAGE_PATH) ?: ""
        if (imagePath.isEmpty() || !File(imagePath).exists()) {
            Toast.makeText(this, "Không tìm thấy ảnh cần chỉnh sửa", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.btnCancelCrop.setOnClickListener {
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
            binding.cropOverlayView.resetToFull()
        }

        loadImage()
    }

    private fun loadImage() {
        binding.pbCropLoading.visibility = View.VISIBLE
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                try {
                    BitmapFactory.decodeFile(imagePath)
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
                Toast.makeText(this@CropRotateActivity, "Không thể đọc file ảnh", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun updateImageDisplay() {
        val bmp = currentBitmap ?: return
        binding.ivCropTarget.setImageBitmap(bmp)
        binding.layoutCropContainer.doOnLayout {
            val bounds = calculateImageBounds(binding.ivCropTarget, bmp)
            binding.cropOverlayView.setImageBounds(bounds)
        }
    }

    private fun rotateCurrentBitmap(degrees: Float) {
        val bmp = currentBitmap ?: return
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        if (bmp != rotated) {
            bmp.recycle()
        }
        currentBitmap = rotated
        updateImageDisplay()
    }

    private fun saveCroppedImage() {
        val bmp = currentBitmap ?: return
        val normRect = binding.cropOverlayView.getNormalizedCropRect()

        binding.pbCropLoading.visibility = View.VISIBLE
        binding.btnSaveCrop.isEnabled = false

        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    val x = (normRect.left * bmp.width).toInt().coerceIn(0, bmp.width - 1)
                    val y = (normRect.top * bmp.height).toInt().coerceIn(0, bmp.height - 1)
                    val w = (normRect.width() * bmp.width).toInt().coerceIn(1, bmp.width - x)
                    val h = (normRect.height() * bmp.height).toInt().coerceIn(1, bmp.height - y)

                    val cropped = Bitmap.createBitmap(bmp, x, y, w, h)
                    val file = File(imagePath)
                    FileOutputStream(file).use { out ->
                        cropped.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    }
                    if (cropped != bmp) {
                        cropped.recycle()
                    }
                    true
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }

            binding.pbCropLoading.visibility = View.GONE
            binding.btnSaveCrop.isEnabled = true

            if (success) {
                Toast.makeText(this@CropRotateActivity, "Đã cắt và lưu trang thành công", Toast.LENGTH_SHORT).show()
                val resultIntent = Intent().apply {
                    putExtra(EXTRA_IMAGE_PATH, imagePath)
                    putExtra(EXTRA_PAGE_INDEX, intent.getIntExtra(EXTRA_PAGE_INDEX, 0))
                }
                setResult(Activity.RESULT_OK, resultIntent)
                finish()
            } else {
                Toast.makeText(this@CropRotateActivity, "Lỗi khi lưu ảnh cắt", Toast.LENGTH_SHORT).show()
            }
        }
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
        currentBitmap?.recycle()
        currentBitmap = null
    }

    companion object {
        const val EXTRA_IMAGE_PATH = "extra_image_path"
        const val EXTRA_PAGE_INDEX = "extra_page_index"

        fun createIntent(context: Context, imagePath: String, pageIndex: Int): Intent {
            return Intent(context, CropRotateActivity::class.java).apply {
                putExtra(EXTRA_IMAGE_PATH, imagePath)
                putExtra(EXTRA_PAGE_INDEX, pageIndex)
            }
        }
    }
}
