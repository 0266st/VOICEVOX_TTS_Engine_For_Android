package dev.ztssst.voicevox_tts

import android.os.SystemClock
import android.util.Log
import jp.hiroshiba.voicevoxcore.AudioFeature
import jp.hiroshiba.voicevoxcore.blocking.Onnxruntime
import jp.hiroshiba.voicevoxcore.blocking.OpenJtalk
import jp.hiroshiba.voicevoxcore.blocking.Synthesizer
import jp.hiroshiba.voicevoxcore.blocking.VoiceModelFile
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToLong

class VoicevoxTTSEngine(voiceModelPath: String, openJtalkDictPath: String){
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

    // 合成は別スレッドで行う。再生に渡す側は再生の進み具合に合わせて待たされるので、同じスレッドだと合成まで止まってしまう
    private val renderExecutor = Executors.newSingleThreadExecutor()

    // 端末の合成の速さ。発話をまたいで覚えておく
    private val costEstimator = CostEstimator()

    /**
     * 音声合成をしながら、できた分から16-bit PCM（24kHz, モノラル）を [onChunk] に渡す。
     *
     * 全体を合成し終わるのを待たずに再生を始められる。ただし合成が再生より遅い端末では、
     * できた区間をすぐ渡すと途中で途切れてしまうので、最後まで途切れずに続けられる時刻になるまで渡すのを待つ。
     * [onChunk] は呼び出したスレッドで呼ばれる。false を返したら、そこで打ち切る。
     * 打ち切らずに最後まで渡せたら true を返す。
     */
    fun synthesizeStreaming(text: String, styleId: Int = DEFAULT_STYLE_ID, onChunk: (ByteArray) -> Boolean): Boolean {
        val queue = LinkedBlockingQueue<Any>() // Chunk / StartAt / Done / 失敗を示す Throwable
        val cancelled = AtomicBoolean(false)
        val firstDeliveredAt = AtomicLong(0) // 渡し始めた時刻。合成のほうが、区間の分け方を決めるのに使う
        renderExecutor.execute {
            try {
                renderSegments(text, styleId, cancelled, firstDeliveredAt, queue)
                queue.put(Done)
            } catch (e: Throwable) {
                queue.put(e)
            }
        }

        val pending = ArrayDeque<Chunk>() // 合成できたが、まだ渡していない区間
        var startAt = Long.MAX_VALUE // この時刻になったら渡し始める。合成が進むまでは分からない
        var started = false
        var deliveredSeconds = 0.0
        try {
            while (true) {
                val item = if (started || startAt == Long.MAX_VALUE) {
                    queue.take()
                } else {
                    queue.poll(maxOf(0L, startAt - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS)
                }
                when (item) {
                    null -> started = true // 渡し始める時刻になった
                    is Chunk -> pending.add(item)
                    is StartAt -> startAt = item.time
                    is Throwable -> throw item
                    Done -> started = true // 全部できた
                }
                if (!started && SystemClock.elapsedRealtime() >= startAt) started = true
                if (!started) continue

                while (pending.isNotEmpty()) {
                    val chunk = pending.removeFirst()
                    firstDeliveredAt.compareAndSet(0, SystemClock.elapsedRealtime())
                    // 渡した音声を再生し終えたあとに次ができていたら、そこで音が途切れている
                    val gapMs = (chunk.readyAt - firstDeliveredAt.get()) - (deliveredSeconds * 1000).toLong()
                    if (gapMs > 0) Log.w("${TAG}->Synthesizer", "Playback gap of about ${gapMs}ms")
                    deliveredSeconds += chunk.pcm.audioSeconds()
                    if (!onChunk(chunk.pcm)) {
                        Log.d("${TAG}->Synthesizer", "Synthesis cancelled")
                        return false
                    }
                }
                if (item === Done) return true
            }
        } finally {
            cancelled.set(true) // 打ち切ったときや失敗したときに、合成のほうも止める
        }
    }

    private class Chunk(val pcm: ByteArray, val readyAt: Long)

    /** 渡し始めてよい時刻（[SystemClock.elapsedRealtime] の値）。合成が進むたびに見積もり直して送られてくる */
    private class StartAt(val time: Long)

    private object Done

    /**
     * 音声を区間ごとに合成して、できたものから [queue] に入れる。
     *
     * 区間の長さは、端末の合成の速さの実測から、区間ごとに決め直す。あわせて、途切れずに再生を始められる時刻も見積もって送る。
     */
    private fun renderSegments(
        text: String,
        styleId: Int,
        cancelled: AtomicBoolean,
        firstDeliveredAt: AtomicLong,
        queue: LinkedBlockingQueue<Any>,
    ) {
        Log.d("${TAG}->Synthesizer", "Synthesis started (isGPUMode = ${synthesizer.isGpuMode})")
        val startTime = SystemClock.elapsedRealtime()

        // 音声特徴量（中間表現）を全体で1回だけ作り、そこから区間ごとに波形を生成する
        val audioQuery = synthesizer.createAudioQuery(text, styleId)
        val audioFeature = synthesizer.createAudioFeature(audioQuery, styleId).perform()
        val totalFrames = audioFeature.frameLength
        Log.d("${TAG}->Synthesizer", "AudioFeature created: frames = $totalFrames, elapsed = ${SystemClock.elapsedRealtime() - startTime}ms")

        val minFrames = (MIN_SEGMENT_SECONDS * AudioFeature.FRAME_RATE).roundToLong()
        var doneFrames = 0L
        var renderMs = 0L
        while (doneFrames < totalFrames) {
            if (cancelled.get()) return

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
            if (deliveredAt == 0L && doneFrames > 0) queue.put(StartAt(startTime + (plan.startSeconds * 1000).toLong()))

            var frames = (plan.segmentSeconds.first() * AudioFeature.FRAME_RATE).roundToLong().coerceIn(1, totalFrames - doneFrames)
            if (totalFrames - doneFrames - frames < minFrames) frames = totalFrames - doneFrames // 端数は最後の区間に含める
            val range = FrameRange(doneFrames, doneFrames + frames)

            val renderStart = SystemClock.elapsedRealtime()
            val pcm = synthesizer.render(audioFeature, range.startInclusive, range.endExclusive)
            val readyAt = SystemClock.elapsedRealtime()
            val ms = readyAt - renderStart
            renderMs += ms
            costEstimator.observe(pcm.audioSeconds(), ms / 1000.0)
            queue.put(Chunk(pcm, readyAt))
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
    }
}
