package dev.ztssst.voicevox_tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipExtractorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun zip(vararg entries: Pair<String, String?>): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { out ->
            for ((name, content) in entries) {
                out.putNextEntry(ZipEntry(name))
                if (content != null) out.write(content.toByteArray())
                out.closeEntry()
            }
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }

    @Test
    fun extractsDirectoriesAndFiles() {
        val dest = tmp.newFolder("dict")
        unzipSafely(zip("open_jtalk_dict/" to null, "open_jtalk_dict/sys.dic" to "abc", "open_jtalk_dict/sub/unk.dic" to "xyz"), dest)
        assertEquals("abc", File(dest, "open_jtalk_dict/sys.dic").readText())
        assertEquals("xyz", File(dest, "open_jtalk_dict/sub/unk.dic").readText())
    }

    @Test
    fun rejectsAnEntryThatEscapesWithDotDot() {
        val dest = tmp.newFolder("dict")
        val outside = File(tmp.root, "evil.txt")
        try {
            unzipSafely(zip("../evil.txt" to "pwned"), dest)
            throw AssertionError("IOException was expected")
        } catch (_: IOException) {
        }
        assertFalse("展開先の外に、ファイルが作られている", outside.exists())
    }

    @Test
    fun rejectsAnEntryThatEscapesInTheMiddleOfThePath() {
        val dest = tmp.newFolder("dict")
        try {
            unzipSafely(zip("a/../../evil.txt" to "pwned"), dest)
            throw AssertionError("IOException was expected")
        } catch (_: IOException) {
        }
        assertFalse(File(tmp.root, "evil.txt").exists())
    }

    @Test
    fun rejectsASiblingDirectoryWhoseNameStartsWithTheDestinationName() {
        // 展開先が「dict」のとき、「dict-evil」は、名前の先頭が同じでも、外側
        val dest = tmp.newFolder("dict")
        try {
            unzipSafely(zip("../dict-evil/x.txt" to "pwned"), dest)
            throw AssertionError("IOException was expected")
        } catch (_: IOException) {
        }
        assertFalse(File(tmp.root, "dict-evil/x.txt").exists())
    }

    @Test
    fun anAbsolutePathIsTreatedAsRelativeToTheDestination() {
        // File(parent, "/x") は、parent の下の x になる。外には出ない
        val dest = tmp.newFolder("dict")
        unzipSafely(zip("/abs.txt" to "ok"), dest)
        assertTrue(File(dest, "abs.txt").exists())
    }
}
