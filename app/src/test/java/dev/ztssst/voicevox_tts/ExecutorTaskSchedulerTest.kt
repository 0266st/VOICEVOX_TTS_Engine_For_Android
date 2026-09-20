package dev.ztssst.voicevox_tts

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 実物の ScheduledExecutorService で、TaskScheduler が動くこと（Kotlin の関数型を、Java の Runnable として渡せること）を確かめる */
class ExecutorTaskSchedulerTest {
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val scheduler = ExecutorTaskScheduler(executor)

    @After
    fun tearDown() {
        executor.shutdownNow()
    }

    @Test
    fun executeRunsTheTaskOnAnotherThread() {
        val latch = CountDownLatch(1)
        val caller = Thread.currentThread()
        var ranOn: Thread? = null
        scheduler.execute { ranOn = Thread.currentThread(); latch.countDown() }
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertFalse(ranOn === caller)
    }

    @Test
    fun executeRunsTasksInTheOrderTheyWereRegistered() {
        val order = mutableListOf<Int>()
        val latch = CountDownLatch(20)
        repeat(20) { i -> scheduler.execute { order += i; latch.countDown() } }
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals((0 until 20).toList(), order)
    }

    @Test
    fun scheduleRunsTheTaskAfterTheDelay() {
        val latch = CountDownLatch(1)
        val startedAt = System.nanoTime()
        scheduler.schedule(150) { latch.countDown() }
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
        assertTrue("elapsed=$elapsedMillis", elapsedMillis >= 140)
    }

    @Test
    fun aCancelledTaskDoesNotRun() {
        val ran = AtomicInteger(0)
        scheduler.schedule(100) { ran.incrementAndGet() }.cancel()
        Thread.sleep(300)
        assertEquals(0, ran.get())
    }
}
