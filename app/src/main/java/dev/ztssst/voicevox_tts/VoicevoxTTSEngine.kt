package dev.ztssst.voicevox_tts

import android.os.SystemClock
import android.util.Log
import jp.hiroshiba.voicevoxcore.AudioFeature
import jp.hiroshiba.voicevoxcore.blocking.Onnxruntime
import jp.hiroshiba.voicevoxcore.blocking.OpenJtalk
import jp.hiroshiba.voicevoxcore.blocking.Synthesizer
import jp.hiroshiba.voicevoxcore.blocking.VoiceModelFile
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToLong

class VoicevoxTTSEngine(voiceModelPath: String, openJtalkDictPath: String) : AutoCloseable {
    private val synthesizer: Synthesizer
    @Suppress("PrivatePropertyName")
    private val TAG = "VoicevoxTTSEngine"

    init {
        // jniLibs に同梱した libvoicevox_onnxruntime.so を読み込む
        val ort = Onnxruntime.loadOnce().perform()
        val jtalk = OpenJtalk(openJtalkDictPath)
        val synthesizer = Synthesizer.builder(ort, jtalk).build()
        VoiceModelFile(voiceModelPath).use { synthesizer.loadVoiceModel(it).perform() }
        this.synthesizer = synthesizer
        Log.d(TAG, "VoicevoxTTSEngine Initialized")
    }

    // 合成は別スレッドで行う。再生に渡す側は再生の進み具合に合わせて待たされるので、同じスレッドだと合成まで止まってしまう。
    // 使われなくなったエンジンにスレッドが残らないよう、デーモンスレッドにして、30秒使われなければ、スレッドを終わらせる
    // （close() を呼ばれなかった場合の保険。呼ばれたら、すぐに止める）
    private val renderExecutor = ThreadPoolExecutor(
        1, 1, RENDER_THREAD_IDLE_SECONDS, TimeUnit.SECONDS, LinkedBlockingQueue(),
    ) { Thread(it, "voicevox-render").apply { isDaemon = true } }.apply { allowCoreThreadTimeOut(true) }

    // 端末の合成の速さ。発話をまたいで覚えておく
    private val costEstimator = CostEstimator()

    /**
     * 音声合成をしながら、できた分から16-bit PCM（24kHz, モノラル）を [onChunk] に渡す。
     *
     * 全体を合成し終わるのを待たずに再生を始められる。ただし合成が再生より遅い端末では、
     * できた区間をすぐ渡すと途中で途切れてしまうので、最後まで途切れずに続けられる時刻になるまで渡すのを待つ。
     * [onChunk] は呼び出したスレッドで呼ばれる。false を返したら、そこで打ち切る。
     *
     * [token] を止める（別のスレッドの `onStop()` から）と、渡し始めるのを待っているあいだでも、すぐに打ち切る。
     * 合成のほうも、進行中の1区間が終わったところで止まる。
     *
     * 合成できた区間は、再生に渡すまで、キューに溜まる。入力は、フレームワークが4000文字に制限している
     * （`TextToSpeech.getMaxSpeechInputLength()`）ので、溜まる PCM は、多くても数十MB（1秒あたり48KB）で、
     * 実際には、合成の速さが再生の0.75〜1.15倍なので、溜まるのは、その差だけ。そのため、キューは、あえて有界にしない。
     *
     * 打ち切らずに最後まで渡せたら true を返す。
     */
    fun synthesizeStreaming(
        text: String,
        styleId: Int = DEFAULT_STYLE_ID,
        token: CancellationToken = CancellationToken(),
        onChunk: (ByteArray) -> Boolean,
    ): Boolean {
        val queue = LinkedBlockingQueue<DeliveryEvent>()
        val firstDeliveredAt = AtomicLong(0) // 渡し始めた時刻。合成のほうが、区間の分け方を決めるのに使う
        token.onCancel { queue.offer(DeliveryEvent.Cancelled) } // 渡し始めるのを待っているあいだも、止められるように
        renderExecutor.execute {
            try {
                renderSegments(text, styleId, token, firstDeliveredAt, queue)
                queue.put(DeliveryEvent.Done)
            } catch (e: Throwable) {
                queue.put(DeliveryEvent.Failed(e))
            }
        }
        try {
            val completed = deliverChunks(
                queue, token, SystemClock::elapsedRealtime, firstDeliveredAt, SAMPLE_RATE * 2, onChunk,
                onGap = { gapMs -> Log.w("${TAG}->Synthesizer", "Playback gap of about ${gapMs}ms") },
            )
            if (!completed) Log.d("${TAG}->Synthesizer", "Synthesis cancelled")
            return completed
        } finally {
            token.cancel() // 打ち切ったときや失敗したときに、合成のほうも止める
        }
    }

    /** 合成用のスレッドを止める。使い終わったエンジンは、これを呼んでから手放す（ネイティブの資源は、GC のときに解放される） */
    override fun close() {
        renderExecutor.shutdown()
    }

    /**
     * 音声を区間ごとに合成して、できたものから [queue] に入れる。
     *
     * 区間の長さは、端末の合成の速さの実測から、区間ごとに決め直す。あわせて、途切れずに再生を始められる時刻も見積もって送る。
     */
    private fun renderSegments(
        text: String,
        styleId: Int,
        token: CancellationToken,
        firstDeliveredAt: AtomicLong,
        queue: LinkedBlockingQueue<DeliveryEvent>,
    ) {
        if (token.isCancelled) return
        Log.d("${TAG}->Synthesizer", "Synthesis started (isGPUMode = ${synthesizer.isGpuMode})")
        val startTime = SystemClock.elapsedRealtime()

        // 音声特徴量（中間表現）を全体で1回だけ作り、そこから区間ごとに波形を生成する
        val audioQuery = synthesizer.createAudioQuery(text, styleId)
        val audioFeature = synthesizer.createAudioFeature(audioQuery, styleId).perform()
        if (token.isCancelled) return
        val totalFrames = audioFeature.frameLength
        Log.d("${TAG}->Synthesizer", "AudioFeature created: frames = $totalFrames, elapsed = ${SystemClock.elapsedRealtime() - startTime}ms")

        var doneFrames = 0L
        var renderMs = 0L
        while (doneFrames < totalFrames) {
            if (token.isCancelled) return

            // 残りの区間の分け方を、いまの見積もりで決め直す
            val elapsedSeconds = (SystemClock.elapsedRealtime() - startTime) / 1000.0
            val deliveredAt = firstDeliveredAt.get()
            val plan = planSegments(
                remainingSeconds = (totalFrames - doneFrames) / AudioFeature.FRAME_RATE,
                model = costEstimator.model(),
                nowSeconds = elapsedSeconds,
                bufferedSeconds = doneFrames / AudioFeature.FRAME_RATE,
                fixedStartSeconds = if (deliveredAt != 0L) (deliveredAt - startTime) / 1000.0 else null,
            )
            // 最初の区間ができるまでは、端末の速さが分からないので、渡し始める時刻は決めない
            if (deliveredAt == 0L && doneFrames > 0) queue.put(DeliveryEvent.StartAt(startTime + (plan.startSeconds * 1000).toLong()))

            // 計画の区間は、短すぎる端数を残さず、上限も守っている。フレーム数に丸めるだけで、ここで区間を足したり、削ったりしない
            val frames = (plan.segmentSeconds.first() * AudioFeature.FRAME_RATE).roundToLong().coerceIn(1, totalFrames - doneFrames)
            val range = FrameRange(doneFrames, doneFrames + frames)

            val renderStart = SystemClock.elapsedRealtime()
            val pcm = synthesizer.render(audioFeature, range.startInclusive, range.endExclusive)
            val readyAt = SystemClock.elapsedRealtime()
            val ms = readyAt - renderStart
            renderMs += ms
            costEstimator.observe(pcm.audioSeconds(), ms / 1000.0)
            queue.put(DeliveryEvent.Chunk(pcm, readyAt))
            doneFrames += frames
            Log.d(
                "${TAG}->Synthesizer",
                "Rendered $range: %.2fs of audio in ${ms}ms, elapsed = ${readyAt - startTime}ms, planned start = %.2fs, %d segments left"
                    .format(pcm.audioSeconds(), plan.startSeconds, plan.segmentSeconds.size - 1),
            )
        }
        val audioSeconds = totalFrames / AudioFeature.FRAME_RATE
        Log.d(
            "${TAG}->Synthesizer",
            "Synthesis finished: %.2fs of audio, render = ${renderMs}ms (x%.2f of real time), elapsed = ${SystemClock.elapsedRealtime() - startTime}ms"
                .format(audioSeconds, renderMs / 1000.0 / audioSeconds),
        )
    }

    private fun ByteArray.audioSeconds() = size / 2.0 / SAMPLE_RATE

    companion object {
        const val SAMPLE_RATE = 24000
        const val DEFAULT_STYLE_ID = 14 // 冥鳴ひまり（ノーマル）
        private const val RENDER_THREAD_IDLE_SECONDS = 30L
    }
}
