package dev.ztssst.voicevox_tts

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Debug
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future

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

    // 解放を待つ時間の上限。これを過ぎたら、待たずに次の作成へ進む（エンジンが、どこかに参照されたままのとき）
    private const val NATIVE_RELEASE_TIMEOUT_MILLIS = 5_000L

    // エンジンの作成、解放、解放のタイマーは、すべて、この1本のスレッドで、登録した順に処理する。
    // 解放のあとに登録した作成は、解放が終わってから始まるので、古いエンジン（約230MB）と新しいエンジンが、
    // 同時に生きて、メモリが足りなくなることがない。モデルのコピーや辞書の解凍に時間がかかるので、バックグラウンドで行う
    private val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "voicevox-engine").apply { isDaemon = true } }
    private val scheduler = ExecutorTaskScheduler(executor)
    private val releaseTracker = ReleaseTracker()
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

    /**
     * エンジンの初期化に失敗していたら、作り直して返す。[acquire] 済みの使う側が、失敗のあとで、もう一度試すためのもの。
     * 使う側の数は変わらないので、[release] は、[acquire] の回数だけ呼べばよい
     */
    @Synchronized
    fun refresh(): Future<VoicevoxTTSEngine> = checkNotNull(shared) { "refresh() was called before acquire()" }.refresh()

    private fun newResource(appContext: Context): SharedResource<VoicevoxTTSEngine> {
        val resource = SharedResource(
            create = { executor.submit<VoicevoxTTSEngine> { createEngine(appContext) } },
            dispose = { engine -> dispose(engine) },
            idleMillis = IDLE_RELEASE_MILLIS,
            scheduler = scheduler,
            afterDispose = { reclaimMemory() },
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

    private fun dispose(engine: VoicevoxTTSEngine) {
        // エンジンのネイティブの資源は、Synthesizer などのファイナライザで解放される（close() を持たない）。
        // ここでは参照を手放すだけで（この呼び出しが終わると、エンジンは、どこからも参照されない）、
        // 解放を見張っておく。実際の解放は、このあとの reclaimMemory() で促して、見届ける
        Log.d(TAG, "Releasing the engine")
        releaseTracker.watch { engine.trackNativeRelease(it) }
    }

    /**
     * 参照がなくなったエンジンのネイティブのメモリが、実際に解放されるまで、GC を促しながら待つ。
     * 次の作成は、これが終わってから始まる。エンジンが解放されない（どこかが参照している）ときは、時間切れで先へ進む
     */
    private fun reclaimMemory() {
        val startedAt = System.nanoTime()
        val before = Debug.getNativeHeapAllocatedSize()
        val released = releaseTracker.awaitReleased(NATIVE_RELEASE_TIMEOUT_MILLIS)
        Log.d(
            TAG,
            "Reclaimed the engine's memory (released = %b, waited %d ms): native heap %d MB -> %d MB"
                .format(released, (System.nanoTime() - startedAt) / 1_000_000, before shr 20, Debug.getNativeHeapAllocatedSize() shr 20),
        )
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
        context.resources.openRawResource(R.raw.open_jtalk_dict).use { unzipSafely(it, filesDir) }
        stamp.writeText(installed)
        Log.d(TAG, "resources copied to $filesDir")
    }
}
