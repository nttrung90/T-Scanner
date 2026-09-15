package com.tscanner.app

import com.tscanner.app.utils.SafeFileWriter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

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
}
