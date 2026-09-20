package dev.ztssst.voicevox_tts

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * [VoicevoxTTSEngine] を、サービスが作り直されても、同じプロセスのあいだは使い回す。
 *
 * TextToSpeechService は、使う側（アプリ）が切断するたびに作り直される。エンジンの初期化（モデルの読み込み）に
 * 約1秒かかるので、そのたびにやり直すと、次の最初の音が遅くなる。一方、エンジンは約230MBのメモリを使うので、
 * 使う側がいなくなって [IDLE_RELEASE_MILLIS] たったときと、システムがメモリ不足を通知したときは解放する。
 */
object VoicevoxEngineProvider {
    private const val TAG = "VoicevoxEngineProvider"
    private const val IDLE_RELEASE_MILLIS = 5 * 60 * 1000L
    private const val GC_DELAY_MILLIS = 1000L

    // モデルのコピーや辞書の解凍に時間がかかるので、作るのはバックグラウンドで行う
    private val initExecutor = Executors.newSingleThreadExecutor { Thread(it, "voicevox-engine-init").apply { isDaemon = true } }
    private val scheduler = ExecutorTaskScheduler(
        Executors.newSingleThreadScheduledExecutor { Thread(it, "voicevox-engine-release").apply { isDaemon = true } },
    )
    private var shared: SharedResource<VoicevoxTTSEngine>? = null

    /** エンジンを使い始める。使い終わったら [release] を呼ぶ。エンジンは初期化が終わるまで、[Future] の中で待つ */
    @Synchronized
    fun acquire(context: Context): Future<VoicevoxTTSEngine> {
        val resource = shared ?: newResource(context.applicationContext).also { shared = it }
        return resource.acquire()
    }

    @Synchronized
    fun release() {
        shared?.release()
    }

    private fun newResource(appContext: Context): SharedResource<VoicevoxTTSEngine> {
        val resource = SharedResource(
            create = { initExecutor.submit<VoicevoxTTSEngine> { createEngine(appContext) } },
            dispose = { engine -> dispose(engine) },
            idleMillis = IDLE_RELEASE_MILLIS,
            scheduler = scheduler,
        )
        appContext.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                Log.d(TAG, "onTrimMemory: level = $level")
                // TRIM_MEMORY_BACKGROUND（40）は、メモリに余裕があっても、プロセスがキャッシュに回るたびに届くので、
                // それでは使い回す意味がない。MODERATE（60）以上は、システムのメモリが実際に足りなくなっている
                if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) resource.releaseIfIdle()
            }

            override fun onLowMemory() {
                Log.d(TAG, "onLowMemory")
                resource.releaseIfIdle()
            }

            override fun onConfigurationChanged(newConfig: Configuration) {}
        })
        return resource
    }

    private fun createEngine(context: Context): VoicevoxTTSEngine {
        prepareResources(context)
        val filesDir = context.filesDir
        return VoicevoxTTSEngine(File(filesDir, "model.vvm").absolutePath, File(filesDir, "open_jtalk_dict").absolutePath)
            .also { Log.d(TAG, "Initialization Finished") }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun dispose(engine: VoicevoxTTSEngine) {
        // Synthesizer などは close() を持たず、GC のときのファイナライザでネイティブのメモリを解放する。
        // 参照を手放したので、GCを促して、メモリを早く返す。この呼び出しが終わるまでは、呼び出し元がまだ参照しているので、少し待つ
        Log.d(TAG, "Releasing the engine")
        scheduler.schedule(GC_DELAY_MILLIS) {
            System.gc()
            Log.d(TAG, "Requested a GC to free the engine's native memory")
        }
    }

    /** res/raw のモデルと辞書を filesDir に展開する。アプリが更新されたときだけやり直す */
    private fun prepareResources(context: Context) {
        val filesDir = context.filesDir
        val stamp = File(filesDir, "resources.stamp")
        val installed = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        if (stamp.exists() && stamp.readText() == installed) {
            Log.d(TAG, "resources are up to date, skipping copy")
            return
        }
        stamp.delete()
        context.resources.openRawResource(R.raw.model).use { input ->
            File(filesDir, "model.vvm").outputStream().use { input.copyTo(it) }
        }
        val dictDir = File(filesDir, "open_jtalk_dict")
        dictDir.deleteRecursively()
        context.resources.openRawResource(R.raw.open_jtalk_dict).use { unzip(it, filesDir) }
        stamp.writeText(installed)
        Log.d(TAG, "resources copied to $filesDir")
    }

    private fun unzip(input: InputStream, destDir: File) {
        ZipInputStream(input).use { zipInputStream ->
            var zipEntry: ZipEntry? = zipInputStream.nextEntry
            while (zipEntry != null) {
                val newFile = File(destDir, zipEntry.name)
                if (zipEntry.isDirectory) {
                    newFile.mkdirs()
                } else {
                    newFile.parentFile?.mkdirs()
                    newFile.outputStream().use { zipInputStream.copyTo(it) }
                }
                zipEntry = zipInputStream.nextEntry
            }
        }
    }
}
