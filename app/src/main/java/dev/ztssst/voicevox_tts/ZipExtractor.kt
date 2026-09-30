package dev.ztssst.voicevox_tts

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * ZIP を [destDir] に展開する。
 *
 * エントリの名前が `../` などで [destDir] の外を指すとき（Zip Slip）は、そのエントリを書かずに [IOException] にする。
 * 展開するのはアプリに同梱した ZIP なので、外から入り込む想定ではないが、ZIP が壊れていたり、差し替わっていたりしても、
 * アプリの領域の外を書き換えないようにするための防御。
 */
fun unzipSafely(input: InputStream, destDir: File) {
    val root = destDir.canonicalFile
    ZipInputStream(input).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            val target = File(root, entry.name).canonicalFile
            // 「/dict」の下として、「/dict-evil」を通さないように、区切り文字まで含めて比べる
            if (target != root && !target.path.startsWith(root.path + File.separator)) {
                throw IOException("Zip entry is outside of the target directory: ${entry.name}")
            }
            if (entry.isDirectory) {
                target.mkdirs()
            } else {
                target.parentFile?.mkdirs()
                target.outputStream().use { zip.copyTo(it) }
            }
            entry = zip.nextEntry
        }
    }
}
