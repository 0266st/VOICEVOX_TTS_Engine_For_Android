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
        renderExecutor.execute {
            try {
                renderSegments(text, styleId, cancelled, queue)
                queue.put(Done)
            } catch (e: Throwable) {
                queue.put(e)
            }
        }

        val pending = ArrayDeque<Chunk>() // 合成できたが、まだ渡していない区間
        var startAt = Long.MAX_VALUE // この時刻になったら渡し始める
        var started = false
        var firstDeliveredAt = 0L
        var deliveredSeconds = 0.0
        try {
            while (true) {
                val waitMs = if (started) Long.MAX_VALUE else maxOf(0L, startAt - SystemClock.elapsedRealtime())
                val item = if (waitMs == Long.MAX_VALUE) queue.take() else queue.poll(waitMs, TimeUnit.MILLISECONDS)
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
                    val now = SystemClock.elapsedRealtime()
                    if (firstDeliveredAt == 0L) firstDeliveredAt = now
                    // 渡した音声を再生し終えたあとに次ができていたら、そこで音が途切れている
                    val gapMs = (chunk.readyAt - firstDeliveredAt) - (deliveredSeconds * 1000).toLong()
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

    /** 音声を区間ごとに合成して、できたものから [queue] に入れる。あわせて、途切れずに再生を始められる時刻を見積もって送る */
    private fun renderSegments(text: String, styleId: Int, cancelled: AtomicBoolean, queue: LinkedBlockingQueue<Any>) {
        Log.d("${TAG}->Synthesizer", "Synthesis started (isGPUMode = ${synthesizer.isGpuMode})")
        val startTime = SystemClock.elapsedRealtime()

        // 音声特徴量（中間表現）を全体で1回だけ作り、そこから区間ごとに波形を生成する
        val audioQuery = synthesizer.createAudioQuery(text, styleId)
        val audioFeature = synthesizer.createAudioFeature(audioQuery, styleId).perform()
        Log.d("${TAG}->Synthesizer", "AudioFeature created: frames = ${audioFeature.frameLength}, elapsed = ${SystemClock.elapsedRealtime() - startTime}ms")

        val segments = planSegments(audioFeature.frameLength, FIRST_SEGMENT_SECONDS, SEGMENT_SECONDS, AudioFeature.FRAME_RATE).toList()
        var secondsPerFrame = 0.0 // 実測した、1フレーム分の合成にかかる時間（区間の固定費を含めた見積もり）の最大値
        var renderedSeconds = 0.0
        var renderMs = 0L

        for ((index, range) in segments.withIndex()) {
            if (cancelled.get()) return
            val renderStart = SystemClock.elapsedRealtime()
            val pcm = synthesizer.render(audioFeature, range.startInclusive, range.endExclusive)
            val readyAt = SystemClock.elapsedRealtime()
            val ms = readyAt - renderStart
            renderMs += ms
            renderedSeconds += pcm.audioSeconds()
            secondsPerFrame = maxOf(secondsPerFrame, ms / 1000.0 / (range.frames + RENDER_OVERHEAD_FRAMES))
            queue.put(Chunk(pcm, readyAt))
            Log.d("${TAG}->Synthesizer", "Rendered $range: %.2fs of audio in ${ms}ms, elapsed = ${readyAt - startTime}ms".format(pcm.audioSeconds()))

            // 残りの区間の合成時間を実測から見積もって、途切れずに再生を始められる時刻を求める
            val remaining = segments.drop(index + 1).map {
                PlannedSegment(
                    audioSeconds = it.frames / AudioFeature.FRAME_RATE,
                    renderSeconds = secondsPerFrame * (it.frames + RENDER_OVERHEAD_FRAMES) * RENDER_TIME_SAFETY_FACTOR,
                )
            }
            val delaySeconds = startDelaySeconds(renderedSeconds, remaining)
            queue.put(StartAt(readyAt + (delaySeconds * 1000).toLong()))
        }
        Log.d(
            "${TAG}->Synthesizer",
            "Synthesis finished: %.2fs of audio, render = ${renderMs}ms (x%.2f of real time), elapsed = ${SystemClock.elapsedRealtime() - startTime}ms"
                .format(renderedSeconds, renderMs / 1000.0 / renderedSeconds),
        )
    }

    private fun ByteArray.audioSeconds() = size / 2.0 / SAMPLE_RATE

    private val FrameRange.frames get() = endExclusive - startInclusive

    companion object {
        const val SAMPLE_RATE = 24000
        const val DEFAULT_STYLE_ID = 14 // 冥鳴ひまり（ノーマル）

        // 最初の区間だけ短くして、最初の音が鳴るまでの時間を縮める。以降はCOREの既定（3秒）に合わせる
        const val FIRST_SEGMENT_SECONDS = 1.0
        const val SEGMENT_SECONDS = 3.0

        // render にかかる時間は「区間のフレーム数 + この値」にほぼ比例する（実測。前後の余白 14 * 2 フレームと、1回ごとの固定費）
        private const val RENDER_OVERHEAD_FRAMES = 56

        // 合成時間の見積もりが外れても途切れにくいように、少し長めに見積もる
        private const val RENDER_TIME_SAFETY_FACTOR = 1.1
    }
}
