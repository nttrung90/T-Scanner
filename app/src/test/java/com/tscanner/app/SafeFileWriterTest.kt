package com.tscanner.app

import com.tscanner.app.utils.SafeFileWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class SafeFileWriterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testWriteSafely_successfulCreation() = runBlocking {
        val dest = File(tempFolder.root, "output.txt")
        val result = SafeFileWriter.writeSafely(dest) { tempFile ->
            tempFile.writeText("Hello T-Scanner")
            true
        }

        assertTrue(result is SafeFileWriter.Result.Success)
        assertTrue(dest.exists())
        assertEquals("Hello T-Scanner", dest.readText())
    }

    @Test
    fun testWriteSafely_writerFails_preservesOriginalFile() = runBlocking {
        // Original file exists with good data
        val dest = File(tempFolder.root, "important.txt")
        dest.writeText("Good original data")

        val result = SafeFileWriter.writeSafely(dest) { tempFile ->
            tempFile.writeText("Corrupted partial data")
            // Writer fails mid-way
            false
        }

        assertTrue(result is SafeFileWriter.Result.Error)
        // Original data must NOT be truncated or replaced!
        assertTrue(dest.exists())
        assertEquals("Good original data", dest.readText())
    }

    @Test
    fun testWriteSafely_validatorFails_preservesOriginalFile() = runBlocking {
        val dest = File(tempFolder.root, "verified.txt")
        dest.writeText("Initial valid content")

        val result = SafeFileWriter.writeSafely(
            destinationFile = dest,
            validator = { file ->
                // Custom validation rejects content
                file.readText().contains("VALID")
            }
        ) { tempFile ->
            tempFile.writeText("CORRUPTED DATA")
            true
        }

        assertTrue(result is SafeFileWriter.Result.Error)
        assertTrue(dest.exists())
        assertEquals("Initial valid content", dest.readText())
    }

    @Test
    fun testWriteSafely_successfulAtomicOverwrite() = runBlocking {
        val dest = File(tempFolder.root, "versioned.txt")
        dest.writeText("Version 1")

        val result = SafeFileWriter.writeSafely(dest) { tempFile ->
            tempFile.writeText("Version 2")
            true
        }

        assertTrue(result is SafeFileWriter.Result.Success)
        assertEquals("Version 2", dest.readText())
    }

    @Test
    fun testWriteSafely_commitThrowsIOException_preservesOriginalFile() = runBlocking {
        // T01: Destination file exists with good data A
        val dest = File(tempFolder.root, "critical_doc.txt")
        dest.writeText("Version A - Good Document")

        // Injected strategy simulates IOException during commit (e.g. disk failure / handle locked)
        val failingStrategy = SafeFileWriter.FileCommitStrategy { _, _ ->
            throw IOException("Simulated disk error during commit")
        }

        val result = SafeFileWriter.writeSafely(
            destinationFile = dest,
            commitStrategy = failingStrategy
        ) { tempFile ->
            tempFile.writeText("Version B - New Data")
            true
        }

        // Commit failed: original file MUST NOT be deleted or overwritten
        assertTrue(result is SafeFileWriter.Result.Error)
        assertTrue(dest.exists())
        assertEquals("Version A - Good Document", dest.readText())

        // Ensure temp files are cleaned up
        val tempFiles = tempFolder.root.listFiles { _, name -> name.startsWith("safe_tmp_") }
        assertTrue(tempFiles.isNullOrEmpty())
    }

    @Test
    fun testWriteSafely_commitReturnsFalse_preservesOriginalFile() = runBlocking {
        // T01: Destination file exists with good data A
        val dest = File(tempFolder.root, "critical_doc2.txt")
        dest.writeText("Version A - Good Document")

        val falseStrategy = SafeFileWriter.FileCommitStrategy { _, _ ->
            false
        }

        val result = SafeFileWriter.writeSafely(
            destinationFile = dest,
            commitStrategy = falseStrategy
        ) { tempFile ->
            tempFile.writeText("Version B - New Data")
            true
        }

        assertTrue(result is SafeFileWriter.Result.Error)
        assertTrue(dest.exists())
        assertEquals("Version A - Good Document", dest.readText())
    }

    @Test
    fun testWriteSafely_atomicMoveNotSupported_fallbackReplaceSucceeds() = runBlocking {
        val dest = File(tempFolder.root, "fallback_doc.txt")
        dest.writeText("Version A - Old Content")

        // Strategy simulates AtomicMoveNotSupportedException on atomic move,
        // then falls back to non-atomic REPLACE_EXISTING
        val fallbackStrategy = SafeFileWriter.FileCommitStrategy { sourceTemp, destination ->
            try {
                throw AtomicMoveNotSupportedException(
                    sourceTemp.absolutePath,
                    destination.absolutePath,
                    "Atomic move unsupported on filesystem"
                )
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(
                    sourceTemp.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
                true
            }
        }

        val result = SafeFileWriter.writeSafely(
            destinationFile = dest,
            commitStrategy = fallbackStrategy
        ) { tempFile ->
            tempFile.writeText("Version B - Fallback Success")
            true
        }

        assertTrue(result is SafeFileWriter.Result.Success)
        assertTrue(dest.exists())
        assertEquals("Version B - Fallback Success", dest.readText())
    }

    @Test
    fun testWriteSafely_atomicMoveNotSupported_andFallbackFails_preservesOriginalFile() = runBlocking {
        val dest = File(tempFolder.root, "fallback_fail_doc.txt")
        dest.writeText("Version A - Must Survive")

        // Strategy simulates AtomicMoveNotSupportedException AND fallback also throws IOException
        val failingFallbackStrategy = SafeFileWriter.FileCommitStrategy { _, destination ->
            try {
                throw AtomicMoveNotSupportedException(
                    "source",
                    destination.absolutePath,
                    "Atomic move unsupported"
                )
            } catch (e: AtomicMoveNotSupportedException) {
                throw IOException("Fallback move also failed")
            }
        }

        val result = SafeFileWriter.writeSafely(
            destinationFile = dest,
            commitStrategy = failingFallbackStrategy
        ) { tempFile ->
            tempFile.writeText("Version B - Doomed Temp Data")
            true
        }

        assertTrue(result is SafeFileWriter.Result.Error)
        assertTrue(dest.exists())
        assertEquals("Version A - Must Survive", dest.readText())
    }

    @Test(expected = CancellationException::class)
    fun testWriteSafely_cancellationPropagates(): Unit = runBlocking {
        val dest = File(tempFolder.root, "cancel_doc.txt")
        SafeFileWriter.writeSafely(dest) { _ ->
            throw CancellationException("Simulated job cancellation")
        }
    }
}
