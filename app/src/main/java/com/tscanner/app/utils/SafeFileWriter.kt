package com.tscanner.app.utils

import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * R04 / T01: SafeFileWriter guarantees that destination files are never truncated or corrupted
 * when generating PDFs, JPEGs, or saving documents.
 * It writes to a temporary file in the same directory, validates contents,
 * and atomically replaces the destination file.
 */
object SafeFileWriter {

    private const val TAG = "SafeFileWriter"
    private val pathLocks = ConcurrentHashMap<String, Mutex>()

    sealed class Result {
        data class Success(val file: File) : Result()
        data class Error(val message: String, val cause: Throwable? = null) : Result()
    }

    /**
     * Strategy interface for committing temporary files to target destinations.
     * Allows test isolation and simulation of filesystem failures.
     */
    fun interface FileCommitStrategy {
        /**
         * Safely commits [sourceTemp] to [destination].
         * Returns true if replacement succeeded, false otherwise.
         * Implementation MUST NOT delete [destination] if replacement cannot be safely completed.
         */
        @Throws(Exception::class)
        fun commit(sourceTemp: File, destination: File): Boolean
    }

    /**
     * Default atomic commit strategy using Java NIO Files.move with fallback.
     * Guarantees that [destination] is NEVER deleted prior to replacement.
     */
    val DefaultFileCommitStrategy: FileCommitStrategy = FileCommitStrategy { sourceTemp, destination ->
        if (!destination.exists()) {
            return@FileCommitStrategy try {
                Files.move(sourceTemp.toPath(), destination.toPath())
                true
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                sourceTemp.renameTo(destination)
            }
        }

        // When destination already exists, we must preserve it if commit fails.
        // Never call destination.delete() before replacement!
        try {
            try {
                Files.move(
                    sourceTemp.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
                true
            } catch (e: AtomicMoveNotSupportedException) {
                // Filesystem does not support ATOMIC_MOVE, fallback to REPLACE_EXISTING
                Files.move(
                    sourceTemp.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
                true
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Files.move failed to safely replace destination file: ${e.message}")
            // T01: Fail-safe. DO NOT delete destination! Original content is preserved.
            false
        }
    }

    private fun getLock(file: File): Mutex {
        val key = file.canonicalPath
        return pathLocks.computeIfAbsent(key) { Mutex() }
    }

    /**
     * Atomically and safely writes a file.
     * @param destinationFile The target destination file.
     * @param validator Optional validator run on temp file before replacing destination.
     * @param commitStrategy Strategy used to commit the temp file to destination.
     * @param writer Action that writes content into the provided temp file.
     */
    suspend fun writeSafely(
        destinationFile: File,
        validator: ((File) -> Boolean)? = null,
        commitStrategy: FileCommitStrategy = DefaultFileCommitStrategy,
        writer: suspend (File) -> Boolean
    ): Result = withContext(Dispatchers.IO) {
        val parentDir = destinationFile.parentFile ?: return@withContext Result.Error("Parent directory is null")
        if (!parentDir.exists()) {
            parentDir.mkdirs()
        }

        val mutex = getLock(destinationFile)
        mutex.withLock {
            val tempFile = File(parentDir, "safe_tmp_${System.currentTimeMillis()}_${UUID.randomUUID()}.tmp")
            try {
                val writeSuccess = writer(tempFile)
                if (!writeSuccess || !tempFile.exists() || tempFile.length() == 0L) {
                    tempFile.delete()
                    return@withContext Result.Error("Writer returned false or created an empty file")
                }

                // Run validator if provided
                if (validator != null && !validator(tempFile)) {
                    tempFile.delete()
                    return@withContext Result.Error("File validation failed for ${tempFile.name}")
                }

                // Atomic replacement
                val commitSuccess = commitStrategy.commit(tempFile, destinationFile)
                if (commitSuccess) {
                    Result.Success(destinationFile)
                } else {
                    try { tempFile.delete() } catch (_: Exception) {}
                    Result.Error("Failed to safely replace destination file ${destinationFile.absolutePath}")
                }
            } catch (e: Exception) {
                if (e is CancellationException) {
                    try { tempFile.delete() } catch (_: Exception) {}
                    throw e
                }
                Log.e(TAG, "SafeFileWriter error writing to ${destinationFile.absolutePath}", e)
                try { tempFile.delete() } catch (_: Exception) {}
                Result.Error("Exception during safe write: ${e.message}", e)
            }
        }
    }

    internal fun commitAtomic(sourceTemp: File, destination: File): Boolean {
        return DefaultFileCommitStrategy.commit(sourceTemp, destination)
    }

    /**
     * Helper to validate that a PDF file can be parsed and opened.
     */
    fun validatePdf(file: File, expectedMinPages: Int = 1): Boolean {
        if (!file.exists() || file.length() < 32L) return false
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        return try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            val pageCount = renderer.pageCount
            pageCount >= expectedMinPages
        } catch (e: Exception) {
            Log.w(TAG, "PDF validation failed for ${file.name}: ${e.message}")
            false
        } finally {
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Extracts actual page count from a PDF file.
     * Pluggable for unit test execution without native PdfRenderer.
     */
    var pdfPageCountExtractor: (File) -> Int = { file ->
        if (!file.exists() || file.length() < 32L) {
            0
        } else {
            var pfd: ParcelFileDescriptor? = null
            var renderer: PdfRenderer? = null
            try {
                pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                renderer = PdfRenderer(pfd)
                renderer.pageCount
            } catch (e: Exception) {
                0
            } finally {
                try { renderer?.close() } catch (_: Exception) {}
                try { pfd?.close() } catch (_: Exception) {}
            }
        }
    }

    fun getPdfPageCount(file: File): Int = pdfPageCountExtractor(file)

    /**
     * Strategy interface for validating image files.
     * Allows test isolation without requiring Android native graphics stack.
     */
    fun interface ImageValidator {
        fun validate(file: File): Boolean
    }

    val DefaultImageValidator: ImageValidator = ImageValidator { file ->
        if (!file.exists() || file.length() < 16L) return@ImageValidator false
        try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, options)
            options.outWidth > 0 && options.outHeight > 0
        } catch (e: Exception) {
            Log.w(TAG, "Image validation failed for ${file.name}: ${e.message}")
            false
        }
    }

    var imageValidator: ImageValidator = DefaultImageValidator

    /**
     * Helper to validate that a JPEG/image file has valid dimensions and header.
     */
    fun validateImage(file: File): Boolean = imageValidator.validate(file)
}
