package dev.ztssst.voicevox_tts

import java.util.concurrent.BlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** 合成の側から、再生に渡す側へ送るもの */
sealed interface DeliveryEvent {
    /** 合成できた区間。[readyAt] は、できた時刻 */
    class Chunk(val pcm: ByteArray, val readyAt: Long) : DeliveryEvent

    /** 渡し始めてよい時刻。合成が進むたびに、見積もり直して送られてくる */
    class StartAt(val time: Long) : DeliveryEvent

    /** 全部できた */
    object Done : DeliveryEvent

    /** 合成に失敗した */
    class Failed(val error: Throwable) : DeliveryEvent

    /** 止められた。渡す側を、待ちから起こすために送る */
    object Cancelled : DeliveryEvent
}

/**
 * 進行中の合成を、別のスレッド（`onStop()`）から止めるための目印。1回の合成につき1つ作る。
 *
 * 登録した処理（[onCancel]）は、止められたときに、**ちょうど1回だけ**動く。登録と [cancel] が、別のスレッドから
 * ほぼ同時に来ても、2回動いたり、1回も動かなかったりしない（状態の確認と、処理の取り出しを、同じロックの中で行う）。
 */
class CancellationToken {
    private val lock = Any()
    private var cancelled = false
    private var listener: (() -> Unit)? = null

    val isCancelled: Boolean get() = synchronized(lock) { cancelled }

    /** 止める。どのスレッドからでも呼べる。2回目以降は、何もしない */
    fun cancel() {
        val action = synchronized(lock) {
            if (cancelled) return
            cancelled = true
            listener.also { listener = null } // 取り出して空にするので、この処理は、ここでしか動かない
        }
        action?.invoke() // ロックの外で呼ぶ（処理の中で、別のロックを取っても、詰まらない）
    }

    /** 止められたときに呼ぶ処理を登録する（1つだけ。あとから登録すると、置き換える）。すでに止められていれば、すぐ呼ぶ */
    fun onCancel(action: () -> Unit) {
        val alreadyCancelled = synchronized(lock) {
            if (!cancelled) listener = action
            cancelled
        }
        if (alreadyCancelled) action()
    }
}

/**
 * 合成の側が [queue] に入れたものを受け取って、渡してよい時刻になったら、[onChunk] に順に渡す。
 *
 * 合成が再生より遅い端末では、できた区間をすぐ渡すと、途中で途切れてしまうので、[DeliveryEvent.StartAt] の時刻まで待つ。
 * 待っているあいだも、[token] が止められれば、すぐに抜ける（[DeliveryEvent.Cancelled] が、待ちを起こす）。
 *
 * @param now 時刻（ミリ秒）を返す。[DeliveryEvent.StartAt] と [DeliveryEvent.Chunk.readyAt] は、この時計の値
 * @param firstDeliveredAt 最初に渡した時刻を書き込む（0 は「まだ」）。合成の側が、区間の分け方を決めるのに使う
 * @param onGap 渡した音声を再生し終えたあとに、次の区間ができていた（そこで音が途切れた）とき、途切れた時間（ミリ秒）を渡す
 * @return 最後まで渡したら true。[onChunk] が false を返したか、止められたら false
 * @throws Throwable 合成に失敗したとき（[DeliveryEvent.Failed] の中身）
 */
fun deliverChunks(
    queue: BlockingQueue<DeliveryEvent>,
    token: CancellationToken,
    now: () -> Long,
    firstDeliveredAt: AtomicLong,
    bytesPerSecond: Int,
    onChunk: (ByteArray) -> Boolean,
    onGap: (gapMillis: Long) -> Unit = {},
): Boolean {
    val pending = ArrayDeque<DeliveryEvent.Chunk>() // 合成できたが、まだ渡していない区間
    var startAt = Long.MAX_VALUE // この時刻になったら渡し始める。合成が進むまでは分からない
    var started = false
    var deliveredSeconds = 0.0
    while (true) {
        if (token.isCancelled) return false
        val event = if (started || startAt == Long.MAX_VALUE) {
            queue.take()
        } else {
            queue.poll(maxOf(0L, startAt - now()), TimeUnit.MILLISECONDS)
        }
        var done = false
        when (event) {
            null -> started = true // 渡し始める時刻になった
            is DeliveryEvent.Chunk -> pending.add(event)
            is DeliveryEvent.StartAt -> startAt = event.time
            is DeliveryEvent.Failed -> throw event.error
            DeliveryEvent.Done -> {
                started = true // 全部できた
                done = true
            }
            DeliveryEvent.Cancelled -> return false
        }
        if (!started && now() >= startAt) started = true
        if (!started) continue

        while (pending.isNotEmpty()) {
            if (token.isCancelled) return false
            val chunk = pending.removeFirst()
            firstDeliveredAt.compareAndSet(0, now())
            // 渡した音声を再生し終えたあとに次ができていたら、そこで音が途切れている
            val gapMillis = (chunk.readyAt - firstDeliveredAt.get()) - (deliveredSeconds * 1000).toLong()
            if (gapMillis > 0) onGap(gapMillis)
            deliveredSeconds += chunk.pcm.size.toDouble() / bytesPerSecond
            if (!onChunk(chunk.pcm)) return false
        }
        if (done) return true
    }
}
