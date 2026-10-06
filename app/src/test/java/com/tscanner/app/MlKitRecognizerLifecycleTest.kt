package com.tscanner.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.Image
import com.google.android.gms.common.Feature
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.OnSuccessListener
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.interfaces.Detector
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tscanner.app.utils.EngineRunResult
import com.tscanner.app.utils.OcrModelUnavailableType
import com.tscanner.app.utils.TextRecognitionHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reproduction & verification tests for TextRecognitionHelper ML Kit client lifecycle.
 *
 * Verifies that TextRecognizer instances created during OCR processing are reliably
 * and deterministically closed exactly once across all termination conditions:
 * - Success (with text)
 * - NoText (empty result)
 * - Failure (task execution exception)
 * - ModelUnavailable (unbundled model pending download)
 * - Synchronous exception during task submission
 * - Coroutine cancellation
 * - Factory failure (uncreated client must not be closed)
 */
class MlKitRecognizerLifecycleTest {

    private lateinit var fakeRecognizer: FakeTextRecognizer
    private lateinit var controllableTask: ControllableTask<Text>

    @Before
    fun setUp() {
        controllableTask = ControllableTask()
        fakeRecognizer = FakeTextRecognizer { controllableTask }

        TextRecognitionHelper.textRecognizerFactory = { fakeRecognizer }
        TextRecognitionHelper.inputImageFactory = {
            val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
            unsafeField.isAccessible = true
            val unsafe = unsafeField.get(null) as sun.misc.Unsafe
            unsafe.allocateInstance(InputImage::class.java) as InputImage
        }
    }

    @After
    fun tearDown() {
        TextRecognitionHelper.textRecognizerFactory = { TextRecognition.getClient(it) }
        TextRecognitionHelper.inputImageFactory = { InputImage.fromBitmap(it!!, 0) }
    }

    @Test
    fun testProcessMlKitRecognition_onSuccess_mustCloseClientOnce() = runBlocking {
        controllableTask.completeWithSuccess(Text("Valid detected text", emptyList<Text.TextBlock>()))

        val result = TextRecognitionHelper.processMlKitRecognition(
            options = TextRecognizerOptions.DEFAULT_OPTIONS,
            bitmap = null,
            engineId = "mlkit_latin",
            documentLanguage = "en"
        )

        assertTrue("Expected Success result, got: $result", result is EngineRunResult.Success)
        assertEquals("Client must be closed on success", 1, fakeRecognizer.closeCallCount.get())
    }

    @Test
    fun testProcessMlKitRecognition_onNoText_mustCloseClientOnce() = runBlocking {
        controllableTask.completeWithSuccess(Text("   ", emptyList<Text.TextBlock>()))

        val result = TextRecognitionHelper.processMlKitRecognition(
            options = TextRecognizerOptions.DEFAULT_OPTIONS,
            bitmap = null,
            engineId = "mlkit_latin",
            documentLanguage = "en"
        )

        assertTrue("Expected NoText result, got: $result", result is EngineRunResult.NoText)
        assertEquals("Client must be closed on NoText", 1, fakeRecognizer.closeCallCount.get())
    }

    @Test
    fun testProcessMlKitRecognition_onFailure_mustCloseClientOnce() = runBlocking {
        controllableTask.completeWithFailure(MlKitException("Inference internal error", MlKitException.INTERNAL))

        val result = TextRecognitionHelper.processMlKitRecognition(
            options = TextRecognizerOptions.DEFAULT_OPTIONS,
            bitmap = null,
            engineId = "mlkit_latin",
            documentLanguage = "en"
        )

        assertTrue("Expected Failure result, got: $result", result is EngineRunResult.Failure)
        assertEquals("Client must be closed on task Failure", 1, fakeRecognizer.closeCallCount.get())
    }

    @Test
    fun testProcessMlKitRecognition_onModelUnavailable_mustCloseClientOnce() = runBlocking {
        controllableTask.completeWithFailure(MlKitException("Waiting for model", MlKitException.UNAVAILABLE))

        val result = TextRecognitionHelper.processMlKitRecognition(
            options = TextRecognizerOptions.DEFAULT_OPTIONS,
            bitmap = null,
            engineId = "mlkit_latin",
            documentLanguage = "en"
        )

        assertTrue("Expected ModelUnavailable result, got: $result", result is EngineRunResult.ModelUnavailable)
        assertEquals("Client must be closed on ModelUnavailable", 1, fakeRecognizer.closeCallCount.get())
    }

    @Test
    fun testProcessMlKitRecognition_onSynchronousException_mustCloseClientOnce() = runBlocking {
        fakeRecognizer = FakeTextRecognizer {
            throw IllegalStateException("Synchronous runner crash")
        }
        TextRecognitionHelper.textRecognizerFactory = { fakeRecognizer }

        val result = TextRecognitionHelper.processMlKitRecognition(
            options = TextRecognizerOptions.DEFAULT_OPTIONS,
            bitmap = null,
            engineId = "mlkit_latin",
            documentLanguage = "en"
        )

        assertTrue("Expected Failure result on sync throw, got: $result", result is EngineRunResult.Failure)
        assertEquals("Client must be closed on synchronous exception", 1, fakeRecognizer.closeCallCount.get())
    }

    @Test
    fun testProcessMlKitRecognition_onCancellation_mustCloseClientOnce() = runBlocking {
        val startedLatch = java.util.concurrent.CountDownLatch(1)
        fakeRecognizer = FakeTextRecognizer {
            startedLatch.countDown()
            controllableTask
        }
        TextRecognitionHelper.textRecognizerFactory = { fakeRecognizer }

        val job = launch(Dispatchers.Default) {
            try {
                TextRecognitionHelper.processMlKitRecognition(
                    options = TextRecognizerOptions.DEFAULT_OPTIONS,
                    bitmap = null,
                    engineId = "mlkit_latin",
                    documentLanguage = "en"
                )
            } catch (_: CancellationException) {
                // Expected on cancellation
            }
        }

        assertTrue("Task should have started", startedLatch.await(5, java.util.concurrent.TimeUnit.SECONDS))
        job.cancelAndJoin()

        assertTrue("Job must have been cancelled", job.isCancelled)
        assertEquals("Client must be closed when coroutine is cancelled", 1, fakeRecognizer.closeCallCount.get())
    }

    @Test
    fun testProcessMlKitRecognition_factoryThrows_doesNotCloseUncreatedClient() = runBlocking {
        TextRecognitionHelper.textRecognizerFactory = {
            throw RuntimeException("Failed to construct recognizer")
        }

        val result = TextRecognitionHelper.processMlKitRecognition(
            options = TextRecognizerOptions.DEFAULT_OPTIONS,
            bitmap = null,
            engineId = "mlkit_latin",
            documentLanguage = "en"
        )

        assertTrue("Expected Failure result when factory throws, got: $result", result is EngineRunResult.Failure)
        assertEquals("Uncreated client must not be closed", 0, fakeRecognizer.closeCallCount.get())
    }

    @Test
    fun testProcessMlKitRecognition_lateCallbackAfterCancellation_doesNotDoubleCloseOrThrow() = runBlocking {
        val startedLatch = java.util.concurrent.CountDownLatch(1)
        fakeRecognizer = FakeTextRecognizer {
            startedLatch.countDown()
            controllableTask
        }
        TextRecognitionHelper.textRecognizerFactory = { fakeRecognizer }

        val job = launch(Dispatchers.Default) {
            try {
                TextRecognitionHelper.processMlKitRecognition(
                    options = TextRecognizerOptions.DEFAULT_OPTIONS,
                    bitmap = null,
                    engineId = "mlkit_latin",
                    documentLanguage = "en"
                )
            } catch (_: CancellationException) {
                // Expected on cancellation
            }
        }

        assertTrue("Task should have started", startedLatch.await(5, java.util.concurrent.TimeUnit.SECONDS))
        job.cancelAndJoin()

        // Invoke late callbacks after cancellation has finished
        controllableTask.completeWithSuccess(Text("Late result", emptyList<Text.TextBlock>()))
        controllableTask.completeWithFailure(IllegalStateException("Late failure"))

        assertEquals("Client must still be closed exactly once even if late callbacks arrive", 1, fakeRecognizer.closeCallCount.get())
    }

    @Test
    fun testProcessMlKitRecognition_onMapperException_closesClientOnceAndReturnsFailure() = runBlocking {
        val throwingVisionText = object : Text("Valid text", emptyList<Text.TextBlock>()) {
            override fun getTextBlocks(): List<Text.TextBlock> {
                throw RuntimeException("Mapper simulation failure")
            }
        }
        controllableTask.completeWithSuccess(throwingVisionText)

        val result = TextRecognitionHelper.processMlKitRecognition(
            options = TextRecognizerOptions.DEFAULT_OPTIONS,
            bitmap = null,
            engineId = "mlkit_latin",
            documentLanguage = "en"
        )

        assertTrue("Expected Failure result on mapper exception, got: $result", result is EngineRunResult.Failure)
        assertEquals("Client must be closed on mapper exception", 1, fakeRecognizer.closeCallCount.get())
    }

    // --- Test Doubles ---

    private class FakeTextRecognizer(
        private val onProcess: ((InputImage) -> Task<Text>)? = null
    ) : TextRecognizer {
        val closeCallCount = AtomicInteger(0)
        val processCallCount = AtomicInteger(0)

        override fun process(image: InputImage): Task<Text> {
            processCallCount.incrementAndGet()
            return onProcess?.invoke(image) ?: ControllableTask()
        }

        override fun process(image: com.google.android.odml.image.MlImage): Task<Text> {
            processCallCount.incrementAndGet()
            throw UnsupportedOperationException("MlImage is not supported in this test double")
        }

        override fun process(bitmap: Bitmap, rotationDegrees: Int): Task<Text> {
            processCallCount.incrementAndGet()
            throw UnsupportedOperationException("Bitmap process is not used directly")
        }

        override fun process(image: Image, rotationDegrees: Int): Task<Text> {
            processCallCount.incrementAndGet()
            throw UnsupportedOperationException("Image process is not used")
        }

        override fun process(image: Image, rotationDegrees: Int, matrix: Matrix): Task<Text> {
            processCallCount.incrementAndGet()
            throw UnsupportedOperationException("Image process is not used")
        }

        override fun process(byteBuffer: ByteBuffer, width: Int, height: Int, rotationDegrees: Int, format: Int): Task<Text> {
            processCallCount.incrementAndGet()
            throw UnsupportedOperationException("ByteBuffer process is not used")
        }

        override fun getDetectorType(): Int = Detector.TYPE_TEXT_RECOGNITION

        override fun close() {
            closeCallCount.incrementAndGet()
        }

        override fun getOptionalFeatures(): Array<Feature> = emptyArray()
    }

    private class ControllableTask<T> : Task<T>() {
        private var successListener: OnSuccessListener<in T>? = null
        private var failureListener: OnFailureListener? = null
        private var _isComplete = false
        private var _isSuccessful = false
        private var _result: T? = null
        private var _exception: Exception? = null

        override fun addOnSuccessListener(listener: OnSuccessListener<in T>): Task<T> {
            this.successListener = listener
            if (_isComplete && _isSuccessful && _result != null) {
                listener.onSuccess(_result!!)
            }
            return this
        }

        override fun addOnSuccessListener(activity: Activity, listener: OnSuccessListener<in T>): Task<T> =
            addOnSuccessListener(listener)

        override fun addOnSuccessListener(executor: Executor, listener: OnSuccessListener<in T>): Task<T> =
            addOnSuccessListener(listener)

        override fun addOnFailureListener(listener: OnFailureListener): Task<T> {
            this.failureListener = listener
            if (_isComplete && !_isSuccessful && _exception != null) {
                listener.onFailure(_exception!!)
            }
            return this
        }

        override fun addOnFailureListener(activity: Activity, listener: OnFailureListener): Task<T> =
            addOnFailureListener(listener)

        override fun addOnFailureListener(executor: Executor, listener: OnFailureListener): Task<T> =
            addOnFailureListener(listener)

        override fun isComplete(): Boolean = _isComplete
        override fun isSuccessful(): Boolean = _isSuccessful
        override fun isCanceled(): Boolean = false
        override fun getResult(): T = _result ?: throw IllegalStateException("Task is not yet completed")
        override fun <X : Throwable?> getResult(exceptionType: Class<X>): T = getResult()
        override fun getException(): java.lang.Exception? = _exception

        fun completeWithSuccess(result: T) {
            _isComplete = true
            _isSuccessful = true
            _result = result
            successListener?.onSuccess(result)
        }

        fun completeWithFailure(exception: Exception) {
            _isComplete = true
            _isSuccessful = false
            _exception = exception
            failureListener?.onFailure(exception)
        }
    }
}
