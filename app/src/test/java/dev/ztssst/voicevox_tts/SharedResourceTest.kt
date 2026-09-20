package dev.ztssst.voicevox_tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future

/** 時間を自分で進める、テスト用のスケジューラ */
private class FakeScheduler : TaskScheduler {
    private class Timer(val at: Long, val task: () -> Unit) {
        var cancelled = false
    }

    private val timers = mutableListOf<Timer>()
    private val background = mutableListOf<() -> Unit>()
    private var now = 0L

    override fun schedule(delayMillis: Long, task: () -> Unit): Cancellable {
        val timer = Timer(now + delayMillis, task)
        timers += timer
        return Cancellable { timer.cancelled = true }
    }

    override fun execute(task: () -> Unit) {
        background += task
    }

    fun advance(millis: Long) {
        now += millis
        timers.filter { !it.cancelled && it.at <= now }.forEach { timers.remove(it); it.task() }
        runBackground()
    }

    fun runBackground() {
        while (background.isNotEmpty()) background.removeAt(0)()
    }

    val pendingTimers get() = timers.count { !it.cancelled }
}

class SharedResourceTest {
    private val scheduler = FakeScheduler()
    private val created = mutableListOf<String>()
    private val disposed = mutableListOf<String>()

    private fun newResource(failFirst: Boolean = false): SharedResource<String> {
        var count = 0
        return SharedResource(
            create = {
                count++
                val name = "engine$count"
                created += name
                if (failFirst && count == 1) CompletableFuture<String>().apply { completeExceptionally(RuntimeException("boom")) }
                else CompletableFuture.completedFuture(name)
            },
            dispose = { disposed += it },
            idleMillis = 1000,
            scheduler = scheduler,
        )
    }

    @Test
    fun theSecondUserGetsTheSameResource() {
        val resource = newResource()
        val first = resource.acquire()
        val second = resource.acquire()
        assertSame(first, second)
        assertEquals(listOf("engine1"), created)
    }

    @Test
    fun aResourceThatIsStillBeingCreatedCanBeAcquired() {
        val pending = CompletableFuture<String>()
        var count = 0
        val resource = SharedResource(create = { count++; pending }, dispose = { disposed += it }, idleMillis = 1000, scheduler = scheduler)
        val first = resource.acquire()
        val second = resource.acquire()
        assertSame(first, second)
        assertEquals(1, count)
    }

    @Test
    fun theResourceIsKeptAcrossUsersWhileWithinTheIdleTime() {
        val resource = newResource()
        val first = resource.acquire()
        resource.release()
        scheduler.advance(999)
        val second = resource.acquire() // 使う側がいなくなった直後に、次の使う側が来た
        assertSame(first, second)
        assertEquals(emptyList<String>(), disposed)
        assertEquals(0, scheduler.pendingTimers) // 解放のタイマーは取り消される
    }

    @Test
    fun theResourceIsDisposedAfterTheIdleTime() {
        val resource = newResource()
        resource.acquire()
        resource.release()
        scheduler.advance(999)
        assertEquals(emptyList<String>(), disposed)
        scheduler.advance(1)
        assertEquals(listOf("engine1"), disposed)
    }

    @Test
    fun aNewResourceIsCreatedAfterDisposal() {
        val resource = newResource()
        val first = resource.acquire()
        resource.release()
        scheduler.advance(1000)
        val second = resource.acquire()
        assertNotSame(first, second)
        assertEquals(listOf("engine1", "engine2"), created)
    }

    @Test
    fun theResourceIsNotDisposedWhileSomeoneIsUsingIt() {
        val resource = newResource()
        resource.acquire()
        resource.acquire()
        resource.release() // 1人は使い続けている
        scheduler.advance(10_000)
        assertEquals(emptyList<String>(), disposed)
        resource.releaseIfIdle()
        scheduler.runBackground()
        assertEquals(emptyList<String>(), disposed)
    }

    @Test
    fun releaseIfIdleDisposesRightAwayWhenNobodyUsesIt() {
        val resource = newResource()
        resource.acquire()
        resource.release()
        resource.releaseIfIdle() // メモリ不足の通知など
        scheduler.runBackground()
        assertEquals(listOf("engine1"), disposed)
        assertEquals(0, scheduler.pendingTimers) // 待っていたタイマーは取り消される
        scheduler.advance(10_000)
        assertEquals(listOf("engine1"), disposed) // 二重には解放しない
    }

    @Test
    fun releaseIfIdleDoesNothingWhenThereIsNoResource() {
        val resource = newResource()
        resource.releaseIfIdle()
        scheduler.runBackground()
        assertEquals(emptyList<String>(), disposed)
    }

    @Test
    fun aFailedCreationIsRetriedOnTheNextAcquire() {
        val resource = newResource(failFirst = true)
        val first: Future<String> = resource.acquire()
        assertTrue(first.isDone)
        val second = resource.acquire()
        assertNotSame(first, second)
        assertEquals("engine2", second.get())
    }

    @Test
    fun aResourceThatFailedToCreateIsNotDisposed() {
        val resource = newResource(failFirst = true)
        resource.acquire()
        resource.release()
        scheduler.advance(1000)
        assertEquals(emptyList<String>(), disposed)
    }

    @Test(expected = IllegalStateException::class)
    fun releaseWithoutAcquireIsAnError() {
        newResource().release()
    }
}
