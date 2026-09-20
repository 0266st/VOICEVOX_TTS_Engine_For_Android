package dev.ztssst.voicevox_tts

import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

fun interface Cancellable {
    fun cancel()
}

/** 時間をおいた処理と、バックグラウンドでの処理を実行する。テストでは差し替える */
interface TaskScheduler {
    fun schedule(delayMillis: Long, task: () -> Unit): Cancellable
    fun execute(task: () -> Unit)
}

class ExecutorTaskScheduler(private val executor: ScheduledExecutorService) : TaskScheduler {
    override fun schedule(delayMillis: Long, task: () -> Unit): Cancellable {
        val scheduled = executor.schedule(task, delayMillis, TimeUnit.MILLISECONDS)
        return Cancellable { scheduled.cancel(false) }
    }

    override fun execute(task: () -> Unit) = executor.execute(task)
}

/**
 * 作るのに時間がかかる資源（エンジン）を、使う側（サービス）が作り直されても使い回す。
 *
 * 使う側は [acquire] で資源を受け取り、使い終わったら [release] を呼ぶ。使う側がいなくなって [idleMillis] たつか、
 * [releaseIfIdle] が呼ばれたら、資源を解放する（[dispose]、続けて [afterDispose]）。使う側がいるあいだは解放しない。
 * 資源は [Future] で持つので、作っている途中でも [acquire] できる。作るのに失敗したときは、次の [acquire] か
 * [refresh] で作り直す。
 *
 * **解放と、次の作成の順序:** 解放の処理（[dispose] と [afterDispose]）は、資源を手放す瞬間に、[scheduler] へ登録する。
 * [create] が、同じ [scheduler] と同じ順序で処理される場所（1本のスレッド）に作成を登録するなら、解放が終わってから
 * 次の作成が始まる。資源が大きいとき（エンジンは約230MB）に、古いものと新しいものが同時に生きて、
 * メモリが足りなくなるのを防ぐ。[afterDispose] は、[dispose] の呼び出しが終わって、資源への参照がなくなったあとで
 * 呼ばれる（GC を促すのに使う）。
 */
class SharedResource<T : Any>(
    private val create: () -> Future<T>,
    private val dispose: (T) -> Unit,
    private val idleMillis: Long,
    private val scheduler: TaskScheduler,
    private val afterDispose: () -> Unit = {},
) {
    private var future: Future<T>? = null
    private var users = 0
    private var idleTimer: Cancellable? = null

    @Synchronized
    fun acquire(): Future<T> {
        idleTimer?.cancel()
        idleTimer = null
        users++
        val current = future
        if (current == null || current.hasFailed()) future = create()
        return future!!
    }

    @Synchronized
    fun release() {
        check(users > 0) { "release() was called without acquire()" }
        users--
        if (users == 0) idleTimer = scheduler.schedule(idleMillis) { releaseIfIdle() }
    }

    /**
     * 作るのに失敗していたら、作り直す。使う側の数は変えない。
     *
     * すでに [acquire] している使う側が、初期化の失敗のあとで、もう一度試すためのもの（[acquire] を呼び直すと、
     * 使う側の数が増えてしまい、[release] が釣り合わなくなる）。失敗していなければ、いまの資源をそのまま返す。
     *
     * 使う側がいないときに呼んではいけない（[IllegalStateException]）。使う側のいない資源を作ると、
     * 解放のタイマーも登録されないので、誰にも解放されず、残り続ける。呼ぶ側が、自分の [release] より前に
     * 呼ぶことを、保証すること。
     */
    @Synchronized
    fun refresh(): Future<T> {
        check(users > 0) { "refresh() was called without a user (already released)" }
        val current = future
        if (current == null || current.hasFailed()) future = create()
        return future!!
    }

    /** 使われていなければ、いま解放する（メモリが足りないときなど）。使われていれば、何もしない */
    fun releaseIfIdle() {
        synchronized(this) {
            if (users > 0) return
            val released = future ?: return
            future = null
            idleTimer?.cancel()
            idleTimer = null
            // 解放の登録は、ロックの中で行う。このあとの acquire は、ロックを取ってから作成を登録するので、
            // 作成は、必ず解放より後ろに並ぶ。作っている途中の資源は、待つ必要があるので、バックグラウンドで解放する
            scheduler.execute(DisposeTask(released, dispose))
            // 解放の呼び出し（と、それが持っていた参照）が終わってから、後始末をする
            scheduler.execute(afterDispose)
        }
    }

    /**
     * 資源への参照を、実行のあいだだけ持つ。実行し終わったら、参照を手放すので、
     * 後始末（[afterDispose]）のときには、資源は、どこからも参照されていない。
     */
    private class DisposeTask<T : Any>(released: Future<T>, private val dispose: (T) -> Unit) : () -> Unit {
        private var released: Future<T>? = released

        override fun invoke() {
            val future = released ?: return
            released = null
            runCatching { future.get() }.getOrNull()?.let(dispose)
        }
    }

    private fun Future<T>.hasFailed(): Boolean {
        if (!isDone) return false
        return try {
            get()
            false
        } catch (_: ExecutionException) {
            true
        } catch (_: CancellationException) {
            true
        }
    }
}
