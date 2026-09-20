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
 * [releaseIfIdle] が呼ばれたら、資源を解放する（[dispose]）。使う側がいるあいだは解放しない。
 * 資源は [Future] で持つので、作っている途中でも [acquire] できる。作るのに失敗したときは、次の [acquire] で作り直す。
 */
class SharedResource<T : Any>(
    private val create: () -> Future<T>,
    private val dispose: (T) -> Unit,
    private val idleMillis: Long,
    private val scheduler: TaskScheduler,
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

    /** 使われていなければ、いま解放する（メモリが足りないときなど）。使われていれば、何もしない */
    fun releaseIfIdle() {
        val released: Future<T>
        synchronized(this) {
            if (users > 0) return
            released = future ?: return
            future = null
            idleTimer?.cancel()
            idleTimer = null
        }
        // 作っている途中なら、終わるのを待つことになるので、呼んだスレッドを止めないようにバックグラウンドで解放する
        scheduler.execute { runCatching { released.get() }.getOrNull()?.let(dispose) }
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
